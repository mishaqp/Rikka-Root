package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
    fun startBackground(context: WorkspaceShellContext, supervisor: WorkspaceProcessSupervisor): Process =
        error("This shell runner does not support managed background processes")
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val shellCompatibilityMode: Boolean = false,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun startBackground(context: WorkspaceShellContext, supervisor: WorkspaceProcessSupervisor): Process =
        ProcessBuilder(supervisor.commandLine(listOf(defaultShell(), "-c", context.command)))
            .directory(context.workingDir).redirectErrorStream(false).start()

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val identity = ownedIdentity()
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    if (stdinWriter == null) {
        // 没有 stdin 输入时立即关闭管道, 让子进程读到 EOF;
        // 否则 cat/gh/kubectl 等按 isatty 判断的工具会把这根永不写入的管道当成待输入流而永久阻塞
        try {
            outputStream.close()
        } catch (_: IOException) {
            // 子进程已提前退出导致管道关闭, 忽略
        }
    }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            stopForeground(identity)
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // PRoot must receive QUIT while alive so it can reap tracees before forced cleanup.
        stopForeground(identity)
        runCatching { stdinWriter?.join(1_000) }
        runCatching { stdout.join(1_000) }
        runCatching { stderr.join(1_000) }
        Thread.currentThread().interrupt()
        throw e
    }
}

private data class ForegroundProcessIdentity(val pid: Long, val parent: Long, val started: Long)

/** Android's Process has no public pid() API. Its platform toString exposes pid; /proc
 * must independently verify that it is our direct child, and record its kernel start time.
 * On a desktop JVM prefer the public pid() method, accessed without an Android API dependency.
 */
private fun Process.ownedIdentity(): ForegroundProcessIdentity? = runCatching {
    val pid = (runCatching { Process::class.java.getMethod("pid").invoke(this) as Long }.getOrNull()
        ?: Regex("\\bpid=(\\d+)").find(toString())?.groupValues?.get(1)?.toLongOrNull()) ?: return null
    if (pid <= 1 || !isAlive) return null
    val self = File("/proc/self/stat").readText().substringBefore(' ').toLong()
    val fields = File("/proc/$pid/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))
    val parent = fields.getOrNull(1)?.toLongOrNull() ?: return null
    val started = fields.getOrNull(19)?.toLongOrNull() ?: return null
    if (parent != self || started <= 0) return null
    ForegroundProcessIdentity(pid, parent, started)
}.getOrNull()

private fun Process.stopForeground(identity: ForegroundProcessIdentity?) {
    if (!isAlive) return
    if (identity != null) {
        // Check ownership again in the signal sender. PID and start time come only from
        // the owned Process and procfs, never from a command or model-visible output.
        val script = """
            IFS= read -r stat < /proc/${identity.pid}/stat || exit 1
            rest=${'$'}{stat##*) }
            set -- ${'$'}rest
            [ "${'$'}2" = "${identity.parent}" ] || exit 1
            shift 19
            [ "${'$'}1" = "${identity.started}" ] || exit 1
            kill -QUIT ${identity.pid}
        """.trimIndent()
        runCatching {
            val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
            val signal = ProcessBuilder(shell, "-c", script).redirectErrorStream(true).start()
            try {
                signal.outputStream.close()
                if (!signal.waitFor(200, TimeUnit.MILLISECONDS)) signal.destroyForcibly()
            } finally {
                if (signal.isAlive) signal.destroyForcibly()
                signal.inputStream.close()
            }
        }
    } else {
        // If the platform cannot prove a PID, use the owned handle rather than guess one.
        destroy()
    }
    if (runCatching { waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return
    destroyForcibly()
    runCatching { waitFor(500, TimeUnit.MILLISECONDS) }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
