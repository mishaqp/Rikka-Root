// Adapted from ExTV/rikkahub-agent, local/SpeechToTextTool.kt (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiRequest
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiResult

internal suspend fun capturePersonalSpeech(context: Context, request: PersonalUiRequest.Speech): PersonalUiResult = withContext(Dispatchers.Main.immediate) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        return@withContext PersonalUiResult.Error("Доступ к микрофону отозван. Разрешите его в настройках функции.")
    val onDevice=Build.VERSION.SDK_INT >= 31 && request.preferOffline && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) return@withContext PersonalUiResult.Error("В Android нет службы распознавания речи. Установите или включите системную службу в настройках Android.")
    var recognizer: SpeechRecognizer?=null
    try {
        val outcome=CompletableDeferred<PersonalUiResult>()
        val current=if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer=current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                outcome.complete(if (text == null) PersonalUiResult.Error("Речь не распознана. Попробуйте произнести фразу ещё раз.") else PersonalUiResult.Speech(text.take(65536)))
            }
            override fun onError(error: Int) { outcome.complete(PersonalUiResult.Error(when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Служба распознавания не получила доступ к микрофону."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Служба распознавания занята другим приложением."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Системной службе распознавания недоступна сеть."
                SpeechRecognizer.ERROR_AUDIO -> "Служба распознавания не смогла записать звук."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не распознана или вы не начали говорить."
                else -> "Системная служба распознавания завершилась с ошибкой."
            })) }
        })
        currentCoroutineContext().ensureActive()
        current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,request.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,request.preferOffline)
        })
        withTimeoutOrNull(request.timeoutMs.toLong()) { outcome.await() } ?: PersonalUiResult.Error("Время распознавания речи истекло.")
    } finally {
        // Runs on success too; Agent only destroyed the recognizer on cancellation.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            runCatching { recognizer?.cancel() }; runCatching { recognizer?.destroy() }
        }
    }
}
