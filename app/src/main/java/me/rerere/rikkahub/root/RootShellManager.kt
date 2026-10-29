package me.rerere.rikkahub.root

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last explicit verification result; READY is not a permanent grant from the root manager. */
enum class RootStatus { UNCHECKED, CHECKING, READY, UNAVAILABLE, DENIED_OR_FAILED, NOT_ROOT, TIMED_OUT }

/** Real Android root only. No adb/shell-UID or external-app fallback is used. */
class RootShellManager(
    private val suExecutable: String = "su",
    private val probeTimeoutMs: Int = 30_000,
    private val controlDirectory: File = File(System.getProperty("java.io.tmpdir") ?: "."),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val _status = MutableStateFlow(RootStatus.UNCHECKED)
    val status = _status.asStateFlow()
    private val probeLock = Mutex()
    private var cachedStatus: RootStatus? = null
    private var verifiedAtMs = 0L
    private val commandLock = Mutex()
    private val sessionStateLock = Any()
    private var sessionEpoch = 0L
    private data class PreparedSession(val transport: HeadlessRootSession, val headlessAllowed: Boolean)
    @Volatile private var sharedSession: PreparedSession? = null

    /** May open the root manager's consent dialog. UI calls this only after a user tap. */
    suspend fun verifyRoot(force: Boolean = false, prepareHeadless: Boolean = false): RootStatus {
        val epoch = synchronized(sessionStateLock) { sessionEpoch }
        val verified = verifyRootProbe(force)
        if (verified != RootStatus.READY) close()
        if (prepareHeadless && verified == RootStatus.READY) {
            var prepared = false
            try {
                commandLock.withLock {
                    // Only this explicit foreground action grants background access.
                    prepared = prepareSession(epoch, headlessAllowed = true) != null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The foreground button must fail closed on storage/capability/preparation errors.
                // Do not expose exception text, or let it escape the UI coroutine.
            }
            if (!prepared) return probeLock.withLock {
                cachedStatus = null
                verifiedAtMs = 0L
                _status.value = RootStatus.DENIED_OR_FAILED
                RootStatus.DENIED_OR_FAILED
            }
        }
        return verified
    }

    private suspend fun verifyRootProbe(force: Boolean): RootStatus = probeLock.withLock {
        cachedStatus?.let { cached ->
            val ttl = if (cached == RootStatus.READY) 60_000 else 15_000
            if (!force && clockMs() - verifiedAtMs < ttl) return@withLock cached
        }
        _status.value = RootStatus.CHECKING
        try {
            // The nonce brackets the actual id output, so a su banner containing "0"
            // cannot be mistaken for a completed UID probe when output is incomplete.
            val token = "RIKKA_ROOT_PROBE_${UUID.randomUUID().toString().replace("-", "")}"
            val begin = "${token}_BEGIN"
            val end = "${token}_END"
            val probeCommand = "printf '\\n%s\\n' '$begin'; id -u; probe_status=\$?; printf '%s\\n' '$end'; exit \$probe_status"
            val probe = runInterruptible(Dispatchers.IO) {
                RootProcessRunner.run(listOf(suExecutable, "-c", probeCommand), probeTimeoutMs, 512, 1_024, requireConfirmedOutput = false)
            }
            val output = probe.stdout.replace("\r\n", "\n")
            val prefix = "\n$begin\n"
            val suffix = "\n$end\n"
            val prefixAt = output.indexOf(prefix)
            val suffixAt = if (prefixAt >= 0) output.indexOf(suffix, prefixAt + prefix.length) else -1
            val uid = if (suffixAt >= 0) output.substring(prefixAt + prefix.length, suffixAt) else null
            val verified = when {
                probe.error == "exec_failed" -> RootStatus.UNAVAILABLE
                probe.error == "command_timeout" -> RootStatus.TIMED_OUT
                probe.error != null -> RootStatus.DENIED_OR_FAILED
                probe.exitCode != 0 -> RootStatus.DENIED_OR_FAILED
                probe.stdoutTruncated || uid == null -> RootStatus.DENIED_OR_FAILED
                uid != "0" -> RootStatus.NOT_ROOT
                else -> RootStatus.READY
            }
            cachedStatus = verified
            verifiedAtMs = clockMs()
            _status.value = verified
            verified
        } catch (error: CancellationException) {
            // A cancelled verification must neither leave a spinner nor cache a failed grant.
            cachedStatus = null
            _status.value = RootStatus.UNCHECKED
            throw error
        }
    }

    /** Commands share one cancellable queue; each request still owns a separate shell and group. */
    internal suspend fun exec(command: String, timeoutMs: Int, headless: Boolean = false, mayExecute: suspend () -> Boolean = { true }): RootProcessResult = commandLock.withLock {
        if (headless) {
            if (!mayExecute()) return@withLock approvalRequired()
            val session = sharedSession?.takeIf { it.headlessAllowed }?.transport
                ?: return@withLock interactionRequired()
            return@withLock executePrepared(session, command, timeoutMs, mayExecute)
        }
        sharedSession?.transport?.takeIf { it.isAlive() }?.let { session ->
            if (!mayExecute()) return@withLock approvalRequired()
            // The broker checks UID for each request; a live transport needs no new su probe.
            return@withLock executePrepared(session, command, timeoutMs, mayExecute, foreground = true)
        }
        val epoch = synchronized(sessionStateLock) { sessionEpoch }
        val verified = verifyRoot()
        if (verified != RootStatus.READY) {
            return@withLock RootProcessResult(
                error = when (verified) {
                    RootStatus.UNAVAILABLE -> "su_not_available"
                    RootStatus.TIMED_OUT -> "root_verification_timeout"
                    else -> "root_not_granted"
                },
                reason = "Root не подтверждён. Разрешите доступ Rikka-Root в менеджере root и повторите проверку Root в настройках.",
            )
        }
        if (!mayExecute()) return@withLock approvalRequired()
        val result = withContext(Dispatchers.IO) {
            if (!mayExecute()) return@withContext approvalRequired()
            val session = try {
                // Foreground reuse does not grant background eligibility.
                prepareSession(epoch, headlessAllowed = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            // Closing root during authentication wins, including over the one-shot fallback.
            if (synchronized(sessionStateLock) { epoch != sessionEpoch }) return@withContext interactionRequired()
            if (!mayExecute()) return@withContext approvalRequired()
            if (session != null) {
                // Never fall back after execute: a lost response does not mean no side effects.
                return@withContext runInterruptible { session.execute(command, timeoutMs, foreground = true) }
            }
            // Preparation failed before any user command was submitted. Keep the legacy path.
            runInterruptible {
                RootCommandSession(suExecutable, controlDirectory).use { session ->
                    val result = RootProcessRunner.run(
                        listOf(suExecutable, "-c", session.wrap(command)),
                        timeoutMs,
                        beforeStop = { session.cancel() },
                    )
                    if (session.rootDenied()) {
                        result.copy(error = "root_not_granted", reason = "Команда не получила права root и не выполнена. Повторите проверку Root в настройках.")
                    } else if (session.capabilityUnavailable()) {
                        result.copy(error = "root_cleanup_unavailable", reason = "Команда не выполнена: на устройстве нет setsid для безопасной остановки. Проверьте набор системных команд root.")
                    } else if (session.cleanupError != null) {
                        result.copy(error = "root_cleanup_unconfirmed", reason = session.cleanupError)
                    } else result
                }
            }
        }
        if (result.error == "root_not_granted") probeLock.withLock {
            cachedStatus = RootStatus.NOT_ROOT
            verifiedAtMs = clockMs()
            _status.value = RootStatus.NOT_ROOT
        }
        result
    }

    private suspend fun executePrepared(session: HeadlessRootSession, command: String, timeoutMs: Int, mayExecute: suspend () -> Boolean, foreground: Boolean = false): RootProcessResult = withContext(Dispatchers.IO) {
        if (!mayExecute()) return@withContext approvalRequired()
        currentCoroutineContext().ensureActive()
        runInterruptible { session.execute(command, timeoutMs, foreground) }
    }

    /** Called under commandLock; epoch prevents a concurrent close from resurrecting root. */
    private suspend fun prepareSession(epoch: Long, headlessAllowed: Boolean): HeadlessRootSession? {
        val old = synchronized(sessionStateLock) {
            if (epoch != sessionEpoch) return null
            sharedSession.also { sharedSession = null }
        }
        var candidate: HeadlessRootSession? = null
        var installed = false
        try {
            withContext(NonCancellable + Dispatchers.IO) { old?.transport?.close() }
            runInterruptible(Dispatchers.IO) {
                candidate = HeadlessRootSession.open(suExecutable, controlDirectory, probeTimeoutMs)
            }
            currentCoroutineContext().ensureActive()
            installed = synchronized(sessionStateLock) {
                if (epoch == sessionEpoch && candidate?.isAlive() == true) {
                    sharedSession = PreparedSession(candidate!!, headlessAllowed)
                    true
                } else false
            }
            return candidate.takeIf { installed }
        } finally {
            // Covers cancellation on the IO handoff, before the caller can own the new shell.
            if (!installed) withContext(NonCancellable + Dispatchers.IO) { candidate?.close() }
        }
    }

    fun isHeadlessReady(): Boolean = sharedSession?.let { it.headlessAllowed && it.transport.isAlive() } == true

    /** Drop the shared transport and its background eligibility; headless never reconnects. */
    fun close() {
        val old = synchronized(sessionStateLock) {
            sessionEpoch++
            sharedSession.also { sharedSession = null }
        }
        old?.transport?.close()
    }

    private fun interactionRequired() = RootProcessResult(
        error = "root_interaction_required",
        reason = "Откройте настройки Root и нажмите проверку Root для фоновой работы. После перезапуска приложения или устройства требуется новая проверка; команда не повторяется.",
    )

    private fun approvalRequired() = RootProcessResult(
        error = "root_approval_required",
        reason = "Автоматический доступ Root отключён до запуска. Подтвердите новый вызов команды.",
    )
}


/**
 * An already-authenticated root transport. There is exactly one su launch, exclusively in open().
 * Requests and replies use private nonce directories, never command stdout. A root guardian owns
 * each setsid group and cancels it without another su, including when the app closes its transport.
 */
private class HeadlessRootSession private constructor(
    private val directory: File,
    private val process: Process,
) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var activeRequest: File? = null
    @Volatile private var retainControl = false

    fun isAlive(): Boolean = !closed && process.isAlive

    fun execute(command: String, timeoutMs: Int, foreground: Boolean = false): RootProcessResult {
        require(timeoutMs > 0)
        if (closed || !process.isAlive) return interactionRequired()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val request = File(directory, nonce)
        if (!request.mkdir() || !secure(request, directory = true)) { request.delete(); return interactionRequired() }
        activeRequest = request
        val active = File(request, "active")
        val result = File(request, "result")
        var submitted = false
        try {
            // Root redirection preserves these app-owned0600 inodes, including result.new on rename.
            listOf("stdout", "stderr", "result.new").forEach { privateFile(request, it, "") }
            privateFile(request, "command", command)
            privateFile(request, "foreground", if (foreground) "1\n" else "0\n")
            privateFile(request, "ticks", ((timeoutMs.toLong() + 49) / 50).toString())
            privateFile(request, "active", nonce)
            if (closed || !process.isAlive) return interactionRequired()
            // The model command is a private script file. Only an unpredictable hex nonce enters stdin.
            process.outputStream.write((nonce + "\n").toByteArray(Charsets.US_ASCII))
            process.outputStream.flush()
            submitted = true
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            while (!result.isFile && process.isAlive && !closed && System.nanoTime() < deadline) Thread.sleep(10)
            if (!result.isFile) {
                active.delete()
                awaitCleanup(result)
            }
            val outcome = acknowledgement(result, nonce) ?: return quarantine(request)
            return when (outcome) {
                "timeout", "cancelled" -> capturedResult(request, error = "command_timeout",
                    reason = "Время root-команды истекло. Вывод может быть неполным. Проверьте, что успело выполниться, перед новым вызовом; команда автоматически не повторяется.")
                "not_root", "failed" -> { close(); interactionRequired() }
                else -> if (outcome.startsWith("output_incomplete:")) {
                    val code = outcome.substringAfter(':').toInt()
                    capturedResult(request, exitCode = code, error = "root_output_incomplete",
                        reason = "Вывод Root-команды может быть неполным. Проверьте результат перед новым вызовом; команда автоматически не повторяется.")
                } else outcome.toIntOrNull()?.takeIf { it in 0..255 }?.let { code ->
                    capturedResult(request, exitCode = code)
                } ?: quarantine(request)
            }
        } catch (error: InterruptedException) {
            active.delete()
            // runInterruptible clears interruption when it throws. Preserve it after guardian acknowledgment.
            awaitCleanup(result)
            if (acknowledgement(result, nonce) == null) quarantine()
            Thread.currentThread().interrupt()
            throw error
        } catch (_: java.io.IOException) {
            active.delete()
            if (submitted) { awaitCleanup(result); return quarantine(request) }
            return interactionRequired()
        } finally {
            active.delete()
            // Do not remove the identity files before the root guardian has acknowledged group cleanup.
            File(request, "command").delete()
            if (!retainControl && (!submitted || result.isFile)) request.deleteRecursively()
            activeRequest = null
            if (closed && !retainControl) directory.deleteRecursively()
        }
    }

    private fun acknowledgement(result: File, nonce: String): String? {
        if (!Files.isRegularFile(result.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readPrefix(result, 65)
        if (bytes.size > 64) return null
        val fields = bytes.toString(Charsets.US_ASCII).split(' ')
        if (fields.size != 2 || fields[0] != nonce) return null
        val outcome = fields[1]
        return outcome.takeIf {
            it in setOf("timeout", "cancelled", "not_root", "failed") ||
                it.toIntOrNull()?.let { code -> code in 0..255 } == true ||
                (it.startsWith("output_incomplete:") && it.substringAfter(':').toIntOrNull()?.let { code -> code in 0..255 } == true)
        }
    }

    private fun quarantine(request: File? = null): RootProcessResult {
        retainControl = true
        close()
        val error = "root_cleanup_unconfirmed"
        val reason = "Завершение Root-команды не подтверждено. Фоновая сессия закрыта; проверьте процессы на устройстве. Команда не повторяется."
        // A lost acknowledgement cannot establish an exit status, but output already captured
        // from this request is still useful. Never lose it or infer success from its contents.
        return request?.let { runCatching { capturedResult(it, error = error, reason = reason) }.getOrNull() }
            ?: RootProcessResult(error = error, reason = reason)
    }

    private fun awaitCleanup(result: File) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!result.isFile && System.nanoTime() < deadline) {
            try { Thread.sleep(10) } catch (_: InterruptedException) { /* bounded cleanup */ }
        }
    }

    private data class CapturedOutput(val text: String, val truncated: Boolean = false)

    private fun capturedResult(request: File, exitCode: Int? = null, error: String? = null, reason: String? = null): RootProcessResult {
        val stdout = output(request, "stdout", 8_000)
        val stderr = output(request, "stderr", 2_000)
        return RootProcessResult(exitCode = exitCode, stdout = stdout.text, stderr = stderr.text,
            error = error, reason = reason, stdoutTruncated = stdout.truncated, stderrTruncated = stderr.truncated)
    }

    private fun output(request: File, name: String, limit: Int): CapturedOutput {
        val file = File(request, name)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return CapturedOutput("")
        val bytes = readPrefix(file, limit + 4)
        if (bytes.size <= limit) return CapturedOutput(bytes.toString(Charsets.UTF_8))
        var keep = limit
        while (keep > 0 && bytes[keep].toInt() and 0xc0 == 0x80) keep--
        return CapturedOutput(String(bytes, 0, keep, Charsets.UTF_8) + "\n…[вывод сокращён]", truncated = true)
    }

    private fun readPrefix(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(limit)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }

    override fun close() {
        closed = true
        runCatching { activeRequest?.let { File(it, "active").delete() } }
        runCatching { process.outputStream.close() } // EOF closes the existing privileged shell.
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
        }
        // An active guardian may still need these files; it removes its own FIFO control files.
        runCatching { if (activeRequest == null && !retainControl) directory.deleteRecursively() }
    }

    companion object {
        private fun interactionRequired() = RootProcessResult(
            error = "root_interaction_required",
            reason = "Нет действующей фоновой Root-сессии. Нажмите проверку Root в настройках; эта команда автоматически не повторяется.",
        )

        fun open(suExecutable: String, parent: File, timeoutMs: Int): HeadlessRootSession? {
            val directory = try { Files.createTempDirectory(parent.toPath(), "root-headless-").toFile() } catch (_: java.io.IOException) { return null }
            if (!secure(directory, directory = true)) { directory.delete(); return null }
            val process = try {
                // A root-created0600 reply is unreadable by the Android app UID. Create it before su.
                privateFile(directory, "ready", "")
                ProcessBuilder(suExecutable, "-c", brokerScript(directory)).start()
            } catch (_: java.io.IOException) {
                directory.deleteRecursively()
                return null
            }
            val session = HeadlessRootSession(directory, process)
            var authenticated = false
            try {
                // Root-manager banners are drained and discarded, never logged or used as a protocol.
                listOf(process.inputStream, process.errorStream).forEach { stream ->
                    Thread({ runCatching { stream.use { input -> val buffer = ByteArray(1_024); while (input.read(buffer) != -1) {} } } }, "root-headless-drain")
                        .apply { isDaemon = true; start() }
                }
                val ready = File(directory, "ready")
                val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
                while (ready.length() == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                authenticated = Files.isRegularFile(ready.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    session.readPrefix(ready, 6).toString(Charsets.US_ASCII) == "ready" && process.isAlive
                return session.takeIf { authenticated }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (_: java.io.IOException) {
                return null
            } finally {
                // Includes read failures and cancellation before the caller can own this process.
                if (!authenticated) session.close()
            }
        }

        private fun secure(file: File, directory: Boolean): Boolean =
            file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false) &&
                file.setReadable(true, true) && file.setWritable(true, true) && (!directory || file.setExecutable(true, true))

        private fun privateFile(parent: File, name: String, value: String) {
            val file = File(parent, name)
            if (!file.createNewFile() || !secure(file, directory = false)) throw java.io.IOException("Private root control unavailable")
            file.writeText(value)
        }

        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

        private fun brokerScript(directory: File): String {
            val stat = File("/proc/self/stat").readText()
            val appPid = stat.substringBefore(' ').toLong()
            val appStarted = stat.substringAfterLast(") ").split(' ')[19].toLong()
            return """
            original_umask=${'$'}(umask)
            umask 077
            base=${quote(directory.path)}
            app_pid=$appPid
            app_started=$appStarted
            [ "${'$'}(id -u)" = 0 ] || exit 126
            for capability in setsid mkfifo dd cat sleep mv rm; do command -v "${'$'}capability" >/dev/null 2>&1 || exit 127; done
            # Detect storage/capability failure before accepting any user command.
            mkfifo "${'$'}base/probe.pipe" || exit 127
            timed_read=no
            # Android shells supporting fractional read timeouts can wait for completion events.
            # Keep the original polling guardian on other shells, with no auxiliary watchdog to fail.
            if printf 'ready\n' | (IFS= read -r -t 0.05 probe_value && [ "${'$'}probe_value" = ready ]) 2>/dev/null; then
                exec 3<> "${'$'}base/probe.pipe"
                if IFS= read -r -t 0.01 probe_value <&3 2>/dev/null; then exit 127; fi
                exec 3>&-
                timed_read=yes
            fi
            rm -f "${'$'}base/probe.pipe"
            # Count actual bytes after each short block read, never records (dd count_bytes can stop early).
            capture_mode=bytes
            [ "${'$'}(printf x | wc -c 2>/dev/null)" != 1 ] || capture_mode=blocks
            broker=${'$'}${'$'}
            IFS= read -r stat < /proc/${'$'}${'$'}/stat || exit 125
            rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19; broker_started=${'$'}1
            same_process() {
                check_pid=${'$'}1; check_started=${'$'}2
                IFS= read -r check_stat 2>/dev/null < "/proc/${'$'}check_pid/stat" || return 1
                check_rest=${'$'}{check_stat##*) }; set -- ${'$'}check_rest
                [ "${'$'}1" != Z ] || return 1
                shift 19; [ "${'$'}1" = "${'$'}check_started" ]
            }
            owned_group() {
                identity=${'$'}1
                [ -s "${'$'}identity" ] || return 1
                read -r group_pid group_started < "${'$'}identity"
                IFS= read -r group_stat 2>/dev/null < "/proc/${'$'}group_pid/stat" || return 1
                group_rest=${'$'}{group_stat##*) }; set -- ${'$'}group_rest
                [ "${'$'}3" = "${'$'}group_pid" ] && [ "${'$'}4" = "${'$'}group_pid" ] || return 1
                shift 19
                [ "${'$'}1" = "${'$'}group_started" ] || return 1
            }
            stop_group() {
                owned_group "${'$'}1" || return 1
                kill -KILL -"${'$'}group_pid" 2>/dev/null || return 1
                count=20
                while same_process "${'$'}group_pid" "${'$'}group_started" && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                ! same_process "${'$'}group_pid" "${'$'}group_started"
            }
            release_group() {
                owned_group "${'$'}1" || return 1
                printf 'release\n' >&4 || return 1
                count=20
                while same_process "${'$'}group_pid" "${'$'}group_started" && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                ! same_process "${'$'}group_pid" "${'$'}group_started"
            }
            printf ready > "${'$'}base/ready"
            while IFS= read -r nonce; do
                case "${'$'}nonce" in ''|*[!a-f0-9]*) exit 125;; esac
                [ "${'$'}{#nonce}" = 32 ] || exit 125
                request="${'$'}base/${'$'}nonce"
                [ -d "${'$'}request" ] || continue
                (
                    finish() { rm -f "${'$'}request/command"; printf '%s %s' "${'$'}nonce" "${'$'}1" > "${'$'}request/result.new"; mv "${'$'}request/result.new" "${'$'}request/result"; }
                    [ "${'$'}(id -u)" = 0 ] || { finish not_root; exit; }
                    [ -f "${'$'}request/active" ] || { finish cancelled; exit; }
                    same_process "${'$'}broker" "${'$'}broker_started" && same_process "${'$'}app_pid" "${'$'}app_started" || { finish cancelled; exit; }
                    mkfifo "${'$'}request/out.pipe" "${'$'}request/err.pipe" "${'$'}request/completed.pipe" "${'$'}request/release.pipe" || { finish failed; exit; }
                    # A dedicated completion FIFO wakes the guardian immediately. It never contains stdout.
                    exec 3<> "${'$'}request/completed.pipe"
                    exec 4<> "${'$'}request/release.pipe"
                    # Independent drain groups can be stopped even if an escaped command retains a FIFO.
                    for channel in out err; do
                        case "${'$'}channel" in out) cap=8004; target=stdout;; err) cap=2004; target=stderr;; esac
                        setsid sh -c '
                            request=${'$'}1; channel=${'$'}2; cap=${'$'}3; capture_mode=${'$'}4; target=${'$'}5
                            IFS= read -r stat < /proc/${'$'}${'$'}/stat || exit 125
                            rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19
                            printf "%s %s\n" "${'$'}${'$'}" "${'$'}1" > "${'$'}request/${'$'}channel.pid"
                            if [ "${'$'}capture_mode" = blocks ]; then
                                copied=0
                                while [ "${'$'}copied" -lt "${'$'}cap" ]; do
                                    block=${'$'}((cap - copied)); [ "${'$'}block" -le 4096 ] || block=4096
                                    dd bs="${'$'}block" count=1 2>/dev/null || { printf failed > "${'$'}request/${'$'}channel.failed"; break; }
                                    size=${'$'}(wc -c < "${'$'}request/${'$'}target") || { printf failed > "${'$'}request/${'$'}channel.failed"; break; }
                                    [ "${'$'}size" -gt "${'$'}copied" ] || break
                                    copied=${'$'}size
                                done
                            else dd bs=1 count="${'$'}cap" 2>/dev/null || printf failed > "${'$'}request/${'$'}channel.failed"
                            fi
                            cat >/dev/null || printf failed > "${'$'}request/${'$'}channel.failed"
                            printf done > "${'$'}request/${'$'}channel.done"
                        ' sh "${'$'}request" "${'$'}channel" "${'$'}cap" "${'$'}capture_mode" "${'$'}target" 3>&- 4>&- < "${'$'}request/${'$'}channel.pipe" > "${'$'}request/${'$'}target" &
                    done
                    setsid sh -c '
                        request=${'$'}1; foreground_umask=${'$'}2
                        IFS= read -r stat < /proc/${'$'}${'$'}/stat || exit 125
                        rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19
                        printf "%s %s\n" "${'$'}${'$'}" "${'$'}1" > "${'$'}request/pid"
                        [ -f "${'$'}request/active" ] || exit 125
                        IFS= read -r foreground < "${'$'}request/foreground" || exit 125
                        # Preserve foreground sh -c/umask semantics and every trailing newline.
                        # User commands cannot inherit or keep the control FIFO open.
                        if [ "${'$'}foreground" = 1 ]; then
                            umask "${'$'}foreground_umask"
                            payload=${'$'}(cat "${'$'}request/command" && printf .) || exit 125
                            payload=${'$'}{payload%.}
                            [ -f "${'$'}request/active" ] || exit 125
                            sh -c "${'$'}payload" 3>&- 4>&-
                        else sh "${'$'}request/command" 3>&- 4>&-
                        fi
                        exit_code=${'$'}?
                        printf "%s\n" "${'$'}exit_code" > "${'$'}request/exit"
                        printf "%s\n" "${'$'}exit_code" >&3
                        if [ "${'$'}foreground" = 1 ]; then
                            # Retain group identity until the guardian chooses normal release or cancellation.
                            IFS= read -r released <&4
                            exit "${'$'}exit_code"
                        else while :; do sleep 1; done
                        fi
                    ' sh "${'$'}request" "${'$'}original_umask" 3<&3 4<&4 > "${'$'}request/out.pipe" 2> "${'$'}request/err.pipe" &
                    IFS= read -r ticks < "${'$'}request/ticks"
                    outcome=timeout
                    while [ "${'$'}ticks" -gt 0 ]; do
                        [ -f "${'$'}request/active" ] || { outcome=cancelled; break; }
                        same_process "${'$'}broker" "${'$'}broker_started" && same_process "${'$'}app_pid" "${'$'}app_started" || { outcome=cancelled; break; }
                        if [ "${'$'}timed_read" = yes ]; then
                            if IFS= read -r -t 0.05 completed <&3; then outcome=${'$'}completed; break; fi
                        else
                            if [ -s "${'$'}request/exit" ]; then IFS= read -r outcome < "${'$'}request/exit" || outcome=cleanup_unconfirmed; break; fi
                            sleep 0.05
                        fi
                        ticks=${'$'}((ticks - 1))
                    done
                    # A late completion cannot override cancellation/the app's expired deadline.
                    [ -f "${'$'}request/active" ] || outcome=cancelled
                    rm -f "${'$'}request/active"
                    # A late leader cannot run after active is removed. Allow bounded publication.
                    count=20
                    while [ ! -s "${'$'}request/pid" ] && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                    IFS= read -r foreground < "${'$'}request/foreground"
                    # mksh treats symbolic numeric-test operands as arithmetic names; only release real exit codes.
                    case "${'$'}foreground:${'$'}outcome" in
                        1:[0-9]|1:[1-9][0-9]|1:1[0-9][0-9]|1:2[0-4][0-9]|1:25[0-5])
                            # Ordinary sh -c historically leaves explicit background launches alive after exit.
                            release_group "${'$'}request/pid" || { stop_group "${'$'}request/pid"; outcome=cleanup_unconfirmed; }
                            ;;
                        *) stop_group "${'$'}request/pid" || outcome=cleanup_unconfirmed ;;
                    esac
                    exec 3>&-
                    exec 4>&-
                    # Allow delayed finite output to drain; descendants can hold the pipe indefinitely.
                    count=150
                    while { [ ! -f "${'$'}request/out.done" ] || [ ! -f "${'$'}request/err.done" ]; } && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                    incomplete=no
                    for channel in out err; do
                        if [ ! -f "${'$'}request/${'$'}channel.done" ]; then
                            if stop_group "${'$'}request/${'$'}channel.pid"; then
                                [ -f "${'$'}request/${'$'}channel.done" ] || incomplete=yes
                            else
                                # A completed drain can disappear between the marker check and
                                # stop_group; the marker alone does not prove the leader exited.
                                identity="${'$'}request/${'$'}channel.pid"
                                if [ -f "${'$'}request/${'$'}channel.done" ] && [ -s "${'$'}identity" ] &&
                                    read -r drain_pid drain_started < "${'$'}identity"; then
                                    case "${'$'}drain_pid:${'$'}drain_started" in
                                        *[!0-9:]*|:*) outcome=cleanup_unconfirmed ;;
                                        *:) outcome=cleanup_unconfirmed ;;
                                        *)
                                            if [ "${'$'}drain_pid" -gt 1 ] 2>/dev/null && [ "${'$'}drain_started" -gt 0 ] 2>/dev/null; then
                                                same_process "${'$'}drain_pid" "${'$'}drain_started" && outcome=cleanup_unconfirmed
                                            else
                                                outcome=cleanup_unconfirmed
                                            fi ;;
                                    esac
                                else
                                    outcome=cleanup_unconfirmed
                                fi
                            fi
                        fi
                        [ ! -f "${'$'}request/${'$'}channel.failed" ] || incomplete=yes
                    done
                    if [ "${'$'}incomplete" = yes ]; then
                        case "${'$'}outcome" in
                            [0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5]) outcome="output_incomplete:${'$'}outcome" ;;
                        esac
                    fi
                    rm -f "${'$'}request/out.pipe" "${'$'}request/err.pipe" "${'$'}request/completed.pipe" "${'$'}request/release.pipe"
                    finish "${'$'}outcome"
                ) </dev/null >/dev/null 2>&1 & guardian=${'$'}!
                wait "${'$'}guardian"
            done
            """.trimIndent()
        }
    }
}
