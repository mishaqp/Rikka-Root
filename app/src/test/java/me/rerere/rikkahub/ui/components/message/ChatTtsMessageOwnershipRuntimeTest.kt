package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.chat.TTSAutoPlay
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionController
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Real message buttons, CustomTtsState and TtsController; only synthesis and audio output are fixed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTtsMessageOwnershipRuntimeTest {
    private val compose = createComposeRule()
    private val messages = listOf(UIMessage.assistant("First response"), UIMessage.assistant("Second response"))
    private val provider = TTSProviderSetting.SystemTTS()
    private val settings = ScreenshotFixtures.settings.copy(
        ttsProviders = listOf(provider),
        selectedTTSProviderId = provider.id,
        displaySetting = ScreenshotFixtures.settings.displaySetting.copy(autoPlayTTSAfterGeneration = true),
    )
    private val audioState = MutableStateFlow(PlaybackState())
    private val persistedSettings = MutableStateFlow(settings)
    private val mounted = mutableStateOf(true)
    private val synthesized = CopyOnWriteArrayList<String>()
    private val playbacks = CopyOnWriteArrayList<OutputPlayback>()
    private lateinit var tts: CustomTtsState

    private val boundaries = object : ExternalResource() {
        override fun before() {
            // Keep the real controller's queue, cancellation, pause and completion behavior.
            // The audio boundary suspends until the test supplies the hardware completion event.
            mockkConstructor(AudioPlayer::class)
            every { anyConstructed<AudioPlayer>().playbackState } returns audioState
            every { anyConstructed<AudioPlayer>().stop() } answers { audioState.value = PlaybackState() }
            every { anyConstructed<AudioPlayer>().pause() } answers {
                audioState.value = audioState.value.copy(status = PlaybackStatus.Paused)
            }
            every { anyConstructed<AudioPlayer>().resume() } answers {
                audioState.value = audioState.value.copy(status = PlaybackStatus.Playing)
            }
            every { anyConstructed<AudioPlayer>().setSpeed(any()) } answers {
                audioState.value = audioState.value.copy(speed = firstArg())
            }
            coEvery { anyConstructed<AudioPlayer>().play(any()) } coAnswers {
                val response = firstArg<TTSResponse>()
                val playback = OutputPlayback(response.audioData.toString(Charsets.UTF_8))
                playbacks += playback
                audioState.value = audioState.value.copy(status = PlaybackStatus.Playing)
                var finished = false
                try {
                    playback.finished.await()
                    finished = true
                    audioState.value = audioState.value.copy(status = PlaybackStatus.Ended)
                } finally {
                    playback.cancelled = !finished
                }
            }

            val manager = mockk<TTSManager>()
            every { manager.generateSpeech(any(), any()) } answers {
                val request = secondArg<TTSRequest>()
                synthesized += request.text
                flowOf(AudioChunk(request.text.toByteArray(), AudioFormat.PCM, sampleRate = 24_000, isLast = true))
            }
            val store = mockk<SettingsStore>()
            every { store.settingsFlow } returns persistedSettings
            stopKoin()
            startKoin { modules(module { single { store }; single { manager } }) }
        }

        override fun after() {
            // This outer rule runs after Compose disposal has released the real controller/player.
            stopKoin()
            unmockkConstructor(AudioPlayer::class)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(boundaries).around(compose)

    @Test fun startingOneResponseOnlyActivatesItsOwnToggle() {
        render()
        control(0).assertIsOff()
        control(1).assertIsOff()

        control(0).performClick()
        awaitPlayback(1)

        control(0).assertIsOn()
        control(1).assertIsOff()
        compose.runOnIdle { assertEquals(messages[0].id, tts.speakingMessageId.value) }
        assertEquals(listOf("First response"), synthesized.toList())
    }

    @Test fun tappingAnotherResponseFlushesThePreviousSpeechAndStartsTheTappedResponse() {
        render()
        control(0).performClick()
        awaitPlayback(1)

        control(1).performClick()
        awaitPlayback(2)

        control(0).assertIsOff()
        control(1).assertIsOn()
        assertEquals(listOf("First response", "Second response"), playbacks.map { it.text })
        compose.runOnIdle {
            assertTrue("Switching responses must cancel the first output instead of queueing", playbacks[0].cancelled)
            assertTrue(tts.isSpeaking.value)
            assertEquals(messages[1].id, tts.speakingMessageId.value)
        }
    }

    @Test fun tappingTheActiveSecondResponseStopsSpeechAndAllowsTheFirstToStartAgain() {
        render()
        control(1).performClick()
        awaitPlayback(1)
        control(1).assertIsOn()

        control(1).performClick()

        control(0).assertIsOff()
        control(1).assertIsOff()
        compose.runOnIdle {
            assertTrue(playbacks[0].cancelled)
            assertEquals(false, tts.isSpeaking.value)
            assertNull(tts.speakingMessageId.value)
        }
        control(0).performClick()
        awaitPlayback(2)
        control(0).assertIsOn()
        control(1).assertIsOff()
        assertEquals(listOf("Second response", "First response"), playbacks.map { it.text })
    }

    @Test fun naturalCompletionClearsTheActiveToggleAndDoesNotLeaveTheNextResponseOwned() {
        render()
        control(1).performClick()
        awaitPlayback(1)

        compose.runOnIdle { playbacks[0].finished.complete(Unit) }
        compose.waitUntil { !tts.isSpeaking.value }

        control(0).assertIsOff()
        control(1).assertIsOff()
        compose.runOnIdle { assertNull(tts.speakingMessageId.value) }
        control(0).performClick()
        awaitPlayback(2)
        control(0).assertIsOn()
        control(1).assertIsOff()
        compose.runOnIdle { assertEquals(messages[0].id, tts.speakingMessageId.value) }
    }

    @Test fun pausedSpeechKeepsItsMessageAndResumingDoesNotRestartIt() {
        render()
        control(0).performClick()
        awaitPlayback(1)

        compose.runOnIdle { tts.pause() }
        compose.waitUntil { tts.playbackState.value.status == PlaybackStatus.Paused }
        control(0).assertIsOn()
        control(1).assertIsOff()

        compose.runOnIdle { tts.resume() }
        compose.waitUntil { tts.playbackState.value.status == PlaybackStatus.Playing }
        control(0).assertIsOn()
        control(1).assertIsOff()
        assertEquals(listOf("First response"), playbacks.map { it.text })
    }

    @Test fun ordinarySpeechReplacesMessageSpeechWithoutClaimingAMessageWithMatchingText() {
        render()
        control(0).performClick()
        awaitPlayback(1)

        // Voice mode uses this existing API. Matching text cannot identify a message owner.
        compose.runOnIdle { tts.speak("Second response") }
        awaitPlayback(2)

        control(0).assertIsOff()
        control(1).assertIsOff()
        compose.runOnIdle {
            assertTrue(tts.isSpeaking.value)
            assertNull(tts.speakingMessageId.value)
        }
        assertEquals(listOf("First response", "Second response"), playbacks.map { it.text })
    }

    @Test fun ordinaryAppendKeepsTheCurrentOutputAndPlaysTheQueuedSpeechAfterIt() {
        render()
        control(0).performClick()
        awaitPlayback(1)

        compose.runOnIdle { tts.speak("Queued ordinary speech", flushCalled = false) }

        control(0).assertIsOff()
        control(1).assertIsOff()
        compose.runOnIdle {
            assertEquals(false, playbacks[0].cancelled)
            assertEquals(1, playbacks.size)
            assertTrue(tts.isSpeaking.value)
            assertNull(tts.speakingMessageId.value)
            playbacks[0].finished.complete(Unit)
        }
        awaitPlayback(2)
        assertEquals(listOf("First response", "Queued ordinary speech"), playbacks.map { it.text })
        control(0).assertIsOff()
        control(1).assertIsOff()
    }

    @Test fun endedAudioBetweenChunksKeepsTheMessageActiveUntilTheWholeQueueFinishes() {
        val longMessage = UIMessage.assistant("First chunk\n\nSecond chunk")
        render(renderedMessages = listOf(longMessage, messages[1]))
        control(0).performClick()
        awaitPlayback(1)

        // Hardware reports Ended before the controller advances to the next queued chunk.
        compose.runOnIdle { audioState.value = audioState.value.copy(status = PlaybackStatus.Ended) }
        control(0).assertIsOn()
        control(1).assertIsOff()
        compose.runOnIdle {
            assertEquals(PlaybackStatus.Ended, tts.playbackState.value.status)
            assertTrue(tts.isSpeaking.value)
            assertEquals(longMessage.id, tts.speakingMessageId.value)
            playbacks[0].finished.complete(Unit)
        }
        awaitPlayback(2)
        assertEquals(listOf("First chunk", "Second chunk"), playbacks.map { it.text })
        control(0).assertIsOn()
        control(1).assertIsOff()

        compose.runOnIdle { playbacks[1].finished.complete(Unit) }
        control(0).assertIsOff()
        control(1).assertIsOff()
        compose.runOnIdle { assertNull(tts.speakingMessageId.value) }
    }

    @Test fun removingTheProviderStopsSpeechAndClearsItsMessageOwner() {
        render()
        control(0).performClick()
        awaitPlayback(1)

        compose.runOnIdle { persistedSettings.value = settings.copy(ttsProviders = emptyList()) }

        control(0).assertIsOff().assertIsNotEnabled()
        control(1).assertIsOff().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(playbacks[0].cancelled)
            assertEquals(false, tts.isSpeaking.value)
            assertNull(tts.speakingMessageId.value)
        }
    }

    @Test fun disposingTheTtsCompositionStopsSpeechAndClearsItsMessageOwner() {
        render()
        control(1).performClick()
        awaitPlayback(1)

        compose.runOnIdle { mounted.value = false }

        compose.onAllNodesWithContentDescription(
            RuntimeEnvironment.getApplication().getString(R.string.tts),
        ).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(playbacks[0].cancelled)
            assertEquals(false, tts.isSpeaking.value)
            assertNull(tts.speakingMessageId.value)
        }
    }

    @Test fun autoplayAfterGenerationOnlyActivatesTheLatestAssistantResponse() {
        val generationDone = MutableSharedFlow<Uuid>(extraBufferCapacity = 1)
        val conversation = Conversation(
            assistantId = ScreenshotFixtures.assistant.id,
            messageNodes = messages.map { MessageNode.of(it) },
        )
        val voice = mockk<VoiceSessionController>()
        every { voice.state } returns MutableStateFlow(VoiceSessionState())
        val vm = mockk<ChatVM>()
        every { vm.generationDoneFlow } returns generationDone
        every { vm.voiceSession } returns voice
        render(vm, conversation)

        compose.runOnIdle { assertTrue(generationDone.tryEmit(conversation.id)) }
        awaitPlayback(1)

        control(0).assertIsOff()
        control(1).assertIsOn()
        compose.runOnIdle { assertEquals(messages[1].id, tts.speakingMessageId.value) }
        assertEquals(listOf("Second response"), playbacks.map { it.text })
    }

    private fun render(
        autoplayVm: ChatVM? = null,
        conversation: Conversation? = null,
        renderedMessages: List<UIMessage> = messages,
    ) {
        compose.setContent {
            ScreenshotTheme {
                if (mounted.value) {
                    tts = rememberCustomTtsState()
                    CompositionLocalProvider(LocalTTSState provides tts) {
                        if (autoplayVm != null) TTSAutoPlay(autoplayVm, settings, requireNotNull(conversation))
                        Column {
                            renderedMessages.forEach { message ->
                                ChatMessageActionButtons(message, MessageNode.of(message), {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
    }

    private fun control(index: Int) = compose.onAllNodesWithContentDescription(
        RuntimeEnvironment.getApplication().getString(R.string.tts),
    )[index]

    private fun awaitPlayback(count: Int) {
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                // Synthesis completes on Dispatchers.IO. Pump its continuation onto Robolectric's
                // paused main Looper as well as Compose's frame clock. Advancing virtual time
                // also releases the controller's delay between queued chunks without real sleeps.
                ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
                playbacks.size >= count
            }
        } catch (failure: ComposeTimeoutException) {
            val logs = ShadowLog.getLogs().filter { it.tag == "TtsController" }.joinToString("\n") {
                "${it.msg}: ${it.throwable}"
            }
            throw AssertionError(
                "Expected $count outputs, got ${playbacks.size}; available=${tts.isAvailable.value}, " +
                    "speaking=${tts.isSpeaking.value}, error=${tts.error.value}, " +
                    "chunks=${tts.currentChunk.value}/${tts.totalChunks.value}, synthesized=$synthesized\n$logs",
                failure,
            )
        }
        compose.waitForIdle()
    }

    private class OutputPlayback(val text: String) {
        val finished = CompletableDeferred<Unit>()
        @Volatile var cancelled = false
    }
}
