package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.CameraCaptureAttempt
import me.rerere.rikkahub.data.ai.tools.local.CameraCaptureFailure
import me.rerere.rikkahub.data.ai.tools.local.CameraCapturePhase
import me.rerere.rikkahub.data.ai.tools.local.CameraCaptureState
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiRequest
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiResult
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiSession
import me.rerere.rikkahub.data.ai.tools.local.sharedPersonalSessions
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import java.lang.ref.WeakReference

/** Only a live, approved request ID can open this non-exported host. */
class CameraToolActivity : ComponentActivity() {
    private var session: PersonalUiSession? = null
    private var captureState: CameraCaptureState? = null
    private var host = 0L
    private var stageStartedAt = 0L
    private var deadline: Job? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var boundCameraState: androidx.lifecycle.LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var streamObserver: Observer<PreviewView.StreamState>? = null
    private lateinit var previewView: PreviewView
    private var displayedPhase by mutableStateOf(CameraCapturePhase.NEW)

    private val externalCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val state = captureState ?: return@registerForActivityResult
        val attempt = state.externalAttempt ?: return@registerForActivityResult
        revokeExternalAccess()
        if (state.phase != CameraCapturePhase.EXTERNAL) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            cancel()
        } else if (!attempt.file.isFile || attempt.file.length() !in 1..MAX_CAPTURE_BYTES) {
            completeError("Системная камера не создала допустимое фото. [external:empty_or_large]")
        } else if (state.completeExternal(host)) {
            complete(PersonalUiResult.Photo(attempt.file, "system_camera_fallback", state.failure))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(PersonalToolActivity.EXTRA_REQUEST_ID) ?: run { finish(); return }
        val current = sharedPersonalSessions.get(id)
        val request = current?.request as? PersonalUiRequest.Camera
        if (current == null || request == null || !sharedPersonalSessions.claim(id)) { finish(); return }
        session = current
        captureState = request.captureState
        host = request.captureState.attachHost(::releaseCamera, { revokeExternalAccess(); finish() }, ::onCaptureStateChanged)
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        displayedPhase = request.captureState.phase
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                BackHandler { cancel() }
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.personal_tools_camera_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (displayedPhase == CameraCapturePhase.FALLBACK_READY || displayedPhase == CameraCapturePhase.EXTERNAL)
                            R.string.personal_tools_camera_fallback_notice else R.string.personal_tools_camera_auto_notice))
                        if (displayedPhase != CameraCapturePhase.EXTERNAL) {
                            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
                        }
                        Text(stringResource(when (displayedPhase) {
                            CameraCapturePhase.CAPTURING -> R.string.personal_tools_camera_capturing
                            CameraCapturePhase.SUSPENDED -> R.string.personal_tools_camera_paused
                            else -> R.string.personal_tools_camera_initializing
                        }))
                        if (displayedPhase == CameraCapturePhase.INITIALIZING || displayedPhase == CameraCapturePhase.CAPTURING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { cancel() }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) }
                    }
                }
            }
        }
        lifecycleScope.launch { current.await(); finish() }
    }

    private fun onCaptureStateChanged() {
        val state = captureState ?: return
        displayedPhase = state.phase
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || session?.let { sharedPersonalSessions.isActive(it.id) } != true) return
        when (state.phase) {
            CameraCapturePhase.SUSPENDED -> startCamera()
            CameraCapturePhase.FALLBACK_READY -> launchExternalCamera()
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        val current = session ?: return
        val state = captureState ?: return
        if (!sharedPersonalSessions.isActive(current.id)) { finish(); return }
        if (!hasCameraPermission()) { completeError("Доступ к камере отозван. Разрешите камеру в настройках функции."); return }
        displayedPhase = state.phase
        when (state.phase) {
            CameraCapturePhase.NEW, CameraCapturePhase.SUSPENDED -> startCamera()
            CameraCapturePhase.FALLBACK_READY -> launchExternalCamera()
            CameraCapturePhase.FINISHED -> finish()
            else -> Unit
        }
    }

    private fun startCamera() {
        val state = captureState ?: return
        val attempt = try { state.beginCamera(host) } catch (_: Exception) {
            completeError("Не удалось создать временный файл снимка. [initializing:output_failed]"); return
        } ?: return
        displayedPhase = state.phase
        stageStartedAt = SystemClock.elapsedRealtime()
        setDeadline(attempt, CameraCapturePhase.INITIALIZING, INITIALIZATION_TIMEOUT_MS)
        try {
            val future = ProcessCameraProvider.getInstance(this)
            val weakHost = WeakReference(this)
            future.addListener({
                weakHost.get()?.bindCamera(attempt) { future.get() }
            }, ContextCompat.getMainExecutor(applicationContext))
        } catch (error: Exception) {
            failCamera(attempt, "initializing", "provider_${error.javaClass.simpleName}")
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(attempt: CameraCaptureAttempt, getProvider: () -> ProcessCameraProvider) {
        val state = captureState ?: return
        if (!isCurrent(attempt, CameraCapturePhase.INITIALIZING)) return
        try {
            val cameraProvider = getProvider()
            val selector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> { failCamera(attempt, "initializing", "no_camera"); return }
            }
            state.selectCamera(host, attempt.id, if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front")
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build())
                .build()
            val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider = cameraProvider
            preview = cameraPreview
            imageCapture = capture
            val camera = cameraProvider.bindToLifecycle(this, selector, cameraPreview, capture)
            val cameraId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull()
            if (cameraId != null) state.selectCamera(host, attempt.id, "${state.selectedCamera}:$cameraId")
            val liveState = camera.cameraInfo.cameraState
            boundCameraState = liveState
            val errorObserver = Observer<CameraState> { cameraState ->
                cameraState.error?.let { error ->
                    failCamera(attempt, if (state.phase == CameraCapturePhase.CAPTURING) "capturing" else "initializing", "camera_${error.code}")
                }
            }
            cameraObserver = errorObserver
            liveState.observe(this, errorObserver)
            if (!isCurrent(attempt, CameraCapturePhase.INITIALIZING)) return
            val readyObserver = Observer<PreviewView.StreamState> { stream ->
                if (stream == PreviewView.StreamState.STREAMING) captureOnce(attempt, capture)
            }
            streamObserver = readyObserver
            previewView.previewStreamState.observe(this, readyObserver)
        } catch (error: Exception) {
            failCamera(attempt, "initializing", "bind_${error.javaClass.simpleName}")
        }
    }

    private fun captureOnce(attempt: CameraCaptureAttempt, capture: ImageCapture) {
        val state = captureState ?: return
        if (!isCurrent(attempt, CameraCapturePhase.INITIALIZING) || !state.beginCapture(host, attempt.id)) return
        displayedPhase = state.phase
        stageStartedAt = SystemClock.elapsedRealtime()
        deadline?.cancel()
        deadline = null
        val owner = host
        val sessionId = session?.id ?: return
        val startedAt = stageStartedAt
        val cameraLabel = state.selectedCamera
        val saveTimeout = cameraCompletionScope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            captureFailed(state, owner, attempt, "timeout", startedAt, cameraLabel)
        }
        state.setCaptureDeadline(owner, attempt.id) { saveTimeout.cancel() }
        try {
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(attempt.file).build(), ContextCompat.getMainExecutor(applicationContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (!sharedPersonalSessions.isActive(sessionId) || !state.isCapturePending(owner, attempt.id)) {
                            state.discardAttempt(attempt); return
                        }
                        if (!attempt.file.isFile || attempt.file.length() !in 1..MAX_CAPTURE_BYTES) {
                            captureFailed(state, owner, attempt, "empty_or_large", startedAt, cameraLabel); return
                        }
                        if (state.completePhoto(owner, attempt.id)) {
                            deliverResult(sessionId, state, PersonalUiResult.Photo(attempt.file, "camerax", state.failure, cameraLabel))
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        captureFailed(state, owner, attempt, "capture_${exception.imageCaptureError}", startedAt, cameraLabel)
                        state.discardAttempt(attempt)
                    }
                },
            )
        } catch (error: Exception) { captureFailed(state, owner, attempt, "capture_${error.javaClass.simpleName}", startedAt, cameraLabel) }
    }

    private fun setDeadline(attempt: CameraCaptureAttempt, phase: CameraCapturePhase, timeoutMs: Long) {
        deadline?.cancel()
        deadline = lifecycleScope.launch {
            delay(timeoutMs)
            if (isCurrent(attempt, phase)) failCamera(attempt, phase.name.lowercase(), "timeout")
        }
    }

    private fun isCurrent(attempt: CameraCaptureAttempt, phase: CameraCapturePhase): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            session?.let { sharedPersonalSessions.isActive(it.id) } == true &&
            captureState?.isCurrent(host, attempt.id, phase) == true

    private fun failCamera(attempt: CameraCaptureAttempt, stage: String, code: String) {
        val state = captureState ?: return
        val elapsed = (SystemClock.elapsedRealtime() - stageStartedAt).coerceAtLeast(0)
        state.offerFallback(host, attempt.id, CameraCaptureFailure(stage, code, elapsed, state.selectedCamera))
    }

    private fun launchExternalCamera() {
        val state = captureState ?: return
        if (!hasCameraPermission()) { completeError("Доступ к камере отозван."); return }
        val attempt = try { state.beginExternal(host) } catch (_: Exception) {
            completeError("Не удалось создать файл для системной камеры. [external:output_failed]"); return
        } ?: return
        displayedPhase = state.phase
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", attempt.file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newRawUri("camera-output", uri)
            }
            if (intent.resolveActivity(packageManager) == null) {
                completeError("В Android нет доступного приложения камеры. [external:no_handler]"); return
            }
            externalCamera.launch(intent)
        } catch (error: Exception) {
            completeError("Не удалось открыть системную камеру. [external:${error.javaClass.simpleName}]")
        }
    }

    private fun releaseCamera() {
        deadline?.cancel()
        deadline = null
        cameraObserver?.let { boundCameraState?.removeObserver(it) }
        streamObserver?.let { if (::previewView.isInitialized) previewView.previewStreamState.removeObserver(it) }
        cameraObserver = null
        streamObserver = null
        boundCameraState = null
        val ownedUseCases = listOfNotNull<UseCase>(preview, imageCapture)
        runCatching { if (ownedUseCases.isNotEmpty()) provider?.unbind(*ownedUseCases.toTypedArray()) }
        preview = null
        imageCapture = null
        provider = null
    }

    private fun revokeExternalAccess() {
        captureState?.externalAttempt?.file?.let { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun completeError(message: String) {
        captureState?.finishError()
        complete(PersonalUiResult.Error(message))
    }

    private fun complete(result: PersonalUiResult) {
        releaseCamera()
        revokeExternalAccess()
        val current = session ?: run { result.destroy(); finish(); return }
        val state = captureState ?: run { result.destroy(); finish(); return }
        deliverResult(current.id, state, result)
    }

    private fun cancel() {
        val current = session ?: return
        cameraCompletionScope.launch(start = CoroutineStart.UNDISPATCHED) { sharedPersonalSessions.cancel(current.id); finish() }
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        captureState?.suspendHost(host)
        captureState?.let { displayedPhase = it.phase }
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && captureState?.ownsHost(host) == true) cancel()
        captureState?.detachHost(host)
        releaseCamera()
        super.onDestroy()
    }

    companion object {
        private val cameraCompletionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private const val INITIALIZATION_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 15_000L
        private const val MAX_CAPTURE_BYTES = 33_554_432L

        private fun captureFailed(state: CameraCaptureState, owner: Long, attempt: CameraCaptureAttempt, code: String, startedAt: Long, camera: String) {
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
            state.offerFallback(owner, attempt.id, CameraCaptureFailure("capturing", code, elapsed, camera))
        }

        private fun deliverResult(id: String, state: CameraCaptureState, result: PersonalUiResult) {
            cameraCompletionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sharedPersonalSessions.complete(id, result)
                state.closeCurrentHost()
            }
        }
    }
}
