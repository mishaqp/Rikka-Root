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

    @Test(timeout = 15_000) fun `root probe accepts su banner when actual uid probe completes`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.su.writeText("#!/bin/sh\nPATH=${q(fixture.bin.path)}:\"\$PATH\"\nexport PATH\nprintf 'su banner\\n'\nexec ${q(brokerShell)} -c \"\$2\"\n")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot())
        }
    }

    @Test(timeout = 15_000) fun `root probe accepts CRLF with su banner`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.su.writeText("#!/bin/sh\nPATH=${q(fixture.bin.path)}:\"\$PATH\"\nexport PATH\nprintf 'banner\\r\\n'\n${q(brokerShell)} -c \"\$2\" | awk '{printf \"%s\\r\\n\", \$0}'\n")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot())
        }
    }

    @Test(timeout = 15_000) fun `root probe rejects banner zero when uid command never ran`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.su.writeText("#!/bin/sh\nprintf '0\\n'\nexit 0\n")
            assertEquals(RootStatus.DENIED_OR_FAILED, fixture.manager.verifyRoot())
        }
    }

    @Test(timeout = 15_000) fun `root probe rejects reader failure even if uid output completed`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val completed = File(fixture.directory, "probe-completed")
            fixture.su.writeText("#!/bin/sh\nPATH=${q(fixture.bin.path)}:\"\$PATH\"\nexport PATH\n${q(brokerShell)} -c \"\$2\"\ntouch ${q(completed.path)}\nsleep 2\n")
            val probe = async { fixture.manager.verifyRoot() }
            awaitFile(completed)
            val stderrReaders = Thread.getAllStackTraces().keys.filter { it.name == "root-stderr" && it.isAlive }
            assertEquals("one active stderr reader", 1, stderrReaders.size)
            stderrReaders.single().interrupt()
            assertEquals(RootStatus.DENIED_OR_FAILED, probe.await())
        }
    }

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

    @Test(timeout = 20_000) fun `immediate printf and id are acknowledged without waiting for their timeout`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            val timeoutMs = 2_000
            val printfStarted = System.nanoTime()
            val printf = fixture.manager.exec("printf immediate", timeoutMs)
            val printfMs = (System.nanoTime() - printfStarted) / 1_000_000
            val idStarted = System.nanoTime()
            val id = fixture.manager.exec("/usr/bin/id", timeoutMs)
            val idMs = (System.nanoTime() - idStarted) / 1_000_000
            println("Root immediate broker=$brokerShell printfMs=$printfMs idMs=$idMs")
            assertNull("stdout=${printf.stdout}; stderr=${printf.stderr}", printf.error)
            assertEquals(0, printf.exitCode)
            assertEquals("immediate", printf.stdout)
            assertEquals("", printf.stderr)
            assertTrue("printf waited for its $timeoutMs ms timeout: $printfMs ms", printfMs < 1_500)
            assertNull("stdout=${id.stdout}; stderr=${id.stderr}", id.error)
            assertEquals(0, id.exitCode)
            assertTrue(id.stdout.contains("uid="))
            assertEquals("", id.stderr)
            assertTrue("id waited for its $timeoutMs ms timeout: $idMs ms", idMs < 1_500)
            assertEquals("One UID probe and one persistent su", 2, fixture.calls.readLines().size)
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
            assertFalse(fixture.manager.isHeadlessReady())
            assertEquals("root_interaction_required", fixture.manager.exec("echo twice >> ${q(effect.path)}", 1_000, headless = true).error)
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
            assertEquals("root_output_incomplete", result.error)
            assertEquals(7, result.exitCode)
            assertEquals("fallback", result.stdout)
            assertEquals(listOf("once"), effect.readLines())
        }
    }

    @Test(timeout = 15_000) fun `unconfirmed drain cleanup retains independently acknowledged command exit`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val interrupted = File(fixture.directory, "drain-interrupted")
            fixture.executable("dd", """
                target=${'$'}(readlink /proc/${'$'}${'$'}/fd/1)
                case "${'$'}target" in
                  */stdout)
                    /bin/dd "${'$'}@"
                    touch ${q(interrupted.path)}
                    kill -KILL "${'$'}PPID"
                    exit 0;;
                esac
                exec /bin/dd "${'$'}@"
            """.trimIndent())
            val effect = File(fixture.directory, "drain-cleanup-runs")
            val result = fixture.manager.exec(
                "echo once >> ${q(effect.path)}; printf partial; printf warning >&2; exit 7", 5_000, headless = true,
            )
            assertTrue("The drain process was interrupted after reading its output", interrupted.exists())
            assertEquals("root_cleanup_unconfirmed", result.error)
            assertEquals(7, result.exitCode)
            assertEquals("partial", result.stdout)
            assertEquals("warning", result.stderr)
            assertEquals(listOf("once"), effect.readLines())
            assertFalse("Unconfirmed cleanup must quarantine the session", fixture.manager.isHeadlessReady())
            assertEquals("root_interaction_required", fixture.manager.exec("echo twice >> ${q(effect.path)}", 1_000, headless = true).error)
            assertEquals(listOf("once"), effect.readLines())
        }
    }

    @Test(timeout = 15_000) fun `fallback timeout reports unconfirmed cleanup when cancellation su fails`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("mkfifo", "exit 1")
            fixture.su.writeText("#!/bin/sh\nPATH=${q(fixture.bin.path)}:\"\$PATH\"\nexport PATH\ncase \"\$2\" in *'kill -KILL -'*) exit 126;; esac\nexec ${q(brokerShell)} -c \"\$2\"\n")
            val leaderStat = File(fixture.directory, "fallback-leader-stat")
            try {
                val result = fixture.manager.exec("IFS= read -r stat < /proc/\$PPID/stat; printf '%s\\n' \"\$stat\" > ${q(leaderStat.path)}; printf BEFORE; sleep 30", 1_000)
                assertEquals("root_cleanup_unconfirmed", result.error)
                assertNull(result.exitCode)
                assertTrue("cleanup warning must be explicit", result.reason.orEmpty().contains("останов"))
            } finally {
                if (leaderStat.isFile) {
                    val leader = processIdentity(leaderStat.readText().trim())
                    if (isAlive(leader) && leader.pid == leader.pgid && leader.pid == leader.sid) {
                        ProcessBuilder("/bin/kill", "-KILL", "--", "-${leader.pid}").start().waitFor()
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun `fallback timeout confirms stopped group and prevents late effects`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("mkfifo", "exit 1")
            val effects = File(fixture.directory, "fallback-timeout-runs")
            val late = File(fixture.directory, "fallback-timeout-late")
            val result = fixture.manager.exec(
                "echo once >> ${q(effects.path)}; printf BEFORE; sleep 3; touch ${q(late.path)}", 800,
            )
            assertEquals("command_timeout", result.error)
            assertNull(result.exitCode)
            assertEquals("BEFORE", result.stdout)
            delay(3_200)
            assertFalse("The timed-out group must not run the late action", late.exists())
            assertEquals(listOf("once"), effects.readLines())
            val next = fixture.manager.exec("printf NEXT_OK", 3_000)
            assertEquals("root_output_incomplete", next.error)
            assertEquals(0, next.exitCode)
            assertEquals("NEXT_OK", next.stdout)
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

    @Test(timeout = 30_000) fun `delayed real drains retain both streams before successful acknowledgement`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val starts = File(fixture.directory, "drains-started")
            fixture.executable("dd", "printf 'started\\n' >> ${q(starts.path)}\nsleep 0.6\nexec /bin/dd \"\$@\"")
            for (headless in listOf(false, true)) {
                val effects = File(fixture.directory, "effects-$headless")
                val result = fixture.manager.exec("echo once >> ${q(effects.path)}; printf 'out\\n'; printf 'err\\n' >&2", 5_000, headless = headless)
                assertNull("headless=$headless stdout=${result.stdout} stderr=${result.stderr}", result.error)
                assertEquals(0, result.exitCode)
                assertEquals("out\n", result.stdout)
                assertEquals("err\n", result.stderr)
                assertEquals(listOf("once"), effects.readLines())
            }
            assertTrue("both real drain paths must have run", starts.readLines().size >= 4)
        }
    }

    @Test(timeout = 20_000) fun `deadline stops a sleeping command even if its drains are delayed`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val effects = File(fixture.directory, "timeout-effects")
            val late = File(fixture.directory, "timeout-late")
            fixture.executable("dd", "sleep 0.6\nexec /bin/dd \"\$@\"")
            val started = System.nanoTime()
            val result = fixture.manager.exec("echo once >> ${q(effects.path)}; printf BEFORE; printf WARN >&2; sleep 2; touch ${q(late.path)}", 1_000)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("command_timeout", result.error)
            assertNull(result.exitCode)
            assertEquals("BEFORE", result.stdout)
            assertEquals("WARN", result.stderr)
            assertEquals(listOf("once"), effects.readLines())
            assertTrue("timeout cleanup must remain bounded: $elapsedMs ms", elapsedMs < 4_500)
            delay(2_200)
            assertFalse("timed out command must not write a late marker", late.exists())
            assertEquals(listOf("once"), effects.readLines())
        }
    }

    @Test(timeout = 30_000) fun `stalled drains return incomplete error with actual exit and partial streams without replay`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val outStarted = File(fixture.directory, "out-second-read")
            val errStarted = File(fixture.directory, "err-second-read")
            fixture.executable("dd", """
                target=${'$'}(readlink /proc/${'$'}${'$'}/fd/1)
                case "${'$'}target" in
                  */stdout) first=${q(File(fixture.directory, "out-first").path)}; second=${q(outStarted.path)};;
                  */stderr) first=${q(File(fixture.directory, "err-first").path)}; second=${q(errStarted.path)};;
                  *) exec /bin/dd "${'$'}@";;
                esac
                if [ -f "${'$'}first" ]; then touch "${'$'}second"; sleep 10; else touch "${'$'}first"; fi
                exec /bin/dd "${'$'}@"
            """.trimIndent())
            val effects = File(fixture.directory, "stalled-effects")
            val result = fixture.manager.exec("""
                echo once >> ${q(effects.path)}
                printf out-prefix; printf err-prefix >&2
                while [ ! -f ${q(outStarted.path)} ] || [ ! -f ${q(errStarted.path)} ]; do sleep 0.01; done
                printf out-tail; printf err-tail >&2
                exit 7
            """.trimIndent(), 5_000)
            assertEquals("root_output_incomplete", result.error)
            assertEquals(7, result.exitCode)
            assertEquals("out-prefix", result.stdout)
            assertEquals("err-prefix", result.stderr)
            assertEquals(listOf("once"), effects.readLines())
            fixture.executable("dd", "exec /bin/dd \"\$@\"")
            val next = fixture.manager.exec("printf NEXT_OK", 3_000)
            assertNull(next.error)
            assertEquals("NEXT_OK", next.stdout)
            assertEquals(listOf("once"), effects.readLines())
            assertEquals(2, fixture.calls.readLines().size)
        }
    }

    @Test(timeout = 20_000) fun `foreground inherited output fds return incomplete without stopping background child`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val done = File(fixture.directory, "background-done")
            val release = File(fixture.directory, "background-release")
            val start = System.nanoTime()
            try {
                val result = fixture.manager.exec("(while [ ! -f ${q(release.path)} ]; do sleep 0.1; done; touch ${q(done.path)}) & printf foreground", 6_000)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertEquals("root_output_incomplete", result.error)
                assertEquals(0, result.exitCode)
                assertEquals("foreground", result.stdout)
                assertTrue("drain cleanup must remain bounded: $elapsedMs ms", elapsedMs < 5_000)
                assertFalse("background child must still await its release", done.exists())
                release.writeText("go")
                withTimeout(6_000) { while (!done.exists()) delay(20) }
                val next = fixture.manager.exec("printf NEXT_OK", 3_000)
                assertNull(next.error)
                assertEquals("NEXT_OK", next.stdout)
            } finally { release.writeText("go") }
        }
    }

    @Test(timeout = 15_000) fun `failed dd reports incomplete output even when drain exits`() = runBlocking {
        checkFailedCapture("dd")
    }

    @Test(timeout = 15_000) fun `failed cat reports incomplete output even when drain exits`() = runBlocking {
        checkFailedCapture("cat")
    }

    private suspend fun checkFailedCapture(failedTool: String) {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.manager.verifyRoot(prepareHeadless = true)
            val marker = File(fixture.directory, "failed-$failedTool")
            val fd = if (failedTool == "dd") "1" else "0"
            fixture.executable(failedTool, """
                target=${'$'}(readlink /proc/${'$'}${'$'}/fd/$fd)
                case "${'$'}target" in
                    */stdout|*/out.pipe)
                        /bin/$failedTool "${'$'}@"
                        touch ${q(marker.path)}
                        exit 91;;
                esac
                exec /bin/$failedTool "${'$'}@"
            """.trimIndent())
            val effects = File(fixture.directory, "effects-$failedTool")
            val result = fixture.manager.exec("echo once >> ${q(effects.path)}; printf output; printf warning >&2; exit 7", 5_000)
            assertTrue("the real $failedTool wrapper was reached", marker.exists())
            assertEquals("failedTool=$failedTool", "root_output_incomplete", result.error)
            assertEquals(7, result.exitCode)
            assertEquals("output", result.stdout)
            assertEquals("warning", result.stderr)
            assertEquals(listOf("once"), effects.readLines())
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

    @Test(timeout = 20_000)
    fun `foreground timeout confirms cleanup and keeps partial streams`() = runBlocking {
        checkTimeoutCleanup(headless = false)
    }

    @Test(timeout = 20_000)
    fun `headless timeout confirms cleanup and keeps partial streams`() = runBlocking {
        checkTimeoutCleanup(headless = true)
    }

    private suspend fun checkTimeoutCleanup(headless: Boolean) {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val executions = File(fixture.directory, "executions")
            val leaderStat = File(fixture.directory, "leader-stat")
            val lateMarker = File(fixture.directory, "late-marker")
            val control = ProcessBuilder("/bin/sleep", "30").start()
            try {
                val controlIdentity = requireNotNull(processIdentity(control.pid()))
                val started = System.nanoTime()
                val result = fixture.manager.exec(timeoutCommand(executions, leaderStat, lateMarker), 1_000, headless = headless)

                assertEquals("headless=$headless", "command_timeout", result.error)
                assertNull(result.exitCode)
                assertEquals("BEFORE\n", result.stdout)
                assertEquals("WARN\n", result.stderr)
                val leader = processIdentity(leaderStat.readText().trim())
                assertEquals("The recorded supervisor must own its process group", leader.pid, leader.pgid)
                assertEquals("The recorded supervisor must own its session", leader.pid, leader.sid)
                assertTrue("Control process must be outside the request group",
                    controlIdentity.pgid != leader.pgid || controlIdentity.sid != leader.sid)
                assertRequestStopped(leader)
                assertTrue("Unrelated control process was killed", isAlive(controlIdentity))

                val next = fixture.manager.exec("printf NEXT_OK", 3_000, headless = headless)
                assertNull(next.error)
                assertEquals(0, next.exitCode)
                assertEquals("NEXT_OK", next.stdout)
                assertEquals("A confirmed timeout must keep the persistent session", 2, fixture.calls.readLines().size)

                val remaining = 4_500 - (System.nanoTime() - started) / 1_000_000
                if (remaining > 0) delay(remaining)
                assertFalse("Code after the original sleep ran late", lateMarker.exists())
                assertRequestStopped(leader)
                assertTrue(isAlive(controlIdentity))
                assertEquals("The submitted command ran more than once", listOf("run"), executions.readLines())
            } finally { control.destroyForcibly() }
        }
    }

    @Test(timeout = 20_000)
    fun `foreground cancellation confirms cleanup without replay and keeps session reusable`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val executions = File(fixture.directory, "executions")
            val leaderStat = File(fixture.directory, "leader-stat")
            val lateMarker = File(fixture.directory, "late-marker")
            val control = ProcessBuilder("/bin/sleep", "30").start()
            try {
                val controlIdentity = requireNotNull(processIdentity(control.pid()))
                val started = System.nanoTime()
                val job = async {
                    fixture.manager.exec(timeoutCommand(executions, leaderStat, lateMarker), 30_000)
                }
                try {
                    // Cancel only after the marker has been written, not merely opened.
                    withTimeout(3_000) {
                        while (!executions.isFile || executions.readText() != "run\n") delay(10)
                    }
                } finally { job.cancelAndJoin() }

                val leader = processIdentity(leaderStat.readText().trim())
                assertEquals(leader.pid, leader.pgid)
                assertEquals(leader.pid, leader.sid)
                assertTrue("Cancellation was not acknowledged; the transport was quarantined", fixture.manager.isHeadlessReady())
                assertRequestStopped(leader)
                assertTrue(isAlive(controlIdentity))
                val next = fixture.manager.exec("printf NEXT_OK", 3_000)
                assertNull(next.error)
                assertEquals("NEXT_OK", next.stdout)
                assertEquals("A confirmed cancellation must keep the persistent session", 2, fixture.calls.readLines().size)

                val remaining = 4_500 - (System.nanoTime() - started) / 1_000_000
                if (remaining > 0) delay(remaining)
                assertFalse("Code after cancellation ran late", lateMarker.exists())
                assertRequestStopped(leader)
                assertTrue(isAlive(controlIdentity))
                assertEquals(listOf("run"), executions.readLines())
            } finally { control.destroyForcibly() }
        }
    }

    @Test(timeout = 15_000)
    fun `foreground completion after the app deadline cannot become a successful exit`() = runBlocking {
        Fixture(folder.newFolder(), brokerShell).use { fixture ->
            fixture.executable("sleep", "if [ \"\$1\" = 0.05 ]; then exec /bin/sleep 0.25; fi\nexec /bin/sleep \"\$@\"")
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("sleep 0.15; printf late; exit 7", 100)
            assertEquals("command_timeout", result.error)
            assertNull(result.exitCode)
            assertEquals("NEXT_OK", fixture.manager.exec("printf NEXT_OK", 3_000).stdout)
            assertEquals(2, fixture.calls.readLines().size)
        }
    }

    private suspend fun awaitFile(file: File) = withTimeout(3_000) { while (!file.isFile) delay(10) }

    private fun timeoutCommand(executions: File, leaderStat: File, lateMarker: File): String =
        "IFS= read -r leader_stat < /proc/\$PPID/stat; " +
            "printf '%s\\n' \"\$leader_stat\" > ${q(leaderStat.path)}; " +
            "printf 'run\\n' >> ${q(executions.path)}; " +
            "printf 'BEFORE\\n'; printf 'WARN\\n' >&2; sleep 4; " +
            "printf 'AFTER\\n'; printf late >> ${q(lateMarker.path)}"

    private data class ProcessIdentity(
        val pid: Long,
        val state: String,
        val pgid: Long,
        val sid: Long,
        val startTime: Long,
    )

    private fun processIdentity(text: String): ProcessIdentity {
        val fields = text.substringAfterLast(") ").split(' ')
        require(fields.size > 19) { "Invalid proc stat: $text" }
        return ProcessIdentity(
            pid = text.substringBefore(' ').toLong(),
            state = fields[0],
            pgid = fields[2].toLong(),
            sid = fields[3].toLong(),
            startTime = fields[19].toLong(),
        )
    }

    private fun processIdentity(pid: Long): ProcessIdentity? =
        runCatching { processIdentity(File("/proc/$pid/stat").readText().trim()) }.getOrNull()

    private fun isAlive(identity: ProcessIdentity): Boolean =
        processIdentity(identity.pid)?.let { it.startTime == identity.startTime && it.state != "Z" } == true

    private fun liveGroupMembers(leader: ProcessIdentity): List<ProcessIdentity> =
        File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            entry.name.toLongOrNull()?.let { pid -> processIdentity(pid) }
        }.filter { it.pgid == leader.pgid && it.sid == leader.sid && it.state != "Z" }

    private fun assertRequestStopped(leader: ProcessIdentity) {
        assertFalse("Supervisor PID/starttime is still live: $leader", isAlive(leader))
        assertEquals("Live processes remain in the request PGID/SID", emptyList<ProcessIdentity>(), liveGroupMembers(leader))
    }


    @Test(timeout = 20_000) fun `completed foreground launch preserves detached children like the one shot transport`() = runBlocking {
        // Cover every decimal-width/range boundary in the foreground release whitelist.
        for (fallback in listOf(true, false)) for (code in listOf(0, 7, 9, 10, 99, 100, 199, 200, 249, 250, 255)) {
            Fixture(folder.newFolder(), brokerShell).use { fixture ->
                if (fallback) fixture.executable("mkfifo", "exit 1")
                val pid = File(fixture.directory, "detached-pid")
                try {
                    val result = fixture.manager.exec("sleep 30 >/dev/null 2>&1 & echo \$! > ${q(pid.path)}; printf launched; exit $code", 3_000)
                    if (fallback) assertEquals("root_output_incomplete", result.error) else assertNull(result.error)
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
            val argvAndMask = result.stdout.trimEnd().lines()
            assertEquals(2, argvAndMask.size)
            assertEquals("sh", argvAndMask[0])
            // mksh prints 022, while dash/bash print 0022; compare the actual permission bits.
            assertEquals("022".toInt(8), argvAndMask[1].toInt(8))
            assertEquals(0, fixture.manager.exec("umask 0077; cd /; exit 0", 3_000).exitCode)
            assertEquals("022".toInt(8), fixture.manager.exec("umask", 3_000).stdout.trim().toInt(8))
            assertEquals("077".toInt(8), fixture.manager.exec("umask", 3_000, headless = true).stdout.trim().toInt(8))
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
            su.writeText("#!/bin/sh\nprintf 'called\\n' >> ${q(calls.path)}\nPATH=${q(bin.path)}:\"\$PATH\"\nexport PATH\ncase \"\$2\" in *RIKKA_ROOT_PROBE_*) ;; *)\n$beforeBroker\n:\n;; esac\nexec ${q(transportShell)} -c \"\$2\"\n")
            check(su.setExecutable(true))
        }

        override fun close() = manager.close()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "broker shell={0}")
        fun cases(): List<Array<Any>> = buildList {
            add(arrayOf("/bin/sh"))
            add(arrayOf("/bin/bash"))
            // Opt in to a real mksh binary; no Android or shell behaviour is emulated.
            System.getenv("RIKKA_TEST_MKSH")?.let { path ->
                val executable = File(path)
                if (executable.isFile && executable.canExecute()) add(arrayOf(executable.absolutePath))
            }
        }

        private fun q(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
