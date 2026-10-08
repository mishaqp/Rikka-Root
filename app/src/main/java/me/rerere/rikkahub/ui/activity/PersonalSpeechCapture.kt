// Adapted from ExTV/rikkahub-agent, local/SpeechToTextTool.kt (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiRequest
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiResult
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.utils.SpeechCaptureOutcome
import me.rerere.rikkahub.utils.asrEngineLabel
import me.rerere.rikkahub.utils.captureConfiguredAsr
import me.rerere.rikkahub.utils.captureSpeechWithFallback
import me.rerere.rikkahub.utils.createAsrController
import me.rerere.rikkahub.utils.requiresIdleFinalization
import okhttp3.OkHttpClient
import org.koin.java.KoinJavaComponent.getKoin

internal suspend fun capturePersonalSpeech(
    context: Context,
    request: PersonalUiRequest.Speech,
    onStatus: (String) -> Unit = {},
): PersonalUiResult = withContext(Dispatchers.Main.immediate) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return@withContext PersonalUiResult.Error("Доступ к микрофону отозван. Разрешите его в настройках функции.")
    }
    val onDevice = Build.VERSION.SDK_INT >= 31 && request.preferOffline &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    val androidEngine = if (onDevice) "Локальное распознавание Android" else "Системное распознавание Android"
    val provider = runCatching { getKoin().get<SettingsStore>().settingsFlow.value.getSelectedASRProvider() }.getOrNull()
    val configuredEngine = provider?.let(::asrEngineLabel) ?: "ASR чата"
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val focusLost = CompletableDeferred<Unit>()
    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { change -> if (change < 0) focusLost.complete(Unit) }
        .build()
    try {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return@withContext PersonalUiResult.Error("Микрофон: не удалось получить аудиофокус. Завершите звонок или другую запись и повторите запрос.")
        }
        val outcome = coroutineScope {
            val capture = async {
                var androidDiagnostic = ""
                captureSpeechWithFallback(request.timeoutMs.toLong(), androidEngine, configuredEngine,
                    onFallback = { androidDiagnostic = it },
                    systemCapture = { captureAndroidSpeech(context, request, onDevice, androidEngine, onStatus) },
                    configuredCapture = { remaining ->
                        onStatus("$androidDiagnostic Переключение на $configuredEngine; говорите ещё раз. Этот движок может использовать сеть.")
                        if (provider == null) {
                            SpeechCaptureOutcome.Error("ASR чата: провайдер не выбран. Настройте распознавание речи в настройках приложения.",
                                asrProviderUnconfigured = true)
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            SpeechCaptureOutcome.Error("$configuredEngine: доступ к микрофону отозван.")
                        } else {
                            val controller = createAsrController(context.applicationContext, getKoin().get<OkHttpClient>(), provider)
                            if (controller == null) SpeechCaptureOutcome.Error("$configuredEngine: провайдер не настроен. Проверьте его настройки в разделе распознавания речи.",
                                asrProviderUnconfigured = true)
                            else captureConfiguredAsr(controller, configuredEngine, remaining, requiresIdleFinalization(provider)) {
                                onStatus("$configuredEngine: микрофон остановлен, ожидается окончательный текст.")
                            }
                        }
                    },
                )
            }
            select {
                capture.onAwait { it }
                focusLost.onAwait {
                    capture.cancelAndJoin()
                    SpeechCaptureOutcome.Error("Распознавание остановлено: аудиофокус перешёл к другому приложению. Микрофон освобождён.")
                }
            }
        }
        when (outcome) {
            is SpeechCaptureOutcome.Transcript -> PersonalUiResult.Speech(outcome.text)
            is SpeechCaptureOutcome.Error -> PersonalUiResult.Error(outcome.message)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }
}

private suspend fun captureAndroidSpeech(
    context: Context,
    request: PersonalUiRequest.Speech,
    onDevice: Boolean,
    engine: String,
    onStatus: (String) -> Unit,
): SpeechCaptureOutcome {
    if (!onDevice && !runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) {
        return SpeechCaptureOutcome.Error("$engine: служба распознавания недоступна или отключена в Android.")
    }
    var recognizer: SpeechRecognizer? = null
    try {
        val outcome = CompletableDeferred<SpeechCaptureOutcome>()
        val current = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onStatus("$engine: микрофон готов, говорите.") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onStatus("$engine: ожидается текст.") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                outcome.complete(if (text == null) SpeechCaptureOutcome.Error("$engine: речь не распознана.")
                    else SpeechCaptureOutcome.Transcript(text.take(65_536)))
            }
            override fun onError(error: Int) {
                outcome.complete(SpeechCaptureOutcome.Error("$engine: ${androidSpeechError(error)}", androidErrorCode = error))
            }
        })
        currentCoroutineContext().ensureActive()
        onStatus("$engine: запуск микрофона, говорите.")
        current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
        })
        return outcome.await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        return SpeechCaptureOutcome.Error("$engine: Android запретил доступ к микрофону.")
    } catch (_: Exception) {
        return SpeechCaptureOutcome.Error("$engine: не удалось запустить службу распознавания.")
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }
}

private fun androidSpeechError(code: Int): String {
    val (name, message) = when (code) {
        1 -> "ERROR_NETWORK_TIMEOUT" to "истекло время подключения к сети"
        2 -> "ERROR_NETWORK" to "сеть недоступна"
        3 -> "ERROR_AUDIO" to "не удалось записать звук"
        4 -> "ERROR_SERVER" to "ошибка сервера распознавания"
        5 -> "ERROR_CLIENT" to "ошибка системного клиента распознавания"
        6 -> "ERROR_SPEECH_TIMEOUT" to "речь не началась вовремя"
        7 -> "ERROR_NO_MATCH" to "речь не распознана"
        8 -> "ERROR_RECOGNIZER_BUSY" to "служба занята другим приложением"
        9 -> "ERROR_INSUFFICIENT_PERMISSIONS" to "службе не предоставлен доступ к микрофону"
        10 -> "ERROR_TOO_MANY_REQUESTS" to "слишком много запросов к службе"
        11 -> "ERROR_SERVER_DISCONNECTED" to "соединение со службой прервано"
        12 -> "ERROR_LANGUAGE_NOT_SUPPORTED" to "выбранный язык не поддерживается"
        13 -> "ERROR_LANGUAGE_UNAVAILABLE" to "системная модель выбранного языка недоступна"
        14 -> "ERROR_CANNOT_CHECK_SUPPORT" to "служба не смогла проверить поддержку языка"
        15 -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS" to "служба не поддерживает уведомления о загрузке модели"
        else -> "ANDROID_ERROR" to "неизвестная ошибка службы распознавания"
    }
    return "$name ($code): $message."
}
