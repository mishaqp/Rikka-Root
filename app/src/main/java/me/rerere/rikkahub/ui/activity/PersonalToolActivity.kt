// Camera/biometric host adapted from ExTV/rikkahub-agent, local/ToolHostActivity.kt (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/** The non-exported activity accepts only a random ID for a live in-memory approved request. */
class PersonalToolActivity : FragmentActivity() {
    private var session: PersonalUiSession?=null
    private var worker: Job?=null
    private var biometric: BiometricPrompt?=null
    private var launched=false
    private var waitingExternal=false
    private var cameraUri: Uri?=null
    private var elapsedMs by mutableIntStateOf(0)
    private var speechStatus by mutableStateOf("")

    private val camera=registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        waitingExternal=false
        revokeCameraAccess()
        val current=session ?: return@registerForActivityResult
        val request=current.request as? PersonalUiRequest.Camera ?: return@registerForActivityResult
        if (success) complete(PersonalUiResult.Photo(request.output)) else cancel()
    }
    private val credential=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waitingExternal=false
        if (result.resultCode == Activity.RESULT_OK) complete(PersonalUiResult.Authentication("device_credential")) else cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getStringExtra(EXTRA_REQUEST_ID) ?: run { finish(); return }
        val current=sharedPersonalSessions.get(id)
        if (current == null || !sharedPersonalSessions.claim(id)) { finish(); return }
        session=current
        if (current.request is PersonalUiRequest.Encrypt || current.request is PersonalUiRequest.Reveal) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Manifest <activity> cannot set View importance; exclude the actual tree.
            window.decorView.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (!sharedPersonalSessions.setCancellationAction(id) {
                val running=withContext(Dispatchers.Main.immediate) {
                    worker?.cancel(); runCatching { biometric?.cancelAuthentication() }; revokeCameraAccess(); finish(); worker
                }
                running?.join()
            }) { finish(); return }
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                BackHandler { cancel() }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(title(current.request)), style=MaterialTheme.typography.titleLarge)
                        when (val request=current.request) {
                            is PersonalUiRequest.Encrypt, is PersonalUiRequest.Reveal -> CompositionLocalProvider(LocalAutofillManager provides null) {
                                ProtectedSecretScreen(request, onSubmit={ complete(PersonalUiResult.Secret(it)) }, onClose={ complete(PersonalUiResult.Acknowledged) })
                            }
                            is PersonalUiRequest.Record -> {
                                Text(stringResource(R.string.personal_tools_recording_notice))
                                LinearProgressIndicator(progress={ elapsedMs.toFloat()/request.durationMs }, modifier=Modifier.fillMaxWidth())
                                Text(stringResource(R.string.personal_tools_recording_progress,elapsedMs/1000,request.durationMs/1000))
                            }
                            is PersonalUiRequest.Speech -> {
                                Text(stringResource(R.string.personal_tools_speech_notice))
                                if (speechStatus.isNotBlank()) Text(speechStatus)
                            }
                            is PersonalUiRequest.Camera -> Text(stringResource(R.string.personal_tools_camera_notice))
                            is PersonalUiRequest.Biometric -> Text(stringResource(R.string.personal_tools_biometric_notice))
                        }
                        Button(onClick={ cancel() }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) }
                    }
                }
            }
        }
        lifecycleScope.launch { current.await(); finish() }
    }

    override fun onResume() {
        super.onResume()
        val current=session ?: return
        if (!sharedPersonalSessions.isActive(current.id)) { finish(); return }
        if (launched) return
        launched=true
        try {
            when (val request=current.request) {
                is PersonalUiRequest.Camera -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        complete(PersonalUiResult.Error("Доступ к камере отозван.")); return
                    }
                    val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",request.output)
                    cameraUri=uri
                    waitingExternal=true
                    try { camera.launch(uri) } catch (_: Exception) { waitingExternal=false; complete(PersonalUiResult.Error("В Android нет доступного приложения камеры.")) }
                }
                is PersonalUiRequest.Record -> startWorker { capturePersonalAudio(this,request.durationMs) { elapsedMs=it } }
                is PersonalUiRequest.Speech -> startWorker { capturePersonalSpeech(this,request) { speechStatus=it } }
                is PersonalUiRequest.Biometric -> authenticate(request)
                is PersonalUiRequest.Encrypt, is PersonalUiRequest.Reveal -> Unit
            }
        } catch (_: Exception) { complete(PersonalUiResult.Error("Не удалось запустить системную функцию. Проверьте настройки Android.")) }
    }

    private fun startWorker(capture: suspend () -> PersonalUiResult) {
        worker=lifecycleScope.launch(start=CoroutineStart.LAZY) {
            if (session?.let { sharedPersonalSessions.isActive(it.id) } != true) return@launch
            val outcome=try { capture() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { PersonalUiResult.Error("Android не смог выполнить запись или распознавание. Проверьте разрешение и системную службу.") }
            if (session?.let { sharedPersonalSessions.isActive(it.id) } != true) { outcome.destroy(); return@launch }
            complete(outcome)
        }
        worker?.start()
    }

    private fun authenticate(request: PersonalUiRequest.Biometric) {
        val combined=if (request.allowCredential && Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(combined) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (request.allowCredential) { launchCredential(request); return }
            complete(PersonalUiResult.Error("Сильная биометрия недоступна или не настроена. Зарегистрируйте отпечаток или лицо в настройках безопасности Android.")); return
        }
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                complete(PersonalUiResult.Authentication(if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) "device_credential" else "biometric"))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && request.allowCredential && Build.VERSION.SDK_INT < 30) { launchCredential(request); return }
                complete(PersonalUiResult.Error(when (errorCode) {
                    BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "Биометрическая проверка отменена."
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Биометрия временно заблокирована. Разблокируйте устройство системным способом."
                    else -> "Android не подтвердил биометрическую проверку."
                }))
            }
        })
        biometric=prompt
        val info=BiometricPrompt.PromptInfo.Builder().setTitle(request.title).setAllowedAuthenticators(combined)
        request.subtitle?.let(info::setSubtitle)
        if (!request.allowCredential || Build.VERSION.SDK_INT < 30) info.setNegativeButtonText(if (request.allowCredential) "PIN / пароль" else "Отмена")
        prompt.authenticate(info.build())
    }

    private fun launchCredential(request: PersonalUiRequest.Biometric) {
        if (session?.let { sharedPersonalSessions.isActive(it.id) } != true) return
        val keyguard=getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) { complete(PersonalUiResult.Error("На устройстве не настроены PIN, пароль или графический ключ.")); return }
        @Suppress("DEPRECATION") val intent=keyguard.createConfirmDeviceCredentialIntent(request.title,request.subtitle)
        if (intent == null) { complete(PersonalUiResult.Error("Android не поддерживает системную проверку PIN или пароля.")); return }
        waitingExternal=true
        try { credential.launch(intent) } catch (_: Exception) { waitingExternal=false; complete(PersonalUiResult.Error("Не удалось открыть системную проверку устройства.")) }
    }

    private fun complete(result: PersonalUiResult) {
        revokeCameraAccess()
        val current=session
        if (current == null) { result.destroy(); finish(); return }
        lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) { sharedPersonalSessions.complete(current.id,result); finish() }
    }
    private fun cancel() {
        val current=session ?: return
        lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) { sharedPersonalSessions.cancel(current.id); finish() }
    }
    private fun revokeCameraAccess() {
        cameraUri?.let { uri -> runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        cameraUri=null
    }
    override fun onPause() {
        super.onPause()
        if (!waitingExternal) cancel()
    }
    override fun onDestroy() {
        // Mark closing before lifecycleScope is destroyed; its cleanup continues NonCancellable.
        cancel()
        super.onDestroy()
    }
    private fun title(request: PersonalUiRequest): Int = when (request) {
        is PersonalUiRequest.Camera -> R.string.personal_tools_camera_title
        is PersonalUiRequest.Record -> R.string.personal_tools_mic_title
        is PersonalUiRequest.Speech -> R.string.personal_tools_speech_title
        is PersonalUiRequest.Biometric -> R.string.personal_tools_fingerprint_title
        is PersonalUiRequest.Encrypt -> R.string.personal_tools_encrypt_title
        is PersonalUiRequest.Reveal -> R.string.personal_tools_decrypt_title
    }
    companion object { const val EXTRA_REQUEST_ID="personal_tool_request_id" }
}
