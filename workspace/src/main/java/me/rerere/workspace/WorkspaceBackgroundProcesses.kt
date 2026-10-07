package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

const val MAX_BG_OUTPUT_CHARS = 32 * 1024
const val MAX_BG_PROCESSES = 5
private const val MAX_BG_RECORDS = 32

data class BackgroundStatus(
    val id: String,
    val command: String,
    val cwd: String,
    val running: Boolean,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val stdout: String,
    val stderr: String,
    val droppedStdout: Long,
    val droppedStderr: Long,
)

internal class BackgroundTail(private val capacity: Int = MAX_BG_OUTPUT_CHARS) {
    private val text = StringBuilder()
    private var dropped = 0L
    @Synchronized fun append(value: String) {
        text.append(value)
        val overflow = (text.length - capacity).coerceAtLeast(0)
        text.delete(0, overflow)
        dropped += overflow
    }
    @Synchronized fun snapshot(): Pair<String, Long> = text.toString() to dropped
}

/** Host-side control files contain no command text or output. They are not backup state. */
class WorkspaceProcessSupervisor(tempDir: File) : AutoCloseable {
    private val directory = File(tempDir, "bg-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Cannot create process control directory" }
        setReadable(false, false); setWritable(false, false); setExecutable(false, false)
        setReadable(true, true); setWritable(true, true); setExecutable(true, true)
    }
    private val active = File(directory, "active").apply { createNewFile() }
    private val result = File(directory, "exit")
    private val owner = File("/proc/self/stat").readText()
    private val ownerPid = owner.substringBefore(' ').toLong()
    private val ownerStart = owner.substringAfterLast(") ").split(' ')[19].toLong()

    fun commandLine(command: List<String>): List<String> {
        require(command.isNotEmpty())
        val script = """
            owner_alive() {
                stat=${'$'}(cat /proc/$ownerPid/stat 2>/dev/null) || return 1
                rest=${'$'}{stat##*) }
                set -- ${'$'}rest
                shift 19
                [ "${'$'}1" = "$ownerStart" ] && [ -f ${quote(active.path)} ]
            }
            owner_alive || exit 125
            ${command.joinToString(" ", transform = ::quote)} &
            child=${'$'}!
            child_stat=${'$'}(cat /proc/${'$'}child/stat 2>/dev/null)
            child_rest=${'$'}{child_stat##*) }
            set -- ${'$'}child_rest
            child_start=''
            if [ "${'$'}#" -ge 20 ]; then shift 19; child_start=${'$'}1; fi
            (
                while owner_alive; do sleep 1; done
                stat=${'$'}(cat /proc/${'$'}child/stat 2>/dev/null)
                rest=${'$'}{stat##*) }
                set -- ${'$'}rest
                if [ -n "${'$'}child_start" ] && [ "${'$'}#" -ge 20 ]; then
                    shift 19
                    if [ "${'$'}1" = "${'$'}child_start" ]; then
                        # PRoot ignores TERM. QUIT invokes kill_all_tracees, including detached children.
                        kill -QUIT "${'$'}child" 2>/dev/null
                    fi
                fi
                sleep 1
                kill -KILL -${'$'}${'$'} 2>/dev/null
            ) &
            wait "${'$'}child"
            code=${'$'}?
            printf '%s\n' "${'$'}code" > ${quote(result.path)}
            # The command is finished: also remove its watchdog and any remaining group members.
            kill -KILL -${'$'}${'$'} 2>/dev/null
            exit "${'$'}code"
        """.trimIndent()
        val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
        val setsid = if (File("/system/bin/toybox").isFile) listOf("/system/bin/toybox", "setsid") else listOf("setsid")
        return setsid + listOf(shell, "-c", script)
    }

    internal fun requestStop() { active.delete() }
    internal fun exitCode(): Int? = runCatching { result.readText().trim().toIntOrNull() }.getOrNull()
    override fun close() { active.delete(); directory.deleteRecursively() }
    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

/** App-lifetime registry. Admission precedes launch; ended records and both output tails are bounded. */
class WorkspaceBackgroundProcesses : AutoCloseable {
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun start(context: WorkspaceShellContext, launch: (WorkspaceProcessSupervisor) -> Process): BackgroundStatus {
        check(entries.values.count { it.process.isAlive } < MAX_BG_PROCESSES) { "Maximum $MAX_BG_PROCESSES background processes; stop one first" }
        val supervisor = WorkspaceProcessSupervisor(context.tempDir)
        val process = try { launch(supervisor) } catch (error: Throwable) { supervisor.close(); throw error }
        val entry = Entry(context, supervisor, process)
        entries[entry.id] = entry
        while (entries.size > MAX_BG_RECORDS) {
            val oldest = entries.values.firstOrNull { !it.process.isAlive && it.id != entry.id } ?: break
            entries.remove(oldest.id)
        }
        return entry.status()
    }

    @Synchronized fun list(root: String): List<BackgroundStatus> = entries.values.filter { it.root == root }.map { it.status() }
    @Synchronized fun status(root: String, id: String): BackgroundStatus? = entries[id]?.takeIf { it.root == root }?.status()
    @Synchronized fun stop(root: String, id: String): Boolean {
        val entry = entries[id]?.takeIf { it.root == root } ?: return false
        entry.supervisor.requestStop()
        check(entry.process.waitFor(3, TimeUnit.SECONDS)) { "Process shutdown could not be confirmed; retry stop" }
        return true
    }
    @Synchronized fun stopAll(root: String) = stopEntries(entries.values.filter { it.root == root })
    @Synchronized override fun close() = stopEntries(entries.values.toList())

    private fun stopEntries(selected: List<Entry>) {
        selected.forEach { it.supervisor.requestStop() }
        selected.forEach { check(it.process.waitFor(3, TimeUnit.SECONDS)) { "Process shutdown could not be confirmed" } }
    }

    private class Entry(context: WorkspaceShellContext, val supervisor: WorkspaceProcessSupervisor, val process: Process) {
        val id = "bg_${UUID.randomUUID()}"
        val root = context.root
        private val command = context.command
        private val cwd = context.cwd
        private val started = System.currentTimeMillis()
        private val stdout = BackgroundTail()
        private val stderr = BackgroundTail()
        private val outThread = drain(process.inputStream, stdout)
        private val errThread = drain(process.errorStream, stderr)
        @Volatile private var completedCode: Int? = null

        init {
            try { process.outputStream.close() } catch (_: IOException) { /* child may have already exited */ }
            Thread {
                process.waitFor()
                outThread.join(1_000); errThread.join(1_000)
                completedCode = supervisor.exitCode() ?: process.exitValue()
                supervisor.close()
            }.apply { isDaemon = true; name = "workspace-bg-wait"; start() }
        }

        fun status(): BackgroundStatus {
            val running = process.isAlive
            if (!running) { outThread.join(1_000); errThread.join(1_000) }
            val out = stdout.snapshot(); val err = stderr.snapshot()
            return BackgroundStatus(id, command, cwd, running,
                if (running) null else completedCode ?: supervisor.exitCode() ?: process.exitValue(),
                started, out.first, err.first, out.second, err.second)
        }

        private fun drain(stream: InputStream, buffer: BackgroundTail): Thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val chars = CharArray(4096)
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        buffer.append(String(chars, 0, count))
                    }
                }
            } catch (_: IOException) { /* retain output after shutdown */ }
        }.apply { isDaemon = true; name = "workspace-bg-output"; start() }
    }
}
