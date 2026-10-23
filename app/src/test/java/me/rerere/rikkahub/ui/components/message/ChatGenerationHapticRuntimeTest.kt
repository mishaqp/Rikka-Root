package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs production MessagePartsBlock; the platform vibration boundary only records pulses. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatGenerationHapticRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val loading = mutableStateOf(false)
    private val parts = mutableStateOf(listOf<UIMessagePart>(UIMessagePart.Text("Начало")))
    private val events = mutableListOf<HapticFeedbackType>()
    private val haptic = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
    }

    private fun render() {
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        every { tts.speakingMessageId } returns MutableStateFlow<kotlin.uuid.Uuid?>(null)
        val source = UIMessage.assistant("")
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(
            hapticsEnabled = true, enableMessageGenerationHapticEffect = true, enableAssistantMarkdown = false,
            showModelName = false, showModelIcon = false, showTokenUsage = false))
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides settings, LocalTTSState provides tts, LocalHapticFeedback provides haptic) {
                    Column(Modifier.width(320.dp)) {
                        ChatMessage(MessageNode.of(source.copy(parts = parts.value)), loading = loading.value,
                            onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
                    }
                }
            }
        }
    }

    private fun settleDebounce() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    @Test fun startingGenerationOnMountedMessageEnablesPulsesWithoutChangingSettings() {
        render()
        settleDebounce()
        assertEquals(emptyList<HapticFeedbackType>(), events)
        compose.runOnIdle {
            loading.value = true
            parts.value = listOf(UIMessagePart.Text("Начало и поток"))
        }
        settleDebounce()
        assertEquals("New output during generation must vibrate", listOf(HapticFeedbackType.KeyboardTap), events)
    }

    @Test fun finishedGenerationDoesNotPulseOnLaterContentUpdates() {
        loading.value = true
        render()
        settleDebounce()
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), events)
        compose.runOnIdle {
            events.clear()
            loading.value = false
        }
        settleDebounce()
        compose.runOnIdle { parts.value = listOf(UIMessagePart.Text("Готовый ответ")) }
        settleDebounce()
        assertEquals("Finishing the stream must stop generation haptics immediately", emptyList<HapticFeedbackType>(), events)
    }
}
