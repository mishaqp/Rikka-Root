package me.rerere.rikkahub.root

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real shell/guardian/streams; the fixture replaces only the device's su authentication. */
@RunWith(Parameterized::class)
class RootPersistentSessionTest(private val brokerShell: String) {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 20_000) fun `foreground commands reuse one su and keep independent shell state`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val timings = mutableListOf<Long>()
            repeat(8) { index ->
                val before = System.nanoTime()
                val result = fixture.manager.exec("printf 'out-$index'; printf 'err-$index' >&2; exit 7", 3_000)
                timings += (System.nanoTime() - before) / 1_000_000
                assertNull(result.error)
                assertEquals(7, result.exitCode)
                assertEquals("out-$index", result.stdout)
                assertEquals("err-$index", result.stderr)
            }
            println("Root foreground local fake-su: calls=${fixture.calls.readLines().size}, ms=$timings")
            assertEquals("One UID probe and one persistent su, regardless of command count", 2, fixture.calls.readLines().size)
            assertNull(fixture.manager.exec("cd /; export RIKKA_TEST_VARIABLE=leaked; exit 19", 3_000).error)
            assertEquals("unset", fixture.manager.exec("printf '%s' \"\${RIKKA_TEST_VARIABLE-unset}\"", 3_000).stdout)
        }
    }

    @Test(timeout = 15_000) fun `prepared transport is shared by foreground and background after probe TTL`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val calls = fixture.calls.readLines().size
            fixture.now += 120_000
            assertEquals("foreground", fixture.manager.exec("printf foreground", 3_000).stdout)
            assertEquals("background", fixture.manager.exec("printf background", 3_000, headless = true).stdout)
            assertEquals(calls, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `foreground session never grants permission for headless preparation`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            assertEquals("ordinary", fixture.manager.exec("printf ordinary", 3_000).stdout)
            assertFalse(fixture.manager.isHeadlessReady())
            val calls = fixture.calls.readLines().size
            assertEquals("root_interaction_required", fixture.manager.exec("printf forbidden", 1_000, headless = true).error)
            assertEquals(calls, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `foreground and headless share one cancellable queue`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val started = File(fixture.directory, "started")
            val release = File(fixture.directory, "release")
            val first = async { fixture.manager.exec("touch ${q(started.path)}; while [ ! -f ${q(release.path)} ]; do sleep 0.01; done; printf first", 5_000) }
            var checks = 0
            try {
                awaitFile(started)
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.manager.exec("printf second", 3_000, headless = true) { checks++; true }
                }
                try {
                    assertEquals("A waiting request cannot reach dispatch checks before its predecessor finishes", 0, checks)
                    release.writeText("go")
                    assertEquals("first", first.await().stdout)
                    assertEquals("second", next.await().stdout)
                    assertEquals(2, fixture.calls.readLines().size)
                } finally { next.cancelAndJoin() }
            } finally { release.writeText("go"); first.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun `cancelled queued foreground command has no effects and session remains usable`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val started = File(fixture.directory, "started")
            val release = File(fixture.directory, "release")
            val effect = File(fixture.directory, "cancelled-effect")
            val first = async { fixture.manager.exec("touch ${q(started.path)}; while [ ! -f ${q(release.path)} ]; do sleep 0.01; done", 5_000, headless = true) }
            try {
                awaitFile(started)
                val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.exec("touch ${q(effect.path)}", 3_000) }
                queued.cancelAndJoin()
                release.writeText("go")
                assertEquals(0, first.await().exitCode)
                assertFalse(effect.exists())
                assertEquals("next", fixture.manager.exec("printf next", 3_000).stdout)
                assertEquals(2, fixture.calls.readLines().size)
            } finally { release.writeText("go"); first.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun `approval revoked during queue wait prevents dispatch`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val started = File(fixture.directory, "started")
            val release = File(fixture.directory, "release")
            val effect = File(fixture.directory, "revoked-effect")
            val first = async { fixture.manager.exec("touch ${q(started.path)}; while [ ! -f ${q(release.path)} ]; do sleep 0.01; done", 5_000, headless = true) }
            var allowed = true
            try {
                awaitFile(started)
                val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.exec("touch ${q(effect.path)}", 3_000) { allowed } }
                try {
                    allowed = false
                    release.writeText("go")
                    first.await()
                    assertEquals("root_approval_required", queued.await().error)
                    assertFalse(effect.exists())
                    assertEquals(2, fixture.calls.readLines().size)
                } finally { queued.cancelAndJoin() }
            } finally { release.writeText("go"); first.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun `foreground missing acknowledgement preserves partial streams without fallback replay`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("mv", "exit 1")
            fixture.manager.verifyRoot(prepareHeadless = true)
            val effect = File(fixture.directory, "once")
            val result = fixture.manager.exec("printf partial; printf warning >&2; echo once >> ${q(effect.path)}", 100)
            assertEquals("root_cleanup_unconfirmed", result.error)
            assertNull(result.exitCode)
            assertEquals("partial", result.stdout)
            assertEquals("warning", result.stderr)
            assertEquals(listOf("once"), effect.readLines())
            assertEquals("A submitted request must never fall back to a new su", 2, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `unsupported broker falls back before dispatch and executes command only once`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("mkfifo", "exit 1")
            // FIFO setup is probed before accepting a request; one-shot setsid remains available.
            val effect = File(fixture.directory, "fallback-once")
            val command = "echo once >> ${q(effect.path)}; printf fallback; exit 7"
            val result = fixture.manager.exec(command, 3_000)
            assertNull(result.error)
            assertEquals(7, result.exitCode)
            assertEquals("fallback", result.stdout)
            assertEquals(listOf("once"), effect.readLines())
        }
    }

    @Test(timeout = 15_000) fun `close during foreground preparation prevents dispatch and fallback`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val opening = File(fixture.directory, "opening")
            val release = File(fixture.directory, "release")
            fixture.beforeBroker = "touch ${q(opening.path)}; while [ ! -f ${q(release.path)} ]; do sleep 0.01; done"
            fixture.writeSu()
            val effect = File(fixture.directory, "closed-effect")
            val job = async { fixture.manager.exec("touch ${q(effect.path)}", 3_000) }
            try {
                awaitFile(opening)
                fixture.manager.close()
                release.writeText("go")
                assertEquals("root_interaction_required", job.await().error)
                assertFalse(effect.exists())
                assertFalse(fixture.manager.isHeadlessReady())
                assertEquals(2, fixture.calls.readLines().size)
            } finally { release.writeText("go"); job.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun `block output capture preserves UTF-8 prefixes without byte sized dd reads`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val byteReads = File(fixture.directory, "byte-sized-reads")
            fixture.executable("dd", "for arg in \"\$@\"; do if [ \"\$arg\" = bs=1 ]; then touch ${q(byteReads.path)}; exit 91; fi; done\nexec /bin/dd \"\$@\"")
            fixture.manager.verifyRoot(prepareHeadless = true)
            val result = fixture.manager.exec("head -c 7999 /dev/zero | tr '\\000' a; printf 'éZ'; head -c 1999 /dev/zero | tr '\\000' b >&2; printf '🙂Z' >&2", 3_000, headless = true)
            assertEquals(0, result.exitCode)
            assertTrue("stdout prefix was lost", result.stdout.startsWith("a".repeat(7_999)))
            assertTrue("stderr prefix was lost", result.stderr.startsWith("b".repeat(1_999)))
            assertTrue(result.stdoutTruncated)
            assertTrue(result.stderrTruncated)
            assertFalse(result.stdout.contains('\uFFFD'))
            assertFalse(result.stderr.contains('\uFFFD'))
            assertFalse("Broker must capture output in blocks", byteReads.exists())
        }
    }

    @Test(timeout = 15_000) fun `fragmented output fills the whole bounded prefix and keeps exact cap metadata`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val chunks = fixture.manager.exec("for value in 1 2 3 4 5; do printf '%s ' \"\$value\"; sleep 0.01; done; printf warning >&2", 3_000, headless = true)
            assertEquals("1 2 3 4 5 ", chunks.stdout)
            assertEquals("warning", chunks.stderr)
            for (size in listOf(7_999, 8_000, 8_001)) {
                val result = fixture.manager.exec("head -c $size /dev/zero | tr '\\000' x", 3_000, headless = true)
                assertEquals(0, result.exitCode)
                assertEquals(size > 8_000, result.stdoutTruncated)
                assertTrue(result.stdout.startsWith("x".repeat(minOf(size, 8_000))))
                if (size <= 8_000) assertEquals(size, result.stdout.length)
            }
        }
    }

    @Test(timeout = 15_000) fun `completed command does not wait for the watchdog polling interval`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.transportShell = "/bin/bash"
            fixture.writeSu()
            val polling = File(fixture.directory, "watchdog-polling")
            val release = File(fixture.directory, "release-poll")
            fixture.executable("sleep", """
                if [ "${'$'}1" = 0.05 ]; then
                    touch ${q(polling.path)}
                    count=100
                    while [ ! -f ${q(release.path)} ] && [ "${'$'}count" -gt 0 ]; do /bin/sleep 0.01; count=${'$'}((count - 1)); done
                fi
                exec /bin/sleep "${'$'}@"
            """.trimIndent())
            fixture.manager.verifyRoot(prepareHeadless = true)
            try {
                val result = fixture.manager.exec("sleep 0.1; printf completed", 500, headless = true)
                assertNull("Completion must notify the guardian independently of its polling sleep", result.error)
                assertEquals(0, result.exitCode)
                assertEquals("completed", result.stdout)
                assertFalse("Native timed read must not depend on a polling sleep", polling.exists())
            } finally { release.writeText("go") }
        }
    }

    @Test(timeout = 15_000) fun `process identity checks use builtin reads without spawning cat for proc`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("cat", "case \"\$1\" in /proc/*/stat) exit 91;; esac\nexec /bin/cat \"\$@\"")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("printf identity-checked", 3_000, headless = true)
            assertNull(result.error)
            assertEquals(0, result.exitCode)
            assertEquals("identity-checked", result.stdout)
            assertEquals(2, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `completion after the app deadline cannot become a successful exit`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("sleep", "if [ \"\$1\" = 0.05 ]; then exec /bin/sleep 0.25; fi\nexec /bin/sleep \"\$@\"")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("sleep 0.15; printf late; exit 7", 100, headless = true)
            assertEquals("command_timeout", result.error)
            assertNull(result.exitCode)
        }
    }

    @Test(timeout = 15_000) fun `failed auxiliary process launch cannot strand the command beyond timeout`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val broker = File(fixture.directory, "broker-pid")
            fixture.beforeBroker = "echo \$\$ > ${q(broker.path)}"
            fixture.writeSu()
            // Fault injection: reject the optional supervisor launch, keep command/drain launches real.
            fixture.executable("setsid", "if [ \"\$#\" -eq 9 ]; then case \"\$6\" in out|err) :;; *) exit 125;; esac; fi\nexec /usr/bin/setsid \"\$@\"")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val pid = File(fixture.directory, "command-pid")
            val job = async { fixture.manager.exec("echo \$\$ > ${q(pid.path)}; sleep 30 & wait", 200, headless = true) }
            val owned = mutableListOf<ProcessHandle>()
            try {
                awaitFile(pid)
                ProcessHandle.of(broker.readText().trim().toLong()).ifPresent { handle -> handle.descendants().use { owned.addAll(it.toList()) } }
                val result = job.await()
                assertEquals("command_timeout", result.error)
                val stat = File("/proc/${pid.readText().trim()}/stat")
                assertFalse("Known command group must be stopped", stat.isFile && !stat.readText().substringAfterLast(") ").startsWith("Z"))
            } finally {
                job.cancelAndJoin()
                owned.asReversed().forEach { it.destroyForcibly() }
            }
        }
    }

    @Test(timeout = 15_000) fun `portable block capture also works when count bytes is unsupported`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("dd", "for arg in \"\$@\"; do [ \"\$arg\" != iflag=count_bytes ] || exit 91; done\nexec /bin/dd \"\$@\"")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("head -c 9000 /dev/zero | tr '\\000' a; printf warning >&2", 3_000, headless = true)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.startsWith("a".repeat(8_000)))
            assertTrue(result.stdoutTruncated)
            assertEquals("warning", result.stderr)
            assertFalse(result.stderrTruncated)
            assertEquals(2, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `missing byte counter retains the old bounded capture`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("wc", "exit 91")
            fixture.manager.verifyRoot(prepareHeadless = true)
            val result = fixture.manager.exec("head -c 9000 /dev/zero | tr '\\000' a", 3_000, headless = true)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.startsWith("a".repeat(8_000)))
            assertTrue(result.stdoutTruncated)
        }
    }

    @Test(timeout = 15_000) fun `cancellation during foreground authentication closes the new shell without dispatch`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val opening = File(fixture.directory, "opening")
            val pid = File(fixture.directory, "opening-pid")
            val effect = File(fixture.directory, "cancelled-open-effect")
            fixture.beforeBroker = "echo \$\$ > ${q(pid.path)}; touch ${q(opening.path)}; exec sleep 30"
            fixture.writeSu()
            val job = async { fixture.manager.exec("touch ${q(effect.path)}", 3_000) }
            try {
                awaitFile(opening)
                job.cancelAndJoin()
                val stat = File("/proc/${pid.readText().trim()}/stat")
                assertFalse(stat.isFile && !stat.readText().substringAfterLast(") ").startsWith("Z"))
                assertFalse(effect.exists())
                assertFalse(fixture.manager.isHeadlessReady())
                assertEquals(2, fixture.calls.readLines().size)
            } finally { job.cancelAndJoin() }
        }
    }

    private suspend fun awaitFile(file: File) = withTimeout(3_000) { while (!file.isFile) delay(10) }

    @Test(timeout = 20_000) fun `completed foreground launch preserves detached children like the one shot transport`() = runBlocking {
        for (fallback in listOf(true, false)) for (code in listOf(0, 7)) {
            Fixture(folder.newFolder(), brokerShell).use { fixture ->
                if (fallback) fixture.executable("mkfifo", "exit 1")
                val pid = File(fixture.directory, "detached-pid")
                try {
                    val result = fixture.manager.exec("sleep 30 >/dev/null 2>&1 & echo \$! > ${q(pid.path)}; printf launched; exit $code", 3_000)
                    assertNull(result.error)
                    assertEquals(code, result.exitCode)
                    assertEquals("launched", result.stdout)
                    fixture.manager.close()
                    val stat = File("/proc/${pid.readText().trim()}/stat")
                    val alive = stat.isFile && !stat.readText().substringAfterLast(") ").startsWith("Z")
                    println("Completed foreground fallback=$fallback exit=$code detached child alive=$alive")
                    assertTrue("Transport reuse must preserve an explicitly launched background process", alive)
                } finally {
                    if (pid.isFile) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun `completed headless command still cleans up its ordinary background children`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val pid = File(fixture.directory, "headless-child")
            try {
                val result = fixture.manager.exec("sleep 30 >/dev/null 2>&1 & echo \$! > ${q(pid.path)}", 3_000, headless = true)
                assertEquals(0, result.exitCode)
                val stat = File("/proc/${pid.readText().trim()}/stat")
                assertFalse(stat.isFile && !stat.readText().substringAfterLast(") ").startsWith("Z"))
            } finally {
                if (pid.isFile) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            }
        }
    }

    @Test(timeout = 15_000) fun `foreground preserves sh c argv zero and su umask without changing headless defaults`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.beforeBroker = "umask 022"
            fixture.writeSu()
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("printf '%s\\n' \"\$0\"; umask", 3_000)
            assertEquals(0, result.exitCode)
            assertEquals("sh\n0022\n", result.stdout)
            assertEquals(0, fixture.manager.exec("umask 0077; cd /; exit 0", 3_000).exitCode)
            assertEquals("0022\n", fixture.manager.exec("umask", 3_000).stdout)
            assertEquals("0077\n", fixture.manager.exec("umask", 3_000, headless = true).stdout)
            assertEquals(2, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 15_000) fun `commands cannot inherit either private completion or release descriptor`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            for (headless in listOf(false, true)) {
                val result = fixture.manager.exec("if ( : >&3 ) 2>/dev/null; then exit 31; fi; if ( : >&4 ) 2>/dev/null; then exit 41; fi; printf private", 3_000, headless = headless)
                assertEquals(0, result.exitCode)
                assertEquals("private", result.stdout)
            }
        }
    }

    private class Fixture(val directory: File, initialShell: String) : AutoCloseable {
        val bin = File(directory, "bin").apply { mkdir() }
        val calls = File(directory, "calls").apply { writeText("") }
        val su = File(directory, "su")
        var now = 1_000L
        var beforeBroker = ""
        var transportShell = initialShell
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = directory, clockMs = { now })

        init { executable("id", "printf '0\\n'"); writeSu() }

        fun executable(name: String, body: String) {
            File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); check(setExecutable(true)) }
        }

        fun writeSu() {
            su.writeText("#!/bin/sh\nprintf 'called\\n' >> ${q(calls.path)}\nPATH=${q(bin.path)}:\"\$PATH\"\nexport PATH\nif [ \"\$2\" != 'id -u' ]; then\n$beforeBroker\n:\nfi\nexec ${q(transportShell)} -c \"\$2\"\n")
            check(su.setExecutable(true))
        }

        override fun close() = manager.close()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "broker shell={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf("/bin/sh"), arrayOf("/bin/bash"))

        private fun q(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
