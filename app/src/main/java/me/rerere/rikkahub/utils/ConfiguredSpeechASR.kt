package me.rerere.rikkahub.utils

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import okhttp3.OkHttpClient

/** Shared with chat speech; credentials remain in the existing selected-provider settings. */
internal fun createAsrController(context: Context, httpClient: OkHttpClient, provider: ASRProviderSetting): ASRController? =
    when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> if (provider.apiKey.isBlank()) null else OpenAIRealtimeASRController(context, httpClient, provider)
        is ASRProviderSetting.DashScope -> if (provider.apiKey.isBlank()) null else DashScopeASRController(context, httpClient, provider)
        is ASRProviderSetting.Volcengine -> if (provider.apiKey.isBlank()) null else VolcengineASRController(context, httpClient, provider)
        is ASRProviderSetting.MiMo -> if (provider.apiKey.isBlank()) null else MiMoASRController(context, httpClient, provider)
        is ASRProviderSetting.Step -> if (provider.apiKey.isBlank()) null else StepASRController(context, httpClient, provider)
    }

/** Fixed engine names avoid exposing user-entered names, credentials, URLs or server responses. */
internal fun asrEngineLabel(provider: ASRProviderSetting): String = "ASR чата — " + when (provider) {
    is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
    is ASRProviderSetting.DashScope -> "DashScope"
    is ASRProviderSetting.Volcengine -> "Volcengine"
    is ASRProviderSetting.MiMo -> "MiMo"
    is ASRProviderSetting.Step -> "Step"
}

internal fun requiresIdleFinalization(provider: ASRProviderSetting): Boolean =
    provider is ASRProviderSetting.MiMo || provider is ASRProviderSetting.Step

internal sealed interface SpeechCaptureOutcome {
    data class Transcript(val text: String) : SpeechCaptureOutcome
    data class Error(val message: String) : SpeechCaptureOutcome
}

internal suspend fun captureSpeechWithFallback(
    timeoutMs: Long,
    systemEngineLabel: String = "Системное распознавание Android",
    configuredEngineLabel: String = "ASR чата",
    onFallback: (String) -> Unit = {},
    systemCapture: suspend (Long) -> SpeechCaptureOutcome,
    configuredCapture: suspend (Long) -> SpeechCaptureOutcome,
): SpeechCaptureOutcome {
    require(timeoutMs > 0)
    val started = System.nanoTime()
    val systemBudget = (timeoutMs / 3).coerceIn(1, 10_000)
    var timeoutMessage = "$systemEngineLabel: время ожидания истекло."
    return withTimeoutOrNull(timeoutMs) {
        val system = withTimeoutOrNull(systemBudget) { systemCapture(systemBudget) }
            ?: SpeechCaptureOutcome.Error(timeoutMessage)
        if (system is SpeechCaptureOutcome.Transcript) return@withTimeoutOrNull system
        val diagnostic = (system as SpeechCaptureOutcome.Error).message
        timeoutMessage = "$diagnostic $configuredEngineLabel: общее время распознавания истекло."
        currentCoroutineContext().ensureActive()
        onFallback(diagnostic)
        val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
        if (remaining <= 0) return@withTimeoutOrNull SpeechCaptureOutcome.Error(timeoutMessage)
        when (val configured = configuredCapture(remaining)) {
            is SpeechCaptureOutcome.Transcript -> configured
            is SpeechCaptureOutcome.Error -> SpeechCaptureOutcome.Error("$diagnostic ${configured.message}")
        }
    } ?: SpeechCaptureOutcome.Error(timeoutMessage)
}

internal suspend fun captureConfiguredAsr(
    controller: ASRController,
    engineLabel: String,
    timeoutMs: Long,
    awaitIdleAfterStop: Boolean = false,
    onStopping: () -> Unit = {},
): SpeechCaptureOutcome {
    var finishing = false
    try {
        return withTimeoutOrNull(timeoutMs) capture@ {
            currentCoroutineContext().ensureActive()
            controller.start { /* The final state is authoritative; callbacks may still be queued. */ }
            val finalizationBudget = (timeoutMs / 2).coerceAtMost(10_000)
            val listeningBudget = (timeoutMs - finalizationBudget).coerceIn(1, 15_000)
            withTimeoutOrNull(listeningBudget) {
                controller.state.first { it.status == ASRStatus.Idle || it.status == ASRStatus.Error || it.voiceTurn.isComplete }
            }
            currentCoroutineContext().ensureActive()
            val beforeStop = controller.state.value
            if (beforeStop.status == ASRStatus.Idle || beforeStop.status == ASRStatus.Error) {
                return@capture configuredAsrOutcome(beforeStop, engineLabel)
            }
            controller.stop()
            finishing = true
            onStopping()
            // HTTP engines may briefly report a cancelled recorder error while the final upload runs.
            val finalState = controller.state.first {
                it.status == ASRStatus.Idle || (!awaitIdleAfterStop && it.status == ASRStatus.Error)
            }
            configuredAsrOutcome(finalState, engineLabel)
        } ?: SpeechCaptureOutcome.Error(if (finishing) "$engineLabel: время ожидания окончательного текста истекло."
            else "$engineLabel: время распознавания истекло.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return SpeechCaptureOutcome.Error("$engineLabel: не удалось запустить запись или распознавание. Проверьте микрофон и настройки провайдера.")
    } finally {
        // Cancellation disposes directly instead of stop(), which would initiate an upload.
        withContext(NonCancellable) { controller.dispose() }
    }
}

private fun configuredAsrOutcome(state: ASRState, engineLabel: String): SpeechCaptureOutcome {
    if (state.status == ASRStatus.Error || !state.errorMessage.isNullOrBlank()) {
        return SpeechCaptureOutcome.Error("$engineLabel: ошибка записи или сервиса. Проверьте микрофон, сеть и настройки провайдера.")
    }
    return state.transcript.trim().takeIf { it.isNotEmpty() }
        ?.let { SpeechCaptureOutcome.Transcript(it.take(65_536)) }
        ?: SpeechCaptureOutcome.Error("$engineLabel: речь не распознана. Произнесите фразу ещё раз.")
}
