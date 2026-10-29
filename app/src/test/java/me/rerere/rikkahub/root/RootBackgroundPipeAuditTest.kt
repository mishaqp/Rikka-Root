package me.rerere.rikkahub.root

import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootBackgroundPipeAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 5_000) fun `reader stops after a finite final drain when an exited process pipe is always readable`() {
        val process = mockk<Process>()
        every { process.isAlive } returns false
        val closed = AtomicBoolean(false)
        val stream = object : InputStream() {
            override fun available(): Int = 8192
            override fun read(): Int = 'x'.code
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(buffer, offset, offset + length, 'x'.code.toByte())
                return length
            }
            override fun close() { closed.set(true) }
        }
        val output = BoundedOutputStream(32)
        val error = AtomicReference<Throwable?>()
        val drain = RootProcessRunner::class.java.getDeclaredMethod(
            "drainWhileRunning", Process::class.java, InputStream::class.java, BoundedOutputStream::class.java,
        ).apply { isAccessible = true }
        val worker = Thread {
            try { drain.invoke(RootProcessRunner, process, stream, output) }
            catch (actual: Throwable) { error.set(actual) }
        }.apply { isDaemon = true }
        try {
            worker.start()
            worker.join(500)
            assertFalse("An exited process requires a finite drain even if available never reaches zero", worker.isAlive)
            assertNull("Finite cleanup must not fail", error.get())
            assertTrue("A finished reader must close its stream", closed.get())
            assertTrue("The final available bytes must still be captured", output.snapshot().startsWith("x"))
        } finally {
            worker.interrupt()
            worker.join(500)
        }
    }

    @Test(timeout = 15_000) fun `background descendants retaining stdio cannot accumulate root reader threads`() {
        val originalReaders = readers()
        val childPids = mutableListOf<Long>()
        try {
            repeat(3) { index ->
                val pidFile = File(temporary.root, "child-$index")
                val result = RootProcessRunner.run(listOf("/bin/sh", "-c",
                    "sleep 30 & printf '%s' \"\$!\" > ${quote(pidFile.path)}; printf started; sleep 0.1; exit 0"), 2_000)
                // Record ownership before assertions so a RED failure cannot leak a child.
                childPids += pidFile.readText().trim().toLong()
                assertEquals(0, result.exitCode)
                assertEquals("root_output_incomplete", result.error)
                assertEquals("started", result.stdout)
                val deadline = System.nanoTime() + 500_000_000L
                while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("Readers must stop after return even when a descendant owns the pipes: ${readers() - originalReaders}",
                    (readers() - originalReaders).isEmpty())
            }
        } finally {
            childPids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            val deadline = System.nanoTime() + 2_000_000_000L
            while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        }
    }

    @Test(timeout = 8_000) fun `background stderr alone cannot keep a reader alive after return`() {
        val originalReaders = readers()
        val pidFile = File(temporary.root, "stderr-child")
        var pid: Long? = null
        try {
            val result = RootProcessRunner.run(listOf("/bin/sh", "-c",
                "sleep 30 >/dev/null & printf '%s' \"\$!\" > ${quote(pidFile.path)}; printf warning >&2; sleep 0.1"), 2_000)
            pid = pidFile.readText().trim().toLong()
            assertEquals(0, result.exitCode)
            assertEquals("root_output_incomplete", result.error)
            assertEquals("warning", result.stderr)
            val deadline = System.nanoTime() + 500_000_000L
            while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("stderr reader must have a bounded lifetime", (readers() - originalReaders).isEmpty())
        } finally {
            pid?.let { child -> ProcessHandle.of(child).ifPresent { it.destroyForcibly() } }
            val deadline = System.nanoTime() + 2_000_000_000L
            while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        }
    }

    @Test(timeout = 10_000) fun `continuous background output cannot extend readers beyond the owned shell lifetime`() {
        val originalReaders = readers()
        val pidFile = File(temporary.root, "continuous-writers")
        try {
            // Two native writers per pipe keep it replenished while the readers drain it.
            // The owned shell exits normally; the writers do not exit or wait for a timer.
            val command = """
                pids=''
                yes stdout-a & pids="${'$'}pids ${'$'}!"
                yes stdout-b & pids="${'$'}pids ${'$'}!"
                yes stderr-a >&2 & pids="${'$'}pids ${'$'}!"
                yes stderr-b >&2 & pids="${'$'}pids ${'$'}!"
                printf '%s' "${'$'}pids" > ${quote(pidFile.path)}
                sleep 0.2
                exit 0
            """.trimIndent()
            val result = RootProcessRunner.run(listOf("/bin/sh", "-c", command), 2_000, 64, 64)
            assertEquals(0, result.exitCode)
            assertEquals("root_output_incomplete", result.error)
            assertTrue("stdout must retain bytes read before shell exit", result.stdout.isNotEmpty())
            assertTrue("stderr must retain bytes read before shell exit", result.stderr.isNotEmpty())
            val deadline = System.nanoTime() + 500_000_000L
            while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("A continuously replenished pipe must not keep readers alive: ${readers() - originalReaders}",
                (readers() - originalReaders).isEmpty())
        } finally {
            if (pidFile.exists()) {
                pidFile.readText().trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull).forEach { pid ->
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                }
            }
            val deadline = System.nanoTime() + 2_000_000_000L
            while ((readers() - originalReaders).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        }
    }

    private fun readers(): Set<Long> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name in setOf("root-stdout", "root-stderr") }.map { it.id }.toSet()

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
