package me.rerere.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises foreground readResult, not the separate managed-background supervisor. */
class WorkspaceForegroundCancellationAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 10_000) fun `timeout gives the tracer QUIT before forcing termination`() {
        fixture { process, childPid, quit ->
            val result = process.readResult(150, sendQuit = ::sendHostQuit)
            assertTrue(result.timedOut)
            assertTrue("A tracer needs QUIT to reap its tracees before it is killed", quit.exists())
            await { !alive(childPid.readText().trim().toLong()) }
            assertFalse("Tracee must not survive timeout", alive(childPid.readText().trim().toLong()))
            assertFalse(process.isAlive)
        }
    }

    @Test(timeout = 10_000) fun `thread interruption propagates after graceful tracer cleanup`() {
        fixture { process, childPid, quit ->
            val error = AtomicReference<Throwable?>()
            val worker = Thread {
                try { process.readResult(30_000, sendQuit = ::sendHostQuit) }
                catch (actual: Throwable) { error.set(actual) }
            }
            worker.start()
            try {
                await { worker.state in setOf(Thread.State.TIMED_WAITING, Thread.State.WAITING) }
                worker.interrupt()
                worker.join(4_000)
                assertFalse("Cancellation cleanup must be bounded", worker.isAlive)
                assertTrue("InterruptedException must propagate", error.get() is InterruptedException)
                assertTrue("Cancellation must let the tracer reap descendants", quit.exists())
                await { !alive(childPid.readText().trim().toLong()) }
                assertFalse(alive(childPid.readText().trim().toLong()))
            } finally {
                worker.interrupt()
                process.destroyForcibly()
                worker.join(1_000)
            }
        }
    }

    @Test(timeout = 8_000) fun `a process ignoring graceful signals still has a bounded force fallback`() {
        val started = File(temporary.root, "ignore-ready")
        val process = ProcessBuilder("/bin/sh", "-c",
            "trap '' QUIT TERM; printf ready > ${quote(started.path)}; exec sleep 30").start()
        try {
            await { started.exists() }
            val start = System.nanoTime()
            val result = process.readResult(100, sendQuit = ::sendHostQuit)
            assertTrue(result.timedOut)
            assertTrue("Forced fallback must finish in less than four seconds", (System.nanoTime() - start) / 1_000_000 < 4_000)
            assertTrue(process.waitFor(1, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    }

    private fun fixture(block: (Process, File, File) -> Unit) {
        val directory = temporary.newFolder()
        val childPid = File(directory, "child-pid")
        val quit = File(directory, "quit-seen")
        // Like a tracer, the owner can reap its descendant only while still alive.
        val script = """
            trap 'printf quit > ${quote(quit.path)}; kill "${'$'}child"; wait "${'$'}child"; exit 0' QUIT
            sleep 30 &
            child=${'$'}!
            printf '%s' "${'$'}child" > ${quote(childPid.path)}
            wait "${'$'}child"
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        try {
            await { childPid.exists() && childPid.length() > 0 }
            block(process, childPid, quit)
        } finally {
            childPid.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    // Android's sendSignal is a stub in these plain JVM tests; preserve the real QUIT/reap contract.
    private fun sendHostQuit(pid: Int) {
        val sender = ProcessBuilder("/bin/kill", "-QUIT", pid.toString()).start()
        assertTrue("The host signal transport must complete", sender.waitFor(1, TimeUnit.SECONDS))
        assertEquals("The actual QUIT must be delivered", 0, sender.exitValue())
    }

    private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { process ->
        process.isAlive && !runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").startsWith("Z") }.getOrDefault(false)
    }.orElse(false)

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (!predicate() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Fixture condition was not reached before deadline", predicate())
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
