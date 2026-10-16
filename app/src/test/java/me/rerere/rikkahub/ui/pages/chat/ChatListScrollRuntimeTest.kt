package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.rememberHazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
import me.rerere.rikkahub.data.model.Conversation
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real timeline layout and navigation; no snapshot recording or copied scroll algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatListScrollRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var state: LazyListState
    private val conversation = mutableStateOf<Conversation?>(null)
    private val loading = mutableStateOf(false)
    private val bottomPadding = mutableStateOf(80.dp)
    private val settings = mutableStateOf(ScreenshotFixtures.settings.copy(displaySetting =
        ScreenshotFixtures.settings.displaySetting.copy(enableAutoScroll = true, autoScrollIdleSeconds = 0,
            showMessageJumper = true, messageNavButtonsMode = MessageNavButtonsMode.ALWAYS,
            enableAssistantMarkdown = false, showModelName = false, showModelIcon = false, showTokenUsage = false)))

    @Before fun install() {
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
        conversation.value = fixture.conversation.copy(chatSuggestions = emptyList(), messageNodes = List(20) { index ->
            MessageNode.of(UIMessage.assistant("Ответ $index\nЕщё строка\nПоследняя строка"))
        })
    }
    @After fun close() { fixture.close() }

    private fun render(scaffoldPadding: Boolean = false) {
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        compose.setContent {
            ScreenshotTheme {
                state = rememberLazyListState()
                val haze = rememberHazeState()
                val toaster = rememberToasterState()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSettings provides settings.value, LocalTTSState provides tts,
                        LocalToaster provides toaster, LocalSharedTransitionScope provides this) {
                        Box(Modifier.requiredSize(411.dp, 600.dp)) {
                            if (scaffoldPadding) {
                                Scaffold(bottomBar = { Box(Modifier.height(bottomPadding.value)) }) { padding ->
                                    ChatList(padding, requireNotNull(conversation.value), state,
                                        loading.value, previewMode = false, settings = settings.value, hazeState = haze)
                                }
                            } else {
                                ChatList(PaddingValues(bottom = 80.dp), requireNotNull(conversation.value), state,
                                    loading.value, previewMode = false, settings = settings.value, hazeState = haze)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun growLastReply(lines: Int) {
        val current = requireNotNull(conversation.value)
        val node = current.messageNodes.last()
        conversation.value = current.copy(messageNodes = current.messageNodes.dropLast(1) + node.copy(messages = listOf(
            node.currentMessage.copy(parts = listOf(UIMessagePart.Text((1..lines).joinToString("\n") { "Строка потока $it" }))))))
    }

    @Test fun navigationButtonsMoveTheRealListAndModeSettingChangesMountedControls() {
        render()
        compose.onNodeWithContentDescription("Прокрутить вниз").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(state.canScrollBackward); assertFalse(state.canScrollForward) }
        compose.onNodeWithContentDescription("К началу чата").performClick()
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex); assertEquals(0, state.firstVisibleItemScrollOffset) }
        compose.runOnIdle { settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(messageNavButtonsMode = MessageNavButtonsMode.NEVER)) }
        compose.onNodeWithContentDescription("К началу чата").assertDoesNotExist()
        compose.onNodeWithContentDescription("Прокрутить вниз").assertDoesNotExist()
        compose.runOnIdle { settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(messageNavButtonsMode = MessageNavButtonsMode.SCROLL)) }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithContentDescription("К началу чата").assertDoesNotExist()
        compose.onNodeWithTag("chat_list_timeline").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("К началу чата").assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.canScrollBackward) }
    }

    @Test fun largeStreamChunkKeepsAnAlreadyBottomAlignedTimelineAtTheBottom() {
        loading.value = true
        render()
        compose.onNodeWithContentDescription("Прокрутить вниз").performClick()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.runOnIdle { growLastReply(20) }
        compose.runOnIdle { assertFalse("A stream chunk must retain following when the reader was at the bottom", state.canScrollForward) }
    }

    @Test fun autoHidingControlsDoesNotChangeMessageViewportOrReaderPosition() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(
            messageNavButtonsMode = MessageNavButtonsMode.SCROLL))
        render()
        compose.onNodeWithTag("chat_list_timeline").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("К началу чата").assertIsDisplayed()
        val viewport = state.layoutInfo.viewportSize
        val position = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithContentDescription("К началу чата").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Auto-hide must not resize the timeline", viewport, state.layoutInfo.viewportSize)
            assertEquals("Auto-hide must not move the reader's message", position,
                state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
    }

    @Test fun disablingAutoScrollKeepsThePositionWhenTheLastReplyGrows() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(enableAutoScroll = false))
        loading.value = true
        render()
        compose.onNodeWithContentDescription("Прокрутить вниз").performClick()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.runOnIdle { growLastReply(20) }
        compose.runOnIdle { assertTrue("Disabled auto-scroll must leave the old position", state.canScrollForward) }
    }

    @Test fun explicitTopNavigationDuringGenerationKeepsTheHistoryPosition() {
        loading.value = true
        render()
        compose.onNodeWithContentDescription("Прокрутить вниз").performClick()
        compose.onNodeWithContentDescription("К началу чата").performClick()
        compose.runOnIdle { growLastReply(20) }
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex); assertTrue(state.canScrollForward) }
    }

    @Test fun scaffoldComposerShrinkingDuringGenerationKeepsFollowingTheNextStreamChunk() {
        bottomPadding.value = 220.dp
        loading.value = true
        render(scaffoldPadding = true)
        compose.onNodeWithContentDescription("Прокрутить вниз").performClick()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.runOnIdle { bottomPadding.value = 80.dp }
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.runOnIdle { growLastReply(20) }
        compose.runOnIdle {
            assertFalse("Following must track the actual Scaffold composer geometry", state.canScrollForward)
        }
    }
}
