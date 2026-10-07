package me.rerere.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceBackgroundProcessesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(command: String, root: String = "test") = makeContext(temporary.newFolder(), command, root)
    private fun start(registry: WorkspaceBackgroundProcesses, context: WorkspaceShellContext) =
        registry.start(context) { HostShellRunner().startBackground(context, it) }

    @Test fun `output tails are bounded independently and record discarded characters`() {
        val buffer = BackgroundTail(5)
        buffer.append("abcdef"); buffer.append("ghi")
        assertEquals("efghi" to 4L, buffer.snapshot())
    }

    @Test(timeout = 15_000) fun `normal exit retains both streams and real exit code`() {
        WorkspaceBackgroundProcesses().use { registry ->
            val status = start(registry, context("printf hello; printf error >&2; exit 7"))
            await { registry.status("test", status.id)?.running == false }
            val done = registry.status("test", status.id)!!
            assertEquals(7, done.exitCode)
            assertEquals("hello", done.stdout)
            assertEquals("error", done.stderr)
            assertTrue(registry.list("other").isEmpty())
            assertNull(registry.status("other", status.id))
        }
    }

    @Test(timeout = 15_000) fun `stop kills child processes and keeps output record without touching another workspace`() {
        WorkspaceBackgroundProcesses().use { registry ->
            val task = start(registry, context("sleep 60 & echo \$!; wait"))
            await { registry.status("test", task.id)!!.stdout.trim().toLongOrNull() != null }
            val child = registry.status("test", task.id)!!.stdout.trim().toLong()
            assertFalse(registry.stop("other", task.id))
            assertTrue(running(child))
            assertTrue(registry.stop("test", task.id))
            await { !running(child) }
            assertFalse(registry.status("test", task.id)!!.running)
            assertNotNull(registry.status("test", task.id)!!.exitCode)
        }
    }

    @Test(timeout = 20_000) fun `admission cap is checked before a rejected command can launch and stopAll drains the group`() {
        WorkspaceBackgroundProcesses().use { registry ->
            repeat(MAX_BG_PROCESSES) { start(registry, context("sleep 60")) }
            var launched = false
            assertThrows(IllegalStateException::class.java) {
                registry.start(context("touch unwanted")) { launched = true; error("must never launch") }
            }
            assertFalse(launched)
            registry.stopAll("test")
            assertTrue(registry.list("test").all { !it.running })
        }
    }

    @Test(timeout = 15_000) fun `owner JVM exit ends supervised processes even without registry cleanup`() {
        val owner = launchOwner()
        try {
            assertTrue(owner.waitFor(8, TimeUnit.SECONDS))
            val output = owner.inputStream.bufferedReader().readText().trim()
            assertEquals(owner.errorStream.bufferedReader().readText(), 0, owner.exitValue())
            val child = output.toLong()
            await { !running(child) }
        } finally { if (owner.isAlive) owner.destroyForcibly() }
    }

    @Test(timeout = 15_000) fun `killing owner JVM bypasses all shutdown hooks but still kills its supervised children`() {
        val owner = launchOwner(hold = true)
        try {
            val line = CompletableFuture.supplyAsync { owner.inputStream.bufferedReader().readLine() }.get(8, TimeUnit.SECONDS)
            val child = line.toLong()
            assertTrue(owner.isAlive)
            assertTrue(running(child))
            owner.destroyForcibly()
            assertTrue(owner.waitFor(3, TimeUnit.SECONDS))
            await { !running(child) }
        } finally { if (owner.isAlive) owner.destroyForcibly() }
    }

    private fun launchOwner(hold: Boolean = false): Process {
        val classes = listOf(javaClass, WorkspaceBackgroundProcesses::class.java, HostShellRunner::class.java, Unit::class.java,
            org.junit.Test::class.java, org.hamcrest.Matcher::class.java)
        val classpath = classes.map { File(it.protectionDomain!!.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val args = listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath, javaClass.name,
            temporary.newFolder().path) + if (hold) listOf("hold") else emptyList()
        return ProcessBuilder(args).start()
    }

    companion object {
        private fun makeContext(dir: File, command: String, root: String): WorkspaceShellContext {
            val temp = File(dir, "tmp").apply { mkdirs() }
            return WorkspaceShellContext(root, command, "", dir, dir, temp, dir, 30_000)
        }
        private fun running(pid: Long): Boolean = runCatching {
            File("/proc/$pid/stat").readText().substringAfterLast(") ").first() != 'Z'
        }.getOrDefault(false)
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("condition timed out", condition())
        }
        @JvmStatic fun main(args: Array<String>) {
            val ctx = makeContext(File(args.first()), "sleep 60 & echo \$!; wait", "owner")
            val registry = WorkspaceBackgroundProcesses()
            val task = registry.start(ctx) { HostShellRunner().startBackground(ctx, it) }
            await { registry.status("owner", task.id)!!.stdout.trim().toLongOrNull() != null }
            println(registry.status("owner", task.id)!!.stdout.trim())
            System.out.flush()
            if (args.size > 1) System.`in`.read()
            // Deliberately omit close, simulating an app process disappearing.
        }
    }
}
