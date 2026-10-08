package me.rerere.rikkahub.data.ai.tools.local

import java.io.File

internal enum class CameraCapturePhase { NEW, INITIALIZING, CAPTURING, SUSPENDED, FALLBACK_READY, EXTERNAL, FINISHED }

internal data class CameraCaptureFailure(val stage: String, val code: String, val elapsedMs: Long, val camera: String = "unknown")
internal data class CameraCaptureAttempt(val id: Long, val file: File)

/** Lives on the approved request, independently of any one Android Activity. */
internal class CameraCaptureState(private val output: File) {
    var phase = CameraCapturePhase.NEW
        private set
    var failure: CameraCaptureFailure? = null
        private set
    var externalAttempt: CameraCaptureAttempt? = null
        private set
    var selectedCamera: String = "unknown"
        private set
    private var host = 0L
    private var sequence = 0L
    private var activeAttempt: CameraCaptureAttempt? = null
    private var activeOwner = 0L
    private var captureWasSuspended = false
    private var acceptedFile: File? = null
    private var cancelCaptureDeadline: (() -> Unit)? = null
    private var releaseHost: (() -> Unit)? = null
    private var closeHost: (() -> Unit)? = null
    private var changedHost: (() -> Unit)? = null
    private val files = mutableSetOf(output)

    @Synchronized fun attachHost(release: () -> Unit, close: () -> Unit, changed: () -> Unit = {}): Long {
        suspendHost(host)
        host++
        releaseHost = release
        closeHost = close
        changedHost = changed
        return host
    }

    @Synchronized fun ownsHost(owner: Long): Boolean = owner == host

    @Synchronized fun beginCamera(owner: Long): CameraCaptureAttempt? {
        if (owner != host || phase !in listOf(CameraCapturePhase.NEW, CameraCapturePhase.SUSPENDED)) return null
        val attempt = newAttempt()
        activeAttempt = attempt
        activeOwner = owner
        captureWasSuspended = false
        selectedCamera = "unknown"
        phase = CameraCapturePhase.INITIALIZING
        return attempt
    }

    @Synchronized fun isCurrent(owner: Long, attempt: Long, expected: CameraCapturePhase): Boolean =
        owner == host && activeAttempt?.id == attempt && phase == expected

    @Synchronized fun beginCapture(owner: Long, attempt: Long): Boolean {
        if (!isCurrent(owner, attempt, CameraCapturePhase.INITIALIZING)) return false
        phase = CameraCapturePhase.CAPTURING
        return true
    }

    @Synchronized fun selectCamera(owner: Long, attempt: Long, camera: String) {
        if (isCurrent(owner, attempt, CameraCapturePhase.INITIALIZING)) selectedCamera = camera
    }

    @Synchronized fun setCaptureDeadline(owner: Long, attempt: Long, cancel: () -> Unit) {
        if (!isCapturePending(owner, attempt)) { cancel(); return }
        clearCaptureDeadline()
        cancelCaptureDeadline = cancel
    }

    @Synchronized fun isCapturePending(owner: Long, attempt: Long): Boolean =
        activeOwner == owner && activeAttempt?.id == attempt && phase == CameraCapturePhase.CAPTURING

    @Synchronized fun completePhoto(owner: Long, attempt: Long): Boolean {
        if (!isCapturePending(owner, attempt)) return false
        acceptedFile = activeAttempt?.file
        phase = CameraCapturePhase.FINISHED
        clearCaptureDeadline()
        releaseHost?.invoke()
        changedHost?.invoke()
        return true
    }

    @Synchronized fun offerFallback(owner: Long, attempt: Long, reason: CameraCaptureFailure): Boolean {
        val capturing = isCapturePending(owner, attempt)
        if (!capturing && !isCurrent(owner, attempt, CameraCapturePhase.INITIALIZING)) return false
        // Unbinding on pause may interrupt a save; wait for its terminal callback before retrying.
        phase = if (capturing && captureWasSuspended) CameraCapturePhase.SUSPENDED else CameraCapturePhase.FALLBACK_READY
        activeAttempt = null
        failure = reason
        clearCaptureDeadline()
        releaseHost?.invoke()
        changedHost?.invoke()
        return true
    }

    @Synchronized fun beginExternal(owner: Long): CameraCaptureAttempt? {
        if (owner != host || phase != CameraCapturePhase.FALLBACK_READY) return null
        return newAttempt().also {
            externalAttempt = it
            phase = CameraCapturePhase.EXTERNAL
        }
    }

    @Synchronized fun completeExternal(owner: Long): Boolean {
        if (owner != host || phase != CameraCapturePhase.EXTERNAL) return false
        acceptedFile = externalAttempt?.file
        phase = CameraCapturePhase.FINISHED
        releaseHost?.invoke()
        return true
    }

    @Synchronized fun suspendHost(owner: Long) {
        if (owner != host || phase == CameraCapturePhase.FINISHED || phase == CameraCapturePhase.EXTERNAL) return
        if (phase == CameraCapturePhase.INITIALIZING) {
            phase = CameraCapturePhase.SUSPENDED
            activeAttempt = null
        }
        if (phase == CameraCapturePhase.CAPTURING) captureWasSuspended = true
        releaseHost?.invoke()
    }

    @Synchronized fun detachHost(owner: Long) {
        if (owner != host) return
        suspendHost(owner)
        releaseHost = null
        closeHost = null
        changedHost = null
    }

    @Synchronized fun finishError() {
        if (phase == CameraCapturePhase.FINISHED) return
        phase = CameraCapturePhase.FINISHED
        activeAttempt = null
        clearCaptureDeadline()
        releaseHost?.invoke()
    }

    @Synchronized fun cancel() {
        if (phase != CameraCapturePhase.FINISHED) {
            phase = CameraCapturePhase.FINISHED
            activeAttempt = null
            clearCaptureDeadline()
            releaseHost?.invoke()
            closeCurrentHost()
        }
        cleanupFiles()
    }

    @Synchronized fun cleanupFiles() { files.forEach { it.delete() } }

    @Synchronized fun discardAttempt(attempt: CameraCaptureAttempt) {
        if (attempt.file in files && attempt.file != acceptedFile) attempt.file.delete()
    }

    @Synchronized fun closeCurrentHost() {
        closeHost?.invoke()
        releaseHost = null
        closeHost = null
        changedHost = null
    }

    private fun clearCaptureDeadline() {
        val cancel = cancelCaptureDeadline
        cancelCaptureDeadline = null
        cancel?.invoke()
    }

    private fun newAttempt(): CameraCaptureAttempt = CameraCaptureAttempt(
        ++sequence, File.createTempFile("capture_", ".jpg", output.parentFile).also { files.add(it) },
    )
}
