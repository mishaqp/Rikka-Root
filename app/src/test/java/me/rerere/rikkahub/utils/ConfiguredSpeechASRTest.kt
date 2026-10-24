package me.rerere.rikkahub.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun androidTimeoutLeavesTimeForConfiguredCapture() = runTest {
        // Advance coroutine deadlines virtually. A 120 ms wall-clock budget also expired
        // under CI scheduling delays, before the fallback could be resumed.
        // Keep a realistic budget for the production monotonic remaining-time check.
        val timeoutMs = 30_000L
        val result = captureSpeechWithFallback(timeoutMs,
            systemCapture = { awaitCancellation() },
            configuredCapture = { remaining ->
                assertTrue("Должно остаться время на резервный движок", remaining in 1..timeoutMs)
                SpeechCaptureOutcome.Transcript("Резервный результат")
            },
        )
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(SpeechCaptureOutcome.Transcript("Резервный результат"), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun finalHttpTranscriptIsReadBeforeDisposalEvenAfterTransientRecorderError() = runTest {
        val controller = TestController(this) {
            state.value = state.value.copy(status = ASRStatus.Error, errorMessage = "cancelled read")
            delay(20)
            state.value = ASRState(status = ASRStatus.Idle, transcript = "Окончательный текст", isAvailable = true)
        }
        val capture = async { captureConfiguredAsr(controller, "ASR чата — MiMo", 200, awaitIdleAfterStop = true) }
        runCurrent()
        assertEquals(ASRStatus.Listening, controller.state.value.status)
        assertEquals(0, controller.stopCount)
        assertFalse(capture.isCompleted)
        assertFalse(controller.disposed)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(ASRStatus.Error, controller.state.value.status)
        assertEquals("cancelled read", controller.state.value.errorMessage)
        assertEquals(1, controller.stopCount)
        assertFalse(capture.isCompleted)
        assertFalse(controller.disposed)

        advanceTimeBy(20)
        runCurrent()
        assertEquals(120L, testScheduler.currentTime)
        assertTrue(capture.isCompleted)
        assertEquals(SpeechCaptureOutcome.Transcript("Окончательный текст"), capture.await())
        assertEquals(1, controller.stopCount)
        assertTrue(controller.disposed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unfinishedFinalizationTimesOutAndReleasesController() = runTest {
        val controller = TestController(this) { awaitCancellation() }
        val capture = async {
            captureConfiguredAsr(controller, "ASR чата — Step", 80, true)
        }
        runCurrent()
        assertEquals(ASRStatus.Listening, controller.state.value.status)
        assertEquals(0, controller.stopCount)
        assertFalse(controller.disposed)

        // Keep the real 40/80 ms deadlines, without depending on CI thread scheduling.
        advanceTimeBy(40)
        runCurrent()
        assertEquals(ASRStatus.Stopping, controller.state.value.status)
        assertEquals(1, controller.stopCount)
        assertFalse(capture.isCompleted)
        assertFalse(controller.disposed)

        advanceTimeBy(40)
        runCurrent()
        assertEquals(80L, testScheduler.currentTime)
        assertTrue(capture.isCompleted)
        assertEquals(SpeechCaptureOutcome.Error("ASR чата — Step: время ожидания окончательного текста истекло."), capture.await())
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
