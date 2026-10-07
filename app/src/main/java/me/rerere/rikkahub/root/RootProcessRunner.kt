package me.rerere.rikkahub.root

import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class RootProcessResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val reason: String? = null,
)

/** One launch only; cancellation/timeout never retries a command that may have side effects. */
internal object RootProcessRunner {
    fun run(
        argv: List<String>,
        timeoutMs: Int,
        maxStdoutBytes: Int = 8_000,
        maxStderrBytes: Int = 2_000,
        beforeStop: () -> Unit = {},
    ): RootProcessResult {
        require(argv.isNotEmpty())
        require(timeoutMs > 0)
        require(maxStdoutBytes >= 0 && maxStderrBytes >= 0)
        val process = try {
            ProcessBuilder(argv).start()
        } catch (error: IOException) {
            return RootProcessResult(error = "exec_failed", reason = error.message)
        }
        // Non-interactive tool: prompts/readers must receive EOF rather than wait indefinitely.
        runCatching { process.outputStream.close() }
        val stdout = BoundedOutputStream(maxStdoutBytes)
        val stderr = BoundedOutputStream(maxStderrBytes)
        val readers = listOf(
            Thread({ runCatching { process.inputStream.use { it.copyTo(stdout) } } }, "root-stdout"),
            Thread({ runCatching { process.errorStream.use { it.copyTo(stderr) } } }, "root-stderr"),
        ).onEach { it.isDaemon = true; it.start() }
        try {
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { beforeStop() }
                process.destroyForcibly()
            }
            readers.forEach { it.join(1_000) }
            return RootProcessResult(
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.snapshot(),
                stderr = stderr.snapshot(),
                error = if (finished) null else "command_timeout",
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
}
