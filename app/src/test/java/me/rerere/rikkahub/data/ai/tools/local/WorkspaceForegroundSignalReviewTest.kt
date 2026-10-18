package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.os.Process as AndroidProcess
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.rerere.workspace.readResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Android signal boundary is mapped to a real host QUIT; actual cleanup/reaping stays real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WorkspaceForegroundSignalReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signals = mutableListOf<Pair<Int, Int>>()

    @Before fun setUp() {
        mockkStatic(AndroidProcess::class)
        every { AndroidProcess.sendSignal(any(), any()) } answers {
            val pid = firstArg<Int>()
            val signal = secondArg<Int>()
            signals += pid to signal
            assertEquals(AndroidProcess.SIGNAL_QUIT, signal)
            val fields = File("/proc/$pid/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))
            assertEquals("A platform signal must target our live direct child", selfPid().toLong(), fields[1].toLong())
            assertTrue("Record a valid kernel start time", fields[19].toLong() > 0)
            // Only this platform effect is replaced on the desktop JVM; no fake cleanup state.
            val sender = ProcessBuilder("/bin/kill", "-QUIT", pid.toString()).start()
            assertTrue(sender.waitFor(1, TimeUnit.SECONDS))
            assertEquals(0, sender.exitValue())
            Unit
        }
    }

    @After fun tearDown() { unmockkStatic(AndroidProcess::class) }

    @Test(timeout = 10_000) fun `review 12 timeout sends platform QUIT to the verified owned pid`() {
        withTracer { process, pid, childPid, quit ->
            val result = process.readResult(100)
            assertTrue(result.timedOut)
            assertEquals("No helper shell may replace the direct Android signal", listOf(pid to AndroidProcess.SIGNAL_QUIT), signals)
            assertTrue("QUIT must reach the tracer while it can still reap descendants", quit.exists())
            await { !alive(childPid) }
            assertFalse(alive(childPid))
            assertFalse(process.isAlive)
        }
    }

    @Test(timeout = 10_000) fun `review 12 interruption sends platform QUIT and preserves cancellation`() {
        withTracer { process, pid, childPid, quit ->
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { process.readResult(30_000) }
                catch (actual: Throwable) { failure.set(actual) }
            }
            worker.start()
            try {
                await { worker.state in setOf(Thread.State.TIMED_WAITING, Thread.State.WAITING) }
                worker.interrupt()
                worker.join(4_000)
                assertFalse(worker.isAlive)
                assertTrue("Caller cancellation must propagate after cleanup", failure.get() is InterruptedException)
                assertEquals(listOf(pid to AndroidProcess.SIGNAL_QUIT), signals)
                assertTrue(quit.exists())
                await { !alive(childPid) }
                assertFalse(alive(childPid))
            } finally { worker.interrupt(); worker.join(1_000) }
        }
    }

    @Test(timeout = 5_000) fun `review 12 unverifiable identity never signals another pid`() {
        val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        try {
            // Process.pid() is unsupported on this wrapper, and its claimed fallback pid is not a child.
            val reported = ReportedProcess(process, selfPid())
            val result = reported.readResult(50)
            assertTrue(result.timedOut)
            assertTrue("An identity with the wrong PPID must never reach sendSignal", signals.isEmpty())
            assertFalse("The owned handle still must be terminated", process.isAlive)
        } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    }

    @Test(timeout = 5_000) fun `review 12 an already finished process is not signalled`() {
        val process = ProcessBuilder("/bin/sh", "-c", "printf complete").start()
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
        val result = process.readResult(100)
        assertFalse(result.timedOut)
        assertEquals("complete", result.stdout)
        assertTrue(signals.isEmpty())
    }

    private fun withTracer(block: (Process, Int, Long, File) -> Unit) {
        val directory = temporary.newFolder()
        val childFile = File(directory, "child-pid")
        val quit = File(directory, "quit-seen")
        val script = """
            trap 'printf quit > ${quote(quit.path)}; kill "${'$'}child"; wait "${'$'}child"; exit 0' QUIT
            sleep 30 &
            child=${'$'}!
            printf '%s' "${'$'}child" > ${quote(childFile.path)}
            wait "${'$'}child"
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        var childPid: Long? = null
        try {
            await { childFile.exists() && childFile.length() > 0 }
            val child = childFile.readText().trim().toLong()
            childPid = child
            val pid = (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()
            block(process, pid, child, quit)
        } finally {
            childPid?.let { child ->
                if (alive(child)) ProcessBuilder("/bin/kill", "-KILL", child.toString()).start().waitFor(1, TimeUnit.SECONDS)
            }
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private class ReportedProcess(private val actual: Process, private val reportedPid: Int) : Process() {
        override fun getOutputStream(): OutputStream = actual.outputStream
        override fun getInputStream(): InputStream = actual.inputStream
        override fun getErrorStream(): InputStream = actual.errorStream
        override fun waitFor(): Int = actual.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = actual.waitFor(timeout, unit)
        override fun exitValue(): Int = actual.exitValue()
        override fun destroy() = actual.destroy()
        override fun destroyForcibly(): Process { actual.destroyForcibly(); return this }
        override fun isAlive(): Boolean = actual.isAlive
        override fun toString(): String = "Process[pid=$reportedPid]"
    }

    private fun selfPid(): Int = File("/proc/self/stat").readText().substringBefore(' ').toInt()
    private fun alive(pid: Long): Boolean = runCatching {
        !File("/proc/$pid/stat").readText().substringAfterLast(") ").startsWith("Z")
    }.getOrDefault(false)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Fixture condition did not arrive", predicate())
    }
    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
