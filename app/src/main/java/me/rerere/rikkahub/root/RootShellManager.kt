package me.rerere.rikkahub.root

import java.io.File
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

    /** May open the root manager's consent dialog. UI calls this only after a user tap. */
    suspend fun verifyRoot(force: Boolean = false): RootStatus = probeLock.withLock {
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

    internal suspend fun exec(command: String, timeoutMs: Int, mayExecute: suspend () -> Boolean = { true }): RootProcessResult {
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

    private fun approvalRequired() = RootProcessResult(
        error = "root_approval_required",
        reason = "Automatic root access was disabled before launch. Request approval for a new call.",
    )
}
