package me.rerere.rikkahub.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RootProcessRunnerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10_000) fun `unmatched process identity cannot confirm cleanup or signal another process`() {
        val unrelated = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val stat = File("/proc/${unrelated.pid()}/stat").readText().substringAfterLast(") ").split(' ')
            assertNotEquals("The unrelated process must not lead its inherited process group", unrelated.pid().toString(), stat[2])
            RootCommandSession("/bin/sh", temp.root).use { session ->
                val identity = temp.root.listFiles()!!.single { it.name.endsWith(".pid") }
                identity.writeText("${unrelated.pid()} ${stat[19]}\n")
                session.cancel()
                assertNotNull("An identity mismatch cannot prove cleanup", session.cleanupError)
                assertTrue("An unrelated process must remain alive", unrelated.isAlive)
            }
        } finally {
            unrelated.destroyForcibly()
            unrelated.waitFor()
        }
    }

    @Test(timeout = 10_000) fun `successful kill return does not confirm cleanup while leader is still alive`() {
        val target = ProcessBuilder("/usr/bin/setsid", "/bin/sleep", "30").start()
        try {
            val pid = target.pid().toString()
            val deadline = System.nanoTime() + 2_000_000_000L
            var stat = emptyList<String>()
            while (System.nanoTime() < deadline) {
                stat = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')
                if (stat[2] == pid && stat[3] == pid) break
                Thread.sleep(10)
            }
            assertEquals(pid, stat[2])
            assertEquals(pid, stat[3])
            val called = File(temp.root, "kill-called")
            val su = File(temp.root, "fake-cleanup-su").apply {
                writeText("#!/bin/sh\nkill() { printf called > '${called.path}'; return 0; }\neval \"\$2\"\n")
                assertTrue(setExecutable(true))
            }
            RootCommandSession(su.path, temp.root).use { session ->
                val identity = temp.root.listFiles()!!.single { it.name.endsWith(".pid") }
                identity.writeText("$pid ${stat[19]}\n")
                session.cancel()
                assertTrue("The test kill was invoked", called.isFile)
                assertTrue("The test kill intentionally leaves the leader alive", target.isAlive)
                assertNotNull("A live leader cannot be reported as stopped", session.cleanupError)
            }
        } finally {
            target.destroyForcibly()
            target.waitFor()
        }
    }

    @Test(timeout = 10_000) fun `unconfirmed empty output never reports success or repeats a completed command`() {
        val effects = File(temp.root, "unconfirmed-runs")
        val result = RootProcessRunner.run(listOf("/bin/sh", "-c",
            "printf 'run\\n' >> '${effects.path}'; exit 0"), 5_000)
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals(listOf("run"), effects.readLines())
        assertEquals("root_output_incomplete", result.error)
    }

    @Test(timeout = 10_000) fun `interrupted stdout reader reports incomplete output without replay`() {
        val ready = File(temp.root, "reader-ready")
        val gate = File(temp.root, "reader-gate")
        val effects = File(temp.root, "runs")
        val before = Thread.getAllStackTraces().keys.map { it.id }.toSet()
        val observed = AtomicReference<RootProcessResult>()
        val worker = Thread {
            observed.set(RootProcessRunner.run(listOf("/bin/sh", "-c", """
                printf 'run\n' >> '${effects.path}'
                : > '${ready.path}'
                while [ ! -f '${gate.path}' ]; do sleep 0.01; done
                trap '' PIPE
                printf expected 2>/dev/null || :
                printf warning >&2
                exit 0
            """.trimIndent()), 5_000, requireConfirmedOutput = false))
        }
        try {
            worker.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            var reader: Thread? = null
            while (System.nanoTime() < deadline && (reader == null || !ready.exists())) {
                reader = Thread.getAllStackTraces().keys.firstOrNull {
                    it.id !in before && it.name == "root-stdout" && it.isAlive
                }
                Thread.sleep(5)
            }
            assertTrue("command started", ready.exists())
            val target = requireNotNull(reader) { "stdout reader not started" }
            target.interrupt()
            target.join(1_000)
            assertFalse("interrupted reader stopped", target.isAlive)
            gate.writeText("go")
            worker.join(5_000)
            assertFalse("runner completed", worker.isAlive)
            val result = requireNotNull(observed.get())
            assertEquals(0, result.exitCode)
            assertEquals("root_output_incomplete", result.error)
            assertEquals("warning", result.stderr)
            assertEquals("run\n", effects.readText())
        } finally {
            gate.writeText("go")
            worker.join(5_000)
        }
    }

    @Test(timeout = 10_000) fun `technical probe can use exact expected output when readers succeed`() {
        val result = RootProcessRunner.run(listOf("/bin/sh", "-c", "printf '0\\n'; sleep 0.1"),
            5_000, requireConfirmedOutput = false)
        assertEquals(0, result.exitCode)
        assertNull(result.error)
        assertEquals("0\n", result.stdout)
    }

    @Test(timeout = 10_000) fun `technical capture does not suppress reader IOException`() {
        val effects = File(temp.root, "reader-failure-runs")
        val process = ProcessBuilder("/bin/sh", "-c",
            "printf 'run\\n' >> '${effects.path}'; printf warning >&2; sleep 0.1; exit 0").start()
        val faulty = object : InputStream() {
            override fun available(): Int = throw IOException("test read failure")
            override fun read(): Int = throw IOException("test read failure")
        }
        val result = RootProcessRunner.runStartedProcess(
            withStdout(process, faulty), 5_000, 8_000, 2_000, requireConfirmedOutput = false)
        assertEquals(0, result.exitCode)
        assertEquals("root_output_incomplete", result.error)
        assertEquals("warning", result.stderr)
        assertEquals(listOf("run"), effects.readLines())
    }

    @Test(timeout = 10_000) fun `stalled reader reports incomplete and preserves completed result`() {
        val effects = File(temp.root, "stalled-reader-runs")
        val process = ProcessBuilder("/bin/sh", "-c",
            "printf 'run\\n' >> '${effects.path}'; printf warning >&2; sleep 0.1; exit 0").start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val stalled = object : FilterInputStream(process.inputStream) {
            override fun available(): Int {
                reader.set(Thread.currentThread())
                entered.countDown()
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { /* simulate uninterruptible IO */ }
                }
                return super.available()
            }
        }
        try {
            val result = RootProcessRunner.runStartedProcess(
                withStdout(process, stalled), 5_000, 8_000, 2_000, requireConfirmedOutput = false)
            assertEquals("reader entered IO", 0L, entered.count)
            assertEquals(0, result.exitCode)
            assertEquals("root_output_incomplete", result.error)
            assertEquals("warning", result.stderr)
            assertEquals(listOf("run"), effects.readLines())
        } finally {
            release.countDown()
            process.destroyForcibly()
            reader.get()?.join(2_000)
            assertFalse("released test reader must stop", reader.get()?.isAlive == true)
        }
    }

    @Test(timeout = 10_000) fun `timeout retains timeout status and warns when reader did not finish`() {
        val process = ProcessBuilder("/bin/sh", "-c", "printf warning >&2; exec sleep 30").start()
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val stalled = object : FilterInputStream(process.inputStream) {
            override fun available(): Int {
                reader.set(Thread.currentThread())
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { /* simulate uninterruptible IO */ }
                }
                return super.available()
            }
        }
        try {
            val result = RootProcessRunner.runStartedProcess(
                withStdout(process, stalled), 500, 8_000, 2_000, requireConfirmedOutput = false)
            assertNull(result.exitCode)
            assertEquals("command_timeout", result.error)
            assertTrue(result.reason.orEmpty().contains("Вывод также мог быть получен не полностью"))
        } finally {
            release.countDown()
            process.destroyForcibly()
            reader.get()?.join(2_000)
            assertFalse("released test reader must stop", reader.get()?.isAlive == true)
        }
    }

    private fun withStdout(process: Process, stdout: InputStream): Process = object : Process() {
        override fun getOutputStream(): OutputStream = process.outputStream
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = process.errorStream
        override fun waitFor(): Int = process.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
        override fun exitValue(): Int = process.exitValue()
        override fun destroy() = process.destroy()
        override fun destroyForcibly(): Process { process.destroyForcibly(); return this }
        override fun isAlive(): Boolean = process.isAlive
    }

    @Test fun `captures both streams and a nonzero exit without replay`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf out; printf err >&2; exit 7"), 2_000)
        assertEquals(7, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
    }

    @Test fun `stdin is closed so a reader immediately finishes`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "cat; printf done"), 2_000)
        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout)
    }

    @Test fun `bounds each stream and marks discarded bytes`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf abcdef; printf 123456 >&2"), 2_000, 3, 2)
        assertTrue(result.stdout.startsWith("abc"))
        assertTrue(result.stdout.contains("ещё 3 байт"))
        assertTrue(result.stderr.startsWith("12"))
        assertTrue(result.stderr.contains("ещё 4 байт"))
    }

    @Test fun `multibyte output is truncated on a UTF8 boundary`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf '😀😀'"), 2_000, 5, 2)
        assertTrue(result.stdout.startsWith("😀\n"))
        assertFalse(result.stdout.contains("\uFFFD"))
        assertTrue(result.stdout.contains("ещё 4 байт"))
    }

    @Test fun `timeout kills the process and retains partial output`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf started; exec sleep 30"), 100)
        assertEquals("command_timeout", result.error)
        assertEquals("started", result.stdout)
        assertTrue(result.reason.orEmpty().startsWith("Время root-команды истекло. Проверьте, что успело выполниться, перед новым вызовом; команда автоматически не повторяется."))
    }

    @Test fun `process launch failure is a structured error`() {
        val result = RootProcessRunner.run(listOf("/nonexistent/root-port-executable"), 1_000)
        assertEquals("exec_failed", result.error)
        assertNull(result.exitCode)
        assertEquals("Не удалось запустить root-команду. Проверьте Root в настройках приложения.", result.reason)
    }

    @Test fun `coroutine cancellation terminates the launched process`() = runBlocking {
        val pid = File(temp.root, "pid")
        val worker = async(Dispatchers.IO) {
            runInterruptible {
                RootProcessRunner.run(listOf("sh", "-c", "echo \$\$ > '${pid.path}'; exec sleep 30"), 30_000)
            }
        }
        repeat(200) { if (!pid.exists()) delay(10) }
        assertTrue("process must start before cancellation", pid.exists())
        val processId = pid.readText().trim().toLong()
        worker.cancel()
        try { worker.await() } catch (_: CancellationException) {}
        assertFalse("cancelled process must be dead", ProcessHandle.of(processId).map { it.isAlive }.orElse(false))
    }

    @Test fun `drains output beyond pipe capacity without deadlock`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "head -c 131072 /dev/zero; head -c 131072 /dev/zero >&2"), 3_000, 64, 32)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("ещё 131008 байт"))
        assertTrue(result.stderr.contains("ещё 131040 байт"))
    }

    @Test fun `owned process truncation marker is Russian while preserving exact omitted bytes`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf abcdef; printf 123456 >&2"), 2_000, 3, 2)
        assertTrue(result.stdout.startsWith("abc\n"))
        assertTrue(result.stdout, result.stdout.contains("ещё 3 байт"))
        assertTrue(result.stderr.startsWith("12\n"))
        assertTrue(result.stderr, result.stderr.contains("ещё 4 байт"))
        assertFalse(result.stdout.contains("bytes more"))
    }
}
