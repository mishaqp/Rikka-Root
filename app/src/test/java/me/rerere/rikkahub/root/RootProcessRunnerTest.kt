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

class RootProcessRunnerTest {
    @get:Rule val temp = TemporaryFolder()

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
        assertTrue(result.stdout.contains("3 bytes more"))
        assertTrue(result.stderr.startsWith("12"))
        assertTrue(result.stderr.contains("4 bytes more"))
    }

    @Test fun `multibyte output is truncated on a UTF8 boundary`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf '😀😀'"), 2_000, 5, 2)
        assertTrue(result.stdout.startsWith("😀\n"))
        assertFalse(result.stdout.contains("\uFFFD"))
        assertTrue(result.stdout.contains("4 bytes more"))
    }

    @Test fun `timeout kills the process and retains partial output`() {
        val result = RootProcessRunner.run(listOf("sh", "-c", "printf started; exec sleep 30"), 100)
        assertEquals("command_timeout", result.error)
        assertEquals("started", result.stdout)
    }

    @Test fun `process launch failure is a structured error`() {
        val result = RootProcessRunner.run(listOf("/nonexistent/root-port-executable"), 1_000)
        assertEquals("exec_failed", result.error)
        assertNull(result.exitCode)
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
        assertTrue(result.stdout.contains("131008 bytes more"))
        assertTrue(result.stderr.contains("131040 bytes more"))
    }
}
