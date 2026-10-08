package me.rerere.rikkahub.root

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val headlessLock = Mutex()
    private val sessionStateLock = Any()
    private var sessionEpoch = 0L
    @Volatile private var headlessSession: HeadlessRootSession? = null

    /** May open the root manager's consent dialog. UI calls this only after a user tap. */
    suspend fun verifyRoot(force: Boolean = false, prepareHeadless: Boolean = false): RootStatus {
        val epoch = synchronized(sessionStateLock) { sessionEpoch }
        val verified = verifyRootProbe(force)
        if (verified != RootStatus.READY) close()
        if (prepareHeadless && verified == RootStatus.READY) {
            var prepared = false
            try {
                headlessLock.withLock {
                    // This opt-in is reserved for the foreground verification button. exec never prepares it.
                    val old = synchronized(sessionStateLock) {
                        if (epoch != sessionEpoch) return@withLock
                        headlessSession.also { headlessSession = null }
                    }
                    old?.close()
                    var candidate: HeadlessRootSession? = null
                    var installed = false
                    try {
                        runInterruptible(Dispatchers.IO) {
                            candidate = HeadlessRootSession.open(suExecutable, controlDirectory, probeTimeoutMs)
                        }
                        installed = synchronized(sessionStateLock) {
                            if (epoch == sessionEpoch && candidate?.isAlive() == true) {
                                headlessSession = candidate
                                true
                            } else false
                        }
                        prepared = installed
                    } finally {
                        // Cancellation on dispatcher handoff must not leak a newly authenticated root shell.
                        if (!installed) candidate?.close()
                    }
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
            val probe = runInterruptible(Dispatchers.IO) {
                RootProcessRunner.run(listOf(suExecutable, "-c", "id -u"), probeTimeoutMs, 512, 1_024)
            }
            val verified = when {
                probe.error == "exec_failed" -> RootStatus.UNAVAILABLE
                probe.error == "command_timeout" -> RootStatus.TIMED_OUT
                probe.exitCode != 0 -> RootStatus.DENIED_OR_FAILED
                probe.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).lastOrNull() != "0" -> RootStatus.NOT_ROOT
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

    internal suspend fun exec(command: String, timeoutMs: Int, headless: Boolean = false, mayExecute: suspend () -> Boolean = { true }): RootProcessResult {
        if (headless) return headlessLock.withLock {
            if (!mayExecute()) return@withLock approvalRequired()
            val session = headlessSession ?: return@withLock interactionRequired()
            withContext(Dispatchers.IO) {
                if (!mayExecute()) return@withContext approvalRequired()
                runInterruptible { session.execute(command, timeoutMs) }
            }
        }
        val verified = verifyRoot()
        if (verified != RootStatus.READY) {
            return RootProcessResult(
                error = when (verified) {
                    RootStatus.UNAVAILABLE -> "su_not_available"
                    RootStatus.TIMED_OUT -> "root_verification_timeout"
                    else -> "root_not_granted"
                },
                reason = "Root verification: ${verified.name.lowercase()}. Check the device root manager and verify root again.",
            )
        }
        if (!mayExecute()) return approvalRequired()
        // The original command is shell-quoted as one argument inside the owned process-group wrapper.
        // Even an unsuccessful/timed-out launch is returned once, never replayed/fallen back.
        val result = withContext(Dispatchers.IO) {
            if (!mayExecute()) return@withContext approvalRequired()
            runInterruptible {
                RootCommandSession(suExecutable, controlDirectory).use { session ->
                    val result = RootProcessRunner.run(
                        listOf(suExecutable, "-c", session.wrap(command)),
                        timeoutMs,
                        beforeStop = { session.cancel() },
                    )
                    if (session.rootDenied()) {
                        result.copy(error = "root_not_granted", reason = "The new su execution did not have UID 0. The command was not executed; verify root again.")
                    } else if (session.capabilityUnavailable()) {
                        result.copy(error = "root_cleanup_unavailable", reason = "setsid is required for root command cancellation. The command was not executed.")
                    } else {
                        result.copy(reason = session.cleanupError ?: result.reason)
                    }
                }
            }
        }
        if (result.error == "root_not_granted") probeLock.withLock {
            cachedStatus = RootStatus.NOT_ROOT
            verifiedAtMs = clockMs()
            _status.value = RootStatus.NOT_ROOT
        }
        return result
    }

    fun isHeadlessReady(): Boolean = headlessSession?.isAlive() == true

    /** Drop the foreground-authenticated transport; headless calls never reconnect it. */
    fun close() {
        val old = synchronized(sessionStateLock) {
            sessionEpoch++
            headlessSession.also { headlessSession = null }
        }
        old?.close()
    }

    private fun interactionRequired() = RootProcessResult(
        error = "root_interaction_required",
        reason = "Откройте настройки Root и нажмите проверку Root для фоновой работы. После перезапуска приложения или устройства требуется новая проверка; команда не повторяется.",
    )

    private fun approvalRequired() = RootProcessResult(
        error = "root_approval_required",
        reason = "Automatic root access was disabled before launch. Request approval for a new call.",
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

    fun execute(command: String, timeoutMs: Int): RootProcessResult {
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
            val outcome = acknowledgement(result, nonce) ?: return quarantine()
            return when (outcome) {
                "timeout", "cancelled" -> RootProcessResult(error = "command_timeout", stdout = output(request, "stdout", 8_000), stderr = output(request, "stderr", 2_000))
                "not_root", "failed" -> { close(); interactionRequired() }
                else -> outcome.toIntOrNull()?.takeIf { it in 0..255 }?.let { code ->
                    RootProcessResult(exitCode = code, stdout = output(request, "stdout", 8_000), stderr = output(request, "stderr", 2_000))
                } ?: quarantine()
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
            if (submitted) { awaitCleanup(result); return quarantine() }
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
        return outcome.takeIf { it in setOf("timeout", "cancelled", "not_root", "failed") || it.toIntOrNull()?.let { code -> code in 0..255 } == true }
    }

    private fun quarantine(): RootProcessResult {
        retainControl = true
        close()
        return RootProcessResult(error = "root_cleanup_unconfirmed", reason = "Завершение Root-команды не подтверждено. Фоновая сессия закрыта; проверьте процессы на устройстве. Команда не повторяется.")
    }

    private fun awaitCleanup(result: File) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!result.isFile && System.nanoTime() < deadline) {
            try { Thread.sleep(10) } catch (_: InterruptedException) { /* bounded cleanup */ }
        }
    }

    private fun output(request: File, name: String, limit: Int): String {
        val file = File(request, name)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return ""
        val bytes = readPrefix(file, limit + 4)
        if (bytes.size <= limit) return bytes.toString(Charsets.UTF_8)
        var keep = limit
        while (keep > 0 && bytes[keep].toInt() and 0xc0 == 0x80) keep--
        return String(bytes, 0, keep, Charsets.UTF_8) + "\n…[truncated]"
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
        runCatching { if (process.isAlive) process.destroyForcibly() }
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
            umask 077
            base=${quote(directory.path)}
            app_pid=$appPid
            app_started=$appStarted
            [ "${'$'}(id -u)" = 0 ] || exit 126
            for capability in setsid mkfifo dd cat sleep mv rm; do command -v "${'$'}capability" >/dev/null 2>&1 || exit 127; done
            broker=${'$'}${'$'}
            stat=${'$'}(cat /proc/${'$'}${'$'}/stat) || exit 125
            rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19; broker_started=${'$'}1
            same_process() {
                check_pid=${'$'}1; check_started=${'$'}2
                check_stat=${'$'}(cat "/proc/${'$'}check_pid/stat" 2>/dev/null) || return 1
                check_rest=${'$'}{check_stat##*) }; set -- ${'$'}check_rest
                [ "${'$'}1" != Z ] || return 1
                shift 19; [ "${'$'}1" = "${'$'}check_started" ]
            }
            stop_group() {
                identity=${'$'}1
                [ -s "${'$'}identity" ] || return 1
                read -r group_pid group_started < "${'$'}identity"
                group_stat=${'$'}(cat "/proc/${'$'}group_pid/stat" 2>/dev/null) || return 1
                group_rest=${'$'}{group_stat##*) }; set -- ${'$'}group_rest
                [ "${'$'}3" = "${'$'}group_pid" ] && [ "${'$'}4" = "${'$'}group_pid" ] || return 1
                group_state=${'$'}1; shift 19
                [ "${'$'}1" = "${'$'}group_started" ] || return 1
                kill -KILL -"${'$'}group_pid" 2>/dev/null || return 1
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
                    mkfifo "${'$'}request/out.pipe" "${'$'}request/err.pipe" || { finish failed; exit; }
                    # Independent drain groups can be stopped even if an escaped command retains a FIFO.
                    for channel in out err; do
                        case "${'$'}channel" in out) cap=8004; target=stdout;; err) cap=2004; target=stderr;; esac
                        setsid sh -c '
                            request=${'$'}1; channel=${'$'}2; cap=${'$'}3
                            stat=${'$'}(cat /proc/${'$'}${'$'}/stat) || exit 125
                            rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19
                            printf "%s %s\n" "${'$'}${'$'}" "${'$'}1" > "${'$'}request/${'$'}channel.pid"
                            dd bs=1 count="${'$'}cap" 2>/dev/null; cat >/dev/null
                            printf done > "${'$'}request/${'$'}channel.done"
                        ' sh "${'$'}request" "${'$'}channel" "${'$'}cap" < "${'$'}request/${'$'}channel.pipe" > "${'$'}request/${'$'}target" &
                    done
                    setsid sh -c '
                        request=${'$'}1
                        stat=${'$'}(cat /proc/${'$'}${'$'}/stat) || exit 125
                        rest=${'$'}{stat##*) }; set -- ${'$'}rest; shift 19
                        printf "%s %s\n" "${'$'}${'$'}" "${'$'}1" > "${'$'}request/pid"
                        [ -f "${'$'}request/active" ] || exit 125
                        sh "${'$'}request/command"
                        printf "%s" "${'$'}?" > "${'$'}request/exit"
                        while :; do sleep 1; done
                    ' sh "${'$'}request" > "${'$'}request/out.pipe" 2> "${'$'}request/err.pipe" &
                    ticks=${'$'}(cat "${'$'}request/ticks")
                    outcome=timeout
                    while [ "${'$'}ticks" -gt 0 ]; do
                        [ -f "${'$'}request/active" ] || { outcome=cancelled; break; }
                        same_process "${'$'}broker" "${'$'}broker_started" && same_process "${'$'}app_pid" "${'$'}app_started" || { outcome=cancelled; break; }
                        if [ -s "${'$'}request/exit" ]; then outcome=${'$'}(cat "${'$'}request/exit"); break; fi
                        ticks=${'$'}((ticks - 1))
                        sleep 0.05
                    done
                    rm -f "${'$'}request/active"
                    # A late leader cannot run the command after active is removed. Allow bounded publication.
                    count=20
                    while [ ! -s "${'$'}request/pid" ] && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                    stop_group "${'$'}request/pid" || outcome=cleanup_unconfirmed
                    # Let finite queued output drain, then kill exact drain groups; never use an unbounded wait.
                    count=20
                    while { [ ! -f "${'$'}request/out.done" ] || [ ! -f "${'$'}request/err.done" ]; } && [ "${'$'}count" -gt 0 ]; do sleep 0.01; count=${'$'}((count - 1)); done
                    for channel in out err; do
                        [ -f "${'$'}request/${'$'}channel.done" ] || stop_group "${'$'}request/${'$'}channel.pid" || outcome=cleanup_unconfirmed
                    done
                    rm -f "${'$'}request/out.pipe" "${'$'}request/err.pipe"
                    finish "${'$'}outcome"
                ) </dev/null >/dev/null 2>&1 & guardian=${'$'}!
                wait "${'$'}guardian"
            done
            """.trimIndent()
        }
    }
}
