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
