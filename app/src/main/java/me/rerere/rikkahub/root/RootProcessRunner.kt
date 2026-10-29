package me.rerere.rikkahub.root

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class RootProcessResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val reason: String? = null,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
)

/** One launch only; cancellation/timeout never retries a command that may have side effects. */
internal object RootProcessRunner {
    private const val MAX_FINAL_DRAIN_BYTES = 1024 * 1024

    fun run(
        argv: List<String>,
        timeoutMs: Int,
        maxStdoutBytes: Int = 8_000,
        maxStderrBytes: Int = 2_000,
        requireConfirmedOutput: Boolean = true,
        beforeStop: () -> Unit = {},
    ): RootProcessResult {
        require(argv.isNotEmpty())
        require(timeoutMs > 0)
        require(maxStdoutBytes >= 0 && maxStderrBytes >= 0)
        val process = try {
            ProcessBuilder(argv).start()
        } catch (_: IOException) {
            return RootProcessResult(error = "exec_failed", reason = "Не удалось запустить root-команду. Проверьте Root в настройках приложения.")
        }
        return runStartedProcess(process, timeoutMs, maxStdoutBytes, maxStderrBytes, requireConfirmedOutput, beforeStop)
    }

    internal fun runStartedProcess(
        process: Process,
        timeoutMs: Int,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
        requireConfirmedOutput: Boolean = true,
        beforeStop: () -> Unit = {},
    ): RootProcessResult {
        require(timeoutMs > 0)
        require(maxStdoutBytes >= 0 && maxStderrBytes >= 0)
        // Non-interactive tool: prompts/readers must receive EOF rather than wait indefinitely.
        runCatching { process.outputStream.close() }
        val stdout = BoundedOutputStream(maxStdoutBytes)
        val stderr = BoundedOutputStream(maxStderrBytes)
        val stdoutDrained = AtomicBoolean(false)
        val stderrDrained = AtomicBoolean(false)
        val readers = listOf(
            Thread({ stdoutDrained.set(drainWhileRunning(process, process.inputStream, stdout)) }, "root-stdout"),
            Thread({ stderrDrained.set(drainWhileRunning(process, process.errorStream, stderr)) }, "root-stderr"),
        ).onEach { it.isDaemon = true; it.start() }
        try {
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { beforeStop() }
                process.destroyForcibly()
            }
            readers.forEach { it.join(1_000) }
            val readerFailed = readers.any { it.isAlive } || !stdoutDrained.get() || !stderrDrained.get()
            if (readerFailed) readers.filter { it.isAlive }.forEach { it.interrupt() }
            // available() is only a snapshot, not EOF: a process reaper or inherited
            // pipe may deliver bytes later even after the owned process has exited.
            val incomplete = readerFailed || (finished && requireConfirmedOutput)
            val incompleteReason = "Вывод root-команды мог быть получен не полностью. Проверьте результат на устройстве; команда автоматически не повторяется."
            return RootProcessResult(
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.snapshot(),
                stderr = stderr.snapshot(),
                stdoutTruncated = stdout.isTruncated(),
                stderrTruncated = stderr.isTruncated(),
                error = if (!finished) "command_timeout" else if (incomplete) "root_output_incomplete" else null,
                reason = if (!finished) {
                    "Время root-команды истекло. Проверьте, что успело выполниться, перед новым вызовом; команда автоматически не повторяется." +
                        if (incomplete) " Вывод также мог быть получен не полностью." else ""
                } else if (incomplete) incompleteReason else null,
            )
        } catch (error: InterruptedException) {
            // runInterruptible maps coroutine cancellation to this interruption.
            runCatching { beforeStop() }
            process.destroyForcibly()
            readers.forEach { reader -> runCatching { reader.join(200) } }
            Thread.currentThread().interrupt()
            throw error
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** A background descendant may keep a pipe open after the owned shell has exited.
     * Drain available bytes while running, then one finite snapshot after shell exit.
     * Closing a stream from another thread cannot safely unblock ProcessPipeInputStream.read.
     */
    private fun drainWhileRunning(process: Process, stream: InputStream, output: BoundedOutputStream): Boolean {
        return runCatching {
            var complete = true
            stream.use {
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    if (!process.isAlive) {
                        // Descendants can replenish a pipe forever. After the owned process
                        // exits, drain one bounded snapshot instead of waiting for an empty pipe.
                        val available = stream.available()
                        if (available > MAX_FINAL_DRAIN_BYTES) complete = false
                        var remaining = minOf(available, MAX_FINAL_DRAIN_BYTES)
                        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count <= 0) { complete = false; break }
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        break
                    }
                    val available = stream.available()
                    if (available > 0) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, available))
                        if (count <= 0) { complete = false; break }
                        output.write(buffer, 0, count)
                    } else {
                        Thread.sleep(5)
                    }
                }
            }
            complete && !Thread.currentThread().isInterrupted
        }.getOrDefault(false)
    }
}
