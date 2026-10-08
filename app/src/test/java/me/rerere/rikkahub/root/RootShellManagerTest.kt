package me.rerere.rikkahub.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootShellManagerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun revocationAfterProbePreventsLaunch() = runBlocking {
        val (su, log) = fakeSu()
        val result = RootShellManager(suExecutable = su.path).exec("printf should-not-run", 2_000) { false }
        assertEquals("root_approval_required", result.error)
        assertEquals(listOf("called"), log.readLines())
        assertEquals("", result.stdout)
    }

    @Test fun revocationAtFinalIOCheckPreventsLaunch() = runBlocking {
        val (su, log) = fakeSu()
        var checks = 0
        val result = RootShellManager(suExecutable = su.path).exec("printf should-not-run", 2_000) { ++checks == 1 }
        assertEquals("root_approval_required", result.error)
        assertEquals(2, checks)
        assertEquals(listOf("called"), log.readLines())
    }

    @Test fun `headless without explicit preparation never launches su`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val result = manager.exec("printf forbidden", 1_000, headless = true)
        assertEquals("root_interaction_required", result.error)
        assertTrue(log.readText().isEmpty())
    }

    @Test fun `cached verification alone does not authorize headless transport`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        assertEquals(RootStatus.READY, manager.verifyRoot())
        assertEquals("root_interaction_required", manager.exec("printf forbidden", 1_000, headless = true).error)
        assertEquals(1, log.readLines().size)
    }

    @Test fun `prepared headless transport survives verification TTL without fresh su`() = runBlocking {
        val (su, log) = fakeSu()
        var now = 1_000L
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root, clockMs = { now })
        try {
            assertEquals(RootStatus.READY, manager.verifyRoot(prepareHeadless = true))
            val calls = log.readLines().size
            now += 120_000
            val result = manager.exec("printf headless", 2_000, headless = true)
            assertEquals(null, result.error)
            assertEquals("headless", result.stdout)
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `headless approval revocation prevents dispatch without new su`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val effect = File(temp.root, "revoked-headless")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            var checks = 0
            val result = manager.exec("touch '${effect.path}'", 2_000, headless = true) { ++checks == 1 }
            assertEquals("root_approval_required", result.error)
            assertFalse(effect.exists())
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `headless failing command is never replayed`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val effect = File(temp.root, "once-headless")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            val result = manager.exec("echo once >> '${effect.path}'; printf failed; exit 7", 2_000, headless = true)
            assertEquals(7, result.exitCode)
            assertEquals("failed", result.stdout)
            assertEquals(listOf("once"), effect.readLines())
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `headless timeout stops TERM ignoring descendants with no cleanup su`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val pid = File(temp.root, "headless-timeout-pid")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            val result = manager.exec("trap '' TERM; echo \$\$ > '${pid.path}'; sleep 30 & wait", 200, headless = true)
            assertEquals("command_timeout", result.error)
            assertTrue(pid.exists())
            assertTerminated(pid)
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `headless cancellation stops command without fresh su`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val pid = File(temp.root, "headless-cancel-pid")
        manager.verifyRoot(prepareHeadless = true)
        val calls = log.readLines().size
        val job = async { manager.exec("echo \$\$ > '${pid.path}'; sleep 30 & wait", 30_000, headless = true) }
        try {
            withTimeout(3_000) { while (!pid.exists()) delay(10) }
            job.cancel()
            try { job.await() } catch (_: CancellationException) {}
            assertTerminated(pid)
            assertEquals(calls, log.readLines().size)
        } finally { job.cancel(); manager.close() }
    }

    @Test fun `headless stdout and stderr are bounded and concurrent calls are isolated`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            val first = async { manager.exec("head -c 20000 /dev/zero; head -c 10000 /dev/zero >&2", 3_000, headless = true) }
            val second = async { manager.exec("printf second", 3_000, headless = true) }
            val output = first.await()
            assertEquals(0, output.exitCode)
            assertTrue(output.stdout.length <= 8_100)
            assertTrue(output.stderr.length <= 2_100)
            assertEquals("second", second.await().stdout)
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `closed transport requires foreground interaction and does not replay`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        manager.verifyRoot(prepareHeadless = true)
        manager.close()
        val calls = log.readLines().size
        assertEquals("root_interaction_required", manager.exec("printf forbidden", 1_000, headless = true).error)
        assertEquals(calls, log.readLines().size)
    }

    @Test fun `headless readiness is a pure transport check`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        try {
            assertFalse(manager.isHeadlessReady())
            assertTrue(log.readText().isEmpty())
            manager.verifyRoot()
            assertFalse(manager.isHeadlessReady())
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            repeat(10) { assertTrue(manager.isHeadlessReady()) }
            assertEquals(calls, log.readLines().size)
            manager.close()
            assertFalse(manager.isHeadlessReady())
        } finally { manager.close() }
    }

    @Test fun `lost transport fails closed without launching a replacement`() = runBlocking {
        val (su, log) = fakeSu()
        val brokerPid = File(temp.root, "broker-process")
        su.writeText(su.readText().replace("exec /bin/sh -c", "echo \$\$ > '${brokerPid.path}'\nexec /bin/sh -c"))
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            ProcessHandle.of(brokerPid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            withTimeout(2_000) { while (manager.isHeadlessReady()) delay(10) }
            val effect = File(temp.root, "lost-transport-effect")
            assertEquals("root_interaction_required", manager.exec("touch '${effect.path}'", 2_000, headless = true).error)
            assertFalse(effect.exists())
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `broker rechecks uid and invalidates transport before a revoked execution`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            temp.root.listFiles()!!.filter { it.isDirectory && it.name.startsWith("root-bin-") }
                .forEach { File(it, "id").writeText("#!/bin/sh\nprintf '2000\\n'\n") }
            val effect = File(temp.root, "revoked-uid-effect")
            assertEquals("root_interaction_required", manager.exec("touch '${effect.path}'", 2_000, headless = true).error)
            assertFalse(effect.exists())
            assertFalse(manager.isHeadlessReady())
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `broker death during a command still lets guardian stop its group`() = runBlocking {
        val (su, log) = fakeSu()
        val brokerPid = File(temp.root, "dying-broker-process")
        su.writeText(su.readText().replace("exec /bin/sh -c", "echo \$\$ > '${brokerPid.path}'\nexec /bin/sh -c"))
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val childPid = File(temp.root, "dying-broker-child")
        manager.verifyRoot(prepareHeadless = true)
        val calls = log.readLines().size
        val job = async { manager.exec("echo \$\$ > '${childPid.path}'; sleep 30 & wait", 10_000, headless = true) }
        try {
            withTimeout(2_000) { while (!childPid.exists()) delay(10) }
            ProcessHandle.of(brokerPid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            job.await()
            assertTerminated(childPid)
            assertEquals(calls, log.readLines().size)
        } finally { job.cancel(); manager.close() }
    }

    @Test fun `closing during explicit preparation cannot resurrect the transport`() = runBlocking {
        val (su, _) = fakeSu()
        val opening = File(temp.root, "opening-broker")
        su.writeText(su.readText().replace("exec /bin/sh -c", "touch '${opening.path}'; sleep 0.2\nexec /bin/sh -c"))
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val preparation = async { manager.verifyRoot(prepareHeadless = true) }
        try {
            withTimeout(2_000) { while (!opening.exists()) delay(10) }
            manager.close()
            preparation.await()
            assertFalse("close must win over an earlier in-flight preparation", manager.isHeadlessReady())
        } finally { preparation.cancel(); manager.close() }
    }

    @Test fun `all headless request control files are owner only`() = runBlocking {
        val (su, _) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        manager.verifyRoot(prepareHeadless = true)
        val started = File(temp.root, "private-command-started")
        val job = async { manager.exec("touch '${started.path}'; sleep 30", 30_000, headless = true) }
        try {
            withTimeout(2_000) { while (!started.exists()) delay(10) }
            val base = temp.root.listFiles()!!.single { it.name.startsWith("root-headless-") }
            base.walkTopDown().forEach { file ->
                val permissions = java.nio.file.Files.getPosixFilePermissions(file.toPath())
                assertTrue("private permissions required for ${file.name}: $permissions", permissions.none {
                    it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")
                })
            }
        } finally { job.cancel(); try { job.await() } catch (_: CancellationException) {}; manager.close() }
    }

    @Test fun `escaped FIFO writer cannot stall broker or leak drain processes`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val escapedPid = File(temp.root, "escaped-output-holder")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            val nested = "echo \$\$ > '${escapedPid.path}'; exec sleep 30"
            val before = System.nanoTime()
            val result = manager.exec("setsid sh -c \"${nested.replace("$", "\\$")}\" & sleep 0.1; printf done", 300, headless = true)
            assertTrue("guardian must bound output draining", (System.nanoTime() - before) / 1_000_000 < 2_000)
            assertEquals(0, result.exitCode)
            assertEquals("done", result.stdout)
            assertEquals("next", manager.exec("printf next", 1_000, headless = true).stdout)
            assertEquals(calls, log.readLines().size)
        } finally {
            if (escapedPid.isFile) ProcessHandle.of(escapedPid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            manager.close()
        }
    }

    @Test fun `killed group leader cannot leave ordinary descendants behind a successful cleanup ack`() = runBlocking {
        val (su, _) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val pid = File(temp.root, "leader-killed-child")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val result = manager.exec("sleep 30 & echo \$! > '${pid.path}'; kill -KILL \$PPID; wait", 200, headless = true)
            if (result.error == "root_cleanup_unconfirmed") assertFalse(manager.isHeadlessReady())
            else { assertEquals("command_timeout", result.error); assertTerminated(pid) }
        } finally {
            if (pid.exists()) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            manager.close()
        }
    }

    @Test fun `unknown guardian outcome quarantines transport and never repeats command`() = runBlocking {
        val (su, log) = fakeSu()
        val bin = temp.root.listFiles()!!.single { it.isDirectory && it.name.startsWith("root-bin-") }
        File(bin, "mv").apply {
            writeText("#!/bin/sh\nread nonce outcome < \"\$1\"\nprintf '%s unknown' \"\$nonce\" > \"\$2\"\n")
            setExecutable(true)
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val effect = File(temp.root, "unknown-ack-effect")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            assertEquals("root_cleanup_unconfirmed", manager.exec("echo once >> '${effect.path}'", 1_000, headless = true).error)
            assertFalse(manager.isHeadlessReady())
            assertEquals("root_interaction_required", manager.exec("echo twice >> '${effect.path}'", 1_000, headless = true).error)
            assertEquals(listOf("once"), effect.readLines())
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `cancellation also quarantines an unconfirmed guardian acknowledgement`() = runBlocking {
        val (su, _) = fakeSu()
        val bin = temp.root.listFiles()!!.single { it.isDirectory && it.name.startsWith("root-bin-") }
        File(bin, "mv").apply {
            writeText("#!/bin/sh\nread nonce outcome < \"\$1\"\nprintf '%s cleanup_unconfirmed' \"\$nonce\" > \"\$2\"\n")
            setExecutable(true)
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val pid = File(temp.root, "cancel-unconfirmed-child")
        manager.verifyRoot(prepareHeadless = true)
        val job = async { manager.exec("echo \$\$ > '${pid.path}'; sleep 30", 30_000, headless = true) }
        try {
            withTimeout(2_000) { while (!pid.exists()) delay(10) }
            job.cancel()
            try { job.await() } catch (_: CancellationException) {}
            assertFalse(manager.isHeadlessReady())
            assertTerminated(pid)
        } finally { job.cancel(); manager.close() }
    }

    @Test fun `delayed group identity fails closed and cannot start command after deadline`() = runBlocking {
        val (su, log) = fakeSu()
        val bin = temp.root.listFiles()!!.single { it.isDirectory && it.name.startsWith("root-bin-") }
        File(bin, "setsid").apply {
            writeText("#!/bin/sh\nsleep 1\nexec /usr/bin/setsid \"\$@\"\n")
            setExecutable(true)
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val effect = File(temp.root, "late-headless-effect")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            assertEquals("root_cleanup_unconfirmed", manager.exec("touch '${effect.path}'", 100, headless = true).error)
            assertFalse(manager.isHeadlessReady())
            delay(1_100)
            assertFalse(effect.exists())
            assertEquals("root_interaction_required", manager.exec("touch '${effect.path}'", 100, headless = true).error)
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `missing guardian ack quarantines session and retains only private control evidence`() = runBlocking {
        val (su, log) = fakeSu()
        val bin = temp.root.listFiles()!!.single { it.isDirectory && it.name.startsWith("root-bin-") }
        File(bin, "mv").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val effect = File(temp.root, "missing-ack-once")
        try {
            manager.verifyRoot(prepareHeadless = true)
            val calls = log.readLines().size
            assertEquals("root_cleanup_unconfirmed", manager.exec("echo once >> '${effect.path}'", 100, headless = true).error)
            assertFalse(manager.isHeadlessReady())
            assertEquals(listOf("once"), effect.readLines())
            val base = temp.root.listFiles()!!.single { it.name.startsWith("root-headless-") }
            assertTrue(base.walkTopDown().any { it.name == "pid" })
            assertFalse(base.walkTopDown().any { it.name == "command" })
            assertEquals("root_interaction_required", manager.exec("echo twice >> '${effect.path}'", 100, headless = true).error)
            assertEquals(calls, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `app creates its private readable readiness file before root is spawned`() = runBlocking {
        val (su, _) = fakeSu()
        val opening = File(temp.root, "ownership-opening")
        su.writeText(su.readText().replace("exec /bin/sh -c", "touch '${opening.path}'; sleep 0.3\nexec /bin/sh -c"))
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        val preparation = async { manager.verifyRoot(prepareHeadless = true) }
        try {
            withTimeout(2_000) { while (!opening.exists()) delay(10) }
            val base = temp.root.listFiles()!!.single { it.name.startsWith("root-headless-") }
            val ready = File(base, "ready")
            assertTrue("readiness file must be app-created before privileged redirect", ready.isFile)
            assertEquals(0L, ready.length())
            assertEquals(java.nio.file.Files.getOwner(base.toPath()), java.nio.file.Files.getOwner(ready.toPath()))
            preparation.await()
            assertTrue(manager.isHeadlessReady())
        } finally { preparation.cancel(); try { preparation.await() } catch (_: CancellationException) {}; manager.close() }
    }

    @Test fun `reply and output files are app-created before privileged request handling`() = runBlocking {
        val (su, _) = fakeSu()
        val bin = temp.root.listFiles()!!.single { it.isDirectory && it.name.startsWith("root-bin-") }
        File(bin, "mkfifo").apply {
            writeText("""
                #!/bin/sh
                request=${'$'}(dirname "${'$'}1")
                for file in stdout stderr result.new; do [ -f "${'$'}request/${'$'}file" ] || exit 1; done
                exec /usr/bin/mkfifo "${'$'}@"
            """.trimIndent() + "\n")
            setExecutable(true)
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root)
        try {
            manager.verifyRoot(prepareHeadless = true)
            val result = manager.exec("printf owned; printf private >&2", 1_000, headless = true)
            assertEquals(null, result.error)
            assertEquals("owned", result.stdout)
            assertEquals("private", result.stderr)
        } finally { manager.close() }
    }

    @Test fun `unavailable preparation reports failure and clears cached ready`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.newFile("not-a-directory"))
        try {
            assertEquals(RootStatus.DENIED_OR_FAILED, manager.verifyRoot(prepareHeadless = true))
            assertEquals(RootStatus.DENIED_OR_FAILED, manager.status.value)
            assertFalse(manager.isHeadlessReady())
            assertEquals(1, log.readLines().size)
            assertEquals(RootStatus.READY, manager.verifyRoot())
            assertEquals("a failed preparation must not reuse stale READY", 2, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `preparation security exception becomes a safe failed status`() = runBlocking {
        val (su, log) = fakeSu()
        val inaccessible = object : File(temp.root.path) {
            override fun toPath(): java.nio.file.Path = throw SecurityException("denied control storage")
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = inaccessible)
        try {
            assertEquals(RootStatus.DENIED_OR_FAILED, manager.verifyRoot(prepareHeadless = true))
            assertEquals(RootStatus.DENIED_OR_FAILED, manager.status.value)
            assertFalse(manager.isHeadlessReady())
            assertEquals(1, log.readLines().size)
            assertEquals(RootStatus.READY, manager.verifyRoot())
            assertEquals(2, log.readLines().size)
        } finally { manager.close() }
    }

    @Test fun `preparation readiness read failure closes its process and reports failure`() = runBlocking {
        val (su, _) = fakeSu()
        val brokerPid = File(temp.root, "failed-ready-pid")
        su.writeText(su.readText().replace("exec /bin/sh -c", "echo \$\$ > '${brokerPid.path}'; exec sleep 30\nexec /bin/sh -c"))
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = temp.root, probeTimeoutMs = 300)
        val preparation = async { manager.verifyRoot(prepareHeadless = true) }
        try {
            withTimeout(2_000) { while (!brokerPid.exists()) delay(10) }
            val base = temp.root.listFiles()!!.single { it.name.startsWith("root-headless-") }
            assertTrue(File(base, "ready").setReadable(false, false))
            assertEquals(RootStatus.DENIED_OR_FAILED, preparation.await())
            assertEquals(RootStatus.DENIED_OR_FAILED, manager.status.value)
            assertFalse(manager.isHeadlessReady())
            assertTerminated(brokerPid)
        } finally { preparation.cancel(); manager.close() }
    }

    private fun assertTerminated(pid: File) {
        val processId = pid.readText().trim().toLong()
        val stat = File("/proc/$processId/stat")
        assertFalse("owned command must be terminated", stat.exists() && !stat.readText().substringAfterLast(") ").startsWith("Z"))
    }

    private fun fakeSu(uid: String = "0", probeExit: Int = 0): Pair<File, File> {
        val log = File(temp.root, "calls-${System.nanoTime()}").apply { writeText("") }
        val script = temp.newFile("su-${System.nanoTime()}")
        val bin = temp.newFolder("root-bin-${System.nanoTime()}")
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '%s\\n' '$uid'\n"); setExecutable(true) }
        script.writeText("""
            #!/bin/sh
            printf '%s\n' called >> '${log.path}'
            if [ "${'$'}2" = 'id -u' ]; then
              printf '%s\n' '$uid'
              exit $probeExit
            fi
            PATH='${bin.path}':${'$'}PATH
            export PATH
            exec /bin/sh -c "${'$'}2"
        """.trimIndent() + "\n")
        assertTrue(script.setExecutable(true))
        return script to log
    }

    @Test fun `starts unchecked and verifies uid zero before command`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path)
        assertEquals(RootStatus.UNCHECKED, manager.status.value)
        val result = manager.exec("printf executed", 2_000)
        assertEquals("executed", result.stdout)
        assertEquals(2, log.readLines().size)

        assertEquals(RootStatus.READY, manager.status.value)
    }

    @Test fun `rejects a nonroot uid without executing requested command`() = runBlocking {
        val (su, log) = fakeSu("2000")
        val manager = RootShellManager(suExecutable = su.path)
        val result = manager.exec("printf should-not-run", 2_000)
        assertEquals("root_not_granted", result.error)
        assertEquals(listOf("called"), log.readLines())
        assertEquals(RootStatus.NOT_ROOT, manager.status.value)
    }

    @Test fun `rejects failed probe even when output says uid zero`() = runBlocking {
        val (su, log) = fakeSu("0", 1)
        val manager = RootShellManager(suExecutable = su.path)
        assertEquals(RootStatus.DENIED_OR_FAILED, manager.verifyRoot())
        assertEquals(listOf("called"), log.readLines())
    }

    @Test fun `missing su reports unavailable`() = runBlocking {
        val manager = RootShellManager(suExecutable = "/nonexistent/root-su")
        assertEquals(RootStatus.UNAVAILABLE, manager.verifyRoot())
        assertEquals("su_not_available", manager.exec("printf nope", 2_000).error)
    }

    @Test fun `successful probe cache expires and forced verification bypasses cache`() = runBlocking {
        val (su, log) = fakeSu()
        var now = 1_000L
        val manager = RootShellManager(suExecutable = su.path, clockMs = { now })
        assertEquals(RootStatus.READY, manager.verifyRoot())
        manager.verifyRoot()
        assertEquals(1, log.readLines().size)
        manager.verifyRoot(force = true)
        assertEquals(2, log.readLines().size)
        now += 60_001
        manager.verifyRoot()
        assertEquals(3, log.readLines().size)
    }

    @Test fun `failed probe is retried after short cache expires`() = runBlocking {
        val (su, log) = fakeSu("2000")
        var now = 1_000L
        val manager = RootShellManager(suExecutable = su.path, clockMs = { now })
        manager.verifyRoot()
        manager.verifyRoot()
        assertEquals(1, log.readLines().size)
        now += 15_001
        manager.verifyRoot()
        assertEquals(2, log.readLines().size)
    }

    @Test fun `launched failing command is executed once`() = runBlocking {
        val (su, log) = fakeSu()
        val result = RootShellManager(suExecutable = su.path).exec("printf failed; exit 9", 2_000)
        assertEquals(9, result.exitCode)
        assertEquals("failed", result.stdout)
        assertEquals(2, log.readLines().size)
    }

    @Test fun `launched timed out command is executed once`() = runBlocking {
        val (su, log) = fakeSu()
        val command = "printf started; exec sleep 30"
        val result = RootShellManager(suExecutable = su.path).exec(command, 100)
        assertEquals("command_timeout", result.error)
        assertEquals(3, log.readLines().size)
    }

    @Test fun `accepts a root manager banner only when final uid is zero`() = runBlocking {
        val (su, _) = fakeSu("KernelSU\n0")
        assertEquals(RootStatus.READY, RootShellManager(suExecutable = su.path).verifyRoot())
    }

    @Test fun `concurrent root checks share a successful probe`() = runBlocking {
        val (su, log) = fakeSu()
        val manager = RootShellManager(suExecutable = su.path)
        val jobs = List(10) { async { manager.verifyRoot() } }
        jobs.forEach { assertEquals(RootStatus.READY, it.await()) }
        assertEquals(1, log.readLines().size)
    }

    @Test fun `cancelling verification resets status and allows a fresh verification`() = runBlocking {
        val pid = temp.newFile("probe-pid")
        val script = temp.newFile("slow-su")
        script.writeText("#!/bin/sh\necho \$\$ > '${pid.path}'\nexec sleep 30\n")
        assertTrue(script.setExecutable(true))
        val manager = RootShellManager(suExecutable = script.path)
        val job = async { manager.verifyRoot() }
        withTimeout(2_000) { while (pid.readText().isBlank()) delay(10) }
        val processId = pid.readText().trim().toLong()
        assertEquals(RootStatus.CHECKING, manager.status.value)
        job.cancel()
        try { job.await() } catch (_: CancellationException) {}
        assertEquals(RootStatus.UNCHECKED, manager.status.value)
        assertFalse(ProcessHandle.of(processId).map { it.isAlive }.orElse(false))
        script.writeText("#!/bin/sh\nprintf '0\\n'\n")
        assertEquals(RootStatus.READY, manager.verifyRoot())
    }

    @Test fun `root probe timeout is distinguished from denial`() = runBlocking {
        val script = temp.newFile("probe-timeout-su")
        script.writeText("#!/bin/sh\nexec sleep 30\n")
        assertTrue(script.setExecutable(true))
        val manager = RootShellManager(suExecutable = script.path, probeTimeoutMs = 100)
        val started = System.nanoTime()
        assertEquals(RootStatus.TIMED_OUT, manager.verifyRoot())
        assertTrue("probe timeout must bound verification", (System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals("root_verification_timeout", manager.exec("printf nope", 2_000).error)
    }

    @Test fun `timeout terminates ordinary shell children before they can continue`() = runBlocking {
        val (su, _) = fakeSu()
        val pid = File(temp.root, "timed-child-pid")
        val manager = RootShellManager(suExecutable = su.path)
        val command = "sleep 30 & child=\$!; echo \$child > '${pid.path}'; wait"
        try {
            assertEquals("command_timeout", manager.exec(command, 150).error)
            assertTrue(pid.exists())
            val child = ProcessHandle.of(pid.readText().trim().toLong())
            assertFalse("ordinary child must terminate with its root call", child.map { it.isAlive && !File("/proc/${it.pid()}/stat").readText().substringAfterLast(") ").startsWith("Z") }.orElse(false))
        } finally {
            if (pid.exists()) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test fun `cancellation terminates ordinary shell children`() = runBlocking {
        val (su, _) = fakeSu()
        val pid = File(temp.root, "cancelled-child-pid")
        val manager = RootShellManager(suExecutable = su.path)
        val command = "sleep 30 & child=\$!; echo \$child > '${pid.path}'; wait"
        val job = async { manager.exec(command, 30_000) }
        try {
            withTimeout(2_000) { while (!pid.exists()) delay(10) }
            val child = ProcessHandle.of(pid.readText().trim().toLong())
            job.cancel()
            try { job.await() } catch (_: CancellationException) {}
            assertFalse("ordinary child must terminate on cancellation", child.map { it.isAlive && !File("/proc/${it.pid()}/stat").readText().substringAfterLast(") ").startsWith("Z") }.orElse(false))
        } finally {
            job.cancel()
            if (pid.exists()) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test fun `cached successful probe cannot authorize a new nonroot execution`() = runBlocking {
        val (su, _) = fakeSu()
        val bin = temp.newFolder("revoked-bin")
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '2000\\n'\n"); setExecutable(true) }
        su.writeText(su.readText().replace("exec /bin/sh -c", "PATH='${bin.path}':\$PATH exec /bin/sh -c"))
        val sideEffect = File(temp.root, "must-not-exist")
        val manager = RootShellManager(suExecutable = su.path)
        assertEquals(RootStatus.READY, manager.verifyRoot())
        assertEquals("root_not_granted", manager.exec("touch '${sideEffect.path}'", 2_000).error)
        assertFalse(sideEffect.exists())
    }

    @Test fun `missing process group capability fails before executing the requested command`() = runBlocking {
        val (su, _) = fakeSu()
        val bin = temp.newFolder("no-setsid-bin")
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '0\\n'\n"); setExecutable(true) }
        su.writeText(su.readText().replace("exec /bin/sh -c", "PATH='${bin.path}' exec /bin/sh -c"))
        val sideEffect = File(temp.root, "no-setsid-must-not-exist")
        val manager = RootShellManager(suExecutable = su.path)
        assertEquals("root_cleanup_unavailable", manager.exec("touch '${sideEffect.path}'", 2_000).error)
        assertFalse(sideEffect.exists())
    }

    @Test fun `timeout terminates grandchildren that ignore TERM without losing group ownership`() = runBlocking {
        val (su, _) = fakeSu()
        val pid = File(temp.root, "ignoring-grandchild-pid")
        val sideEffect = File(temp.root, "ignoring-grandchild-side-effect")
        val nested = "trap \"\" TERM; echo \$\$ > ${pid.path}; sleep 30; printf survived > ${sideEffect.path}"
        val manager = RootShellManager(suExecutable = su.path)
        try {
            assertEquals("command_timeout", manager.exec("sh -c '$nested' & wait", 150).error)
            assertTrue(pid.exists())
            val child = ProcessHandle.of(pid.readText().trim().toLong())
            assertFalse("TERM-ignoring descendant must not survive a cancelled root group", child.map {
                it.isAlive && !File("/proc/${it.pid()}/stat").readText().substringAfterLast(") ").startsWith("Z")
            }.orElse(false))
            assertFalse(sideEffect.exists())
        } finally {
            if (pid.exists()) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { child ->
                child.descendants().forEach { it.destroyForcibly() }
                child.destroyForcibly()
            }
        }
    }
}
