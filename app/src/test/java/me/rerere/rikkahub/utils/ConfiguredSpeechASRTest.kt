package me.rerere.rikkahub.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import org.junit.Assert.*
import org.junit.Test

class ConfiguredSpeechASRTest {
    @Test fun unavailableSystemLanguageWithoutSelectedAsrShowsBothRemedies() = runBlocking {
        val diagnostic = "Системное распознавание Android: ERROR_LANGUAGE_UNAVAILABLE (13): системная модель выбранного языка недоступна."
        val providerFailure = "ASR чата: провайдер не выбран. Настройте распознавание речи в настройках приложения."
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error(diagnostic, androidErrorCode = 13) },
            configuredCapture = { SpeechCaptureOutcome.Error(providerFailure, asrProviderUnconfigured = true) },
        ) as SpeechCaptureOutcome.Error

        assertTrue(result.message.contains(diagnostic))
        assertTrue(result.message.contains(providerFailure))
        assertTrue(result.message.contains("«Настройки» → «Служба преобразования текста в речь» → «Распознавание речи»"))
        assertTrue(result.message.contains("выберите и настройте провайдера ASR"))
        assertTrue(result.message.contains("офлайн-модель выбранного языка"))
        assertTrue(result.message.contains("системных настройках распознавания речи Android"))
    }

    @Test fun unavailableSystemLanguageWithIncompleteAsrShowsSetupPath() = runBlocking {
        val diagnostic = "Локальное распознавание Android: ERROR_LANGUAGE_UNAVAILABLE (13): системная модель выбранного языка недоступна."
        val providerFailure = "ASR чата — DashScope: провайдер не настроен. Проверьте его настройки в разделе распознавания речи."
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error(diagnostic, androidErrorCode = 13) },
            configuredCapture = { SpeechCaptureOutcome.Error(providerFailure, asrProviderUnconfigured = true) },
        ) as SpeechCaptureOutcome.Error

        assertTrue(result.message.contains(diagnostic))
        assertTrue(result.message.contains(providerFailure))
        assertTrue(result.message.contains("«Настройки» → «Служба преобразования текста в речь» → «Распознавание речи»"))
        assertTrue(result.message.contains("офлайн-модель выбранного языка"))
    }

    @Test fun otherAndroidFailureWithUnconfiguredAsrKeepsExistingDiagnostics() = runBlocking {
        val diagnostic = "Android: ERROR_AUDIO (3)."
        val providerFailure = "ASR чата: провайдер не выбран."
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error(diagnostic, androidErrorCode = 3) },
            configuredCapture = { SpeechCaptureOutcome.Error(providerFailure, asrProviderUnconfigured = true) },
        )
        assertEquals(SpeechCaptureOutcome.Error("$diagnostic $providerFailure"), result)
    }

    @Test fun unavailableSystemLanguageWithConfiguredAsrFailureKeepsBothDiagnostics() = runBlocking {
        val diagnostic = "Android: ERROR_LANGUAGE_UNAVAILABLE (13)."
        val providerFailure = "ASR чата — Step: ошибка записи или сервиса."
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error(diagnostic, androidErrorCode = 13) },
            configuredCapture = { SpeechCaptureOutcome.Error(providerFailure) },
        )
        assertEquals(SpeechCaptureOutcome.Error("$diagnostic $providerFailure"), result)
    }

    @Test fun unavailableSystemLanguageStillUsesConfiguredChatAsr() = runBlocking {
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error("Android: ERROR_LANGUAGE_UNAVAILABLE (13).", androidErrorCode = 13) },
            configuredCapture = { SpeechCaptureOutcome.Transcript("Речь через настроенный ASR") },
        )
        assertEquals(SpeechCaptureOutcome.Transcript("Речь через настроенный ASR"), result)
    }

    @Test fun androidFailureUsesConfiguredChatAsr() = runBlocking {
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Error("Android: ERROR_AUDIO (3).") },
            configuredCapture = { SpeechCaptureOutcome.Transcript("Речь через ASR чата") },
        )
        assertEquals(SpeechCaptureOutcome.Transcript("Речь через ASR чата"), result)
    }

    @Test fun androidSuccessDoesNotRecordAgain() = runBlocking {
        val result = captureSpeechWithFallback(200,
            systemCapture = { SpeechCaptureOutcome.Transcript("Системный результат") },
            configuredCapture = { fail("Микрофон не должен открываться повторно"); error("unexpected") },
        )
        assertEquals(SpeechCaptureOutcome.Transcript("Системный результат"), result)
    }

    @Test fun androidTimeoutLeavesTimeForConfiguredCapture() = runBlocking {
        val result = captureSpeechWithFallback(120,
            systemCapture = { awaitCancellation() },
            configuredCapture = { remaining ->
                assertTrue("Должно остаться время на резервный движок", remaining > 0)
                SpeechCaptureOutcome.Transcript("Резервный результат")
            },
        )
        assertEquals(SpeechCaptureOutcome.Transcript("Резервный результат"), result)
    }

    @Test fun finalHttpTranscriptIsReadBeforeDisposalEvenAfterTransientRecorderError() = runBlocking {
        val controller = TestController(this) {
            state.value = state.value.copy(status = ASRStatus.Error, errorMessage = "cancelled read")
            delay(20)
            state.value = ASRState(status = ASRStatus.Idle, transcript = "Окончательный текст", isAvailable = true)
        }
        val result = captureConfiguredAsr(controller, "ASR чата — MiMo", 200, awaitIdleAfterStop = true)
        assertEquals(SpeechCaptureOutcome.Transcript("Окончательный текст"), result)
        assertEquals(1, controller.stopCount)
        assertTrue(controller.disposed)
    }

    @Test fun unfinishedFinalizationTimesOutAndReleasesController() = runBlocking {
        val controller = TestController(this) { awaitCancellation() }
        val result = withTimeout(1000) { captureConfiguredAsr(controller, "ASR чата — Step", 80, true) }
        assertTrue(result is SpeechCaptureOutcome.Error)
        assertEquals(1, controller.stopCount)
        assertTrue(controller.disposed)
    }

    @Test fun generationCancellationDisposesWithoutStartingAnUpload() = runBlocking {
        val controller = TestController(this) { error("После отмены нельзя отправлять звук") }
        val capture = launch { captureConfiguredAsr(controller, "ASR чата — MiMo", 10000, true) }
        withTimeout(1000) { controller.started.await() }
        capture.cancelAndJoin()
        assertTrue(controller.disposed)
        assertEquals(0, controller.stopCount)
    }

    @Test fun providerFailureDoesNotExposeServerErrorOrCredentials() = runBlocking {
        val controller = TestController(this, initialError = "https://private.invalid/?key=secret") {}
        val result = captureConfiguredAsr(controller, "ASR чата — Step", 100, true) as SpeechCaptureOutcome.Error
        assertTrue(result.message.contains("Step"))
        assertFalse(result.message.contains("secret"))
        assertFalse(result.message.contains("private.invalid"))
        assertTrue(controller.disposed)
    }

    private class TestController(
        parent: CoroutineScope,
        private val initialError: String? = null,
        private val finalize: suspend TestController.() -> Unit,
    ) : ASRController {
        override val state = MutableStateFlow(ASRState(isAvailable = true))
        val started = CompletableDeferred<Unit>()
        var disposed = false
        var stopCount = 0
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = if (initialError == null) ASRStatus.Listening else ASRStatus.Error,
                isAvailable = true, transcript = "Предварительный текст", errorMessage = initialError)
            started.complete(Unit)
        }

        override fun stop() {
            stopCount++
            state.value = state.value.copy(status = ASRStatus.Stopping)
            scope.launch { finalize() }
        }

        override fun dispose() { disposed = true; scope.cancel() }
    }
}
