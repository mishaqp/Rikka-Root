package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

/** An app-held pipe controls lifetime; the host-side file stores only the exit code. */
class WorkspaceProcessSupervisor(tempDir: File) : AutoCloseable {
    private val directory = File(tempDir, "bg-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Cannot create process control directory" }
        setReadable(false, false); setWritable(false, false); setExecutable(false, false)
        setReadable(true, true); setWritable(true, true); setExecutable(true, true)
    }
    private val result = File(directory, "exit")
    private var ownerChannel: OutputStream? = null

    fun commandLine(command: List<String>): List<String> {
        require(command.isNotEmpty())
        val script = """
            diagnostic() { printf 'workspace-background: %s\n' "${'$'}1" >&2 2>/dev/null || :; }
            # stdin is a lifetime pipe, not input for the command. Only the app owns its write end.
            exec 3<&0 0</dev/null
            if ! IFS= read -r control <&3; then
                diagnostic owner_channel_closed_before_start
                exit 125
            fi
            if [ "${'$'}control" != run ]; then
                diagnostic invalid_owner_handshake
                exit 125
            fi
            child_identity() {
                # Use shell builtins: PATH, PRoot and access to the app's /proc entry are irrelevant.
                IFS= read -r stat 2>/dev/null < /proc/${'$'}child/stat || return 1
                rest=${'$'}{stat##*) }
                [ "${'$'}rest" != "${'$'}stat" ] || return 1
                set -- ${'$'}rest
                [ "${'$'}#" -ge 20 ] || return 1
                shift 19
                case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
                printf '%s' "${'$'}1"
            }
            ${command.joinToString(" ", transform = ::quote)} </dev/null 3<&- &
            child=${'$'}!
            child_start=${'$'}(child_identity)
            (
                # App death also closes stderr. A diagnostic must not kill the watchdog with SIGPIPE.
                trap '' PIPE
                # The explicit stdin redirect matters: asynchronous shell commands otherwise get /dev/null.
                if IFS= read -r control; then
                    diagnostic unexpected_owner_message
                else
                    diagnostic owner_channel_closed
                fi
                current_start=${'$'}(child_identity)
                if [ -n "${'$'}child_start" ] && [ "${'$'}current_start" = "${'$'}child_start" ]; then
                    # PRoot ignores TERM. QUIT invokes kill_all_tracees, including detached children.
                    kill -QUIT "${'$'}child" 2>/dev/null
                elif kill -0 "${'$'}child" 2>/dev/null; then
                    diagnostic child_identity_unavailable
                fi
                sleep 1
                kill -KILL -${'$'}${'$'} 2>/dev/null
            ) <&3 3<&- &
            exec 3<&-
            wait "${'$'}child"
            code=${'$'}?
            # Startup cleanup may remove temp files. Recreate only the private result directory;
            # process lifetime never depends on its existence.
            if [ ! -d ${quote(directory.path)} ]; then
                (umask 077; mkdir -p ${quote(directory.path)}) 2>/dev/null
            fi
            printf '%s\n' "${'$'}code" 2>/dev/null > ${quote(result.path)} || diagnostic exit_code_record_unavailable
            # The command is finished: also remove its watchdog and any remaining group members.
            kill -KILL -${'$'}${'$'} 2>/dev/null
            exit "${'$'}code"
        """.trimIndent()
        val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
        val setsid = if (File("/system/bin/toybox").isFile) listOf("/system/bin/toybox", "setsid") else listOf("setsid")
        return setsid + listOf(shell, "-c", script)
    }

    @Synchronized internal fun attachOwnerChannel(channel: OutputStream) {
        ownerChannel = channel
        try {
            channel.write("run\n".toByteArray(Charsets.US_ASCII))
            channel.flush()
        } catch (_: IOException) {
            // Preserve the process's startup diagnostic and exit code if it has already failed.
            requestStop()
        }
    }
    @Synchronized internal fun requestStop() {
        try { ownerChannel?.close() } catch (_: IOException) { /* already closed by the process */ }
        ownerChannel = null
    }
    internal fun exitCode(): Int? = runCatching { result.readText().trim().toIntOrNull() }.getOrNull()
    override fun close() { requestStop(); directory.deleteRecursively() }
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
            supervisor.attachOwnerChannel(process.outputStream)
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
