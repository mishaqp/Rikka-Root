package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.rememberHazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.screenshot.ChatBeforeFixture
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Mounts ChatList and invokes the exact listener it registers on RouteActivity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatVolumeKeyRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var activity: RouteActivity
    private lateinit var state: LazyListState
    private val settings = mutableStateOf(ScreenshotFixtures.settings.copy(displaySetting =
        ScreenshotFixtures.settings.displaySetting.copy(enableVolumeKeyScroll = false,
            enableAutoScroll = false, showMessageJumper = false, enableAssistantMarkdown = false,
            showModelName = false, showModelIcon = false, showTokenUsage = false)))
    private val bottomPadding = mutableStateOf(40.dp)
    private val visible = mutableStateOf(true)

    @Before fun install() {
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
        // Attaches the real Activity context and registry without launching app navigation.
        activity = Robolectric.buildActivity(RouteActivity::class.java).get()
    }
    @After fun close() { fixture.close() }

    private fun render() {
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        every { tts.speakingMessageId } returns MutableStateFlow<kotlin.uuid.Uuid?>(null)
        val conversation = fixture.conversation.copy(chatSuggestions = emptyList(), messageNodes = List(30) { index ->
            MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Ответ $index\nВторая строка\nТретья строка")))))
        })
        compose.setContent {
            ScreenshotTheme {
                state = rememberLazyListState()
                val haze = rememberHazeState()
                val toaster = rememberToasterState()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalContext provides activity, LocalSettings provides settings.value,
                        LocalTTSState provides tts, LocalToaster provides toaster, LocalSharedTransitionScope provides this) {
                        Box(Modifier.requiredSize(411.dp, 600.dp)) {
                            if (visible.value) ChatList(PaddingValues(bottom = bottomPadding.value), conversation,
                                state, false, previewMode = false, settings = settings.value, hazeState = haze)
                        }
                    }
                }
            }
        }
    }

    @Test fun togglingVolumeScrollUpdatesMountedChatAndDisposalRemovesListener() {
        render()
        compose.runOnIdle { assertFalse(activity.volumeKeyListeners.single()(false)) }
        compose.runOnIdle { settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(enableVolumeKeyScroll = true)) }
        compose.runOnIdle { assertTrue("Enabling the setting must take effect without reopening chat", activity.volumeKeyListeners.single()(false)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 0) }
        compose.runOnIdle { settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(enableVolumeKeyScroll = false)) }
        compose.runOnIdle { assertFalse("Disabling restores normal volume key handling", activity.volumeKeyListeners.single()(false)) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertTrue(activity.volumeKeyListeners.isEmpty()) }
    }

    @Test fun changedRatioAndComposerInsetUpdateTheActualScrollDistance() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(enableVolumeKeyScroll = true, volumeKeyScrollRatio = .25f))
        render()
        fun scrollDistance(): Int {
            compose.runOnIdle { state.requestScrollToItem(0) }
            compose.waitForIdle()
            val rowStride = state.layoutInfo.visibleItemsInfo.first { it.index == 0 }.size + 12
            compose.runOnIdle { assertTrue(activity.volumeKeyListeners.single()(false)) }
            compose.waitForIdle()
            return state.firstVisibleItemIndex * rowStride + state.firstVisibleItemScrollOffset
        }
        assertEquals(132, scrollDistance()) // (600 - 32 - 40) * .25
        compose.runOnIdle {
            bottomPadding.value = 120.dp
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(volumeKeyScrollRatio = .5f))
        }
        assertEquals("New ratio and measured composer inset must both be read", 224, scrollDistance())
    }
}
