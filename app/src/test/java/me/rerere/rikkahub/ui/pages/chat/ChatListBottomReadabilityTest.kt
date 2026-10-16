package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.rememberHazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.screenshot.ChatBeforeFixture
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.ScreenshotThemeMode
import me.rerere.rikkahub.ui.screenshot.FixedSeedDynamicColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/** Real ChatList/messages/actions and Scaffold insets; composer/IME heights are controlled. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListBottomReadabilityTest internal constructor(
    private val theme: ScreenshotThemeMode,
    private val width: Int,
    private val fontScale: Float,
) {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture

    @Before fun install() {
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
    }
    @After fun close() { fixture.close() }

    @Test fun scrollToEndKeepsLastReplyAndActionsOpaqueWithCompactExpandedAndImeInsets() {
        val finished = LocalDateTime.parse("2026-01-02T12:00:02")
        val history = List(12) { index -> MessageNode(messages = listOf(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Исторический ответ $index.\nЕщё одна строка истории.\nЗавершение исторического ответа.")),
            finishedAt = finished,
        ))) }
        val lastMessage = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(LAST_TEXT)), finishedAt = finished)
        val conversation = fixture.conversation.copy(messageNodes = history + MessageNode(messages = listOf(lastMessage)),
            chatSuggestions = emptyList())
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(
            enableAssistantMarkdown = false, showModelName = false, showModelIcon = false,
            showTokenUsage = false, showUserMessageActions = false, hapticsEnabled = false,
            messageNavButtonsMode = MessageNavButtonsMode.ALWAYS,
        ))
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        val composerHeight = mutableStateOf(100.dp)
        val imeHeight = mutableStateOf(0.dp)
        lateinit var state: LazyListState
        var regenerations = 0
        val regeneratedIds = mutableListOf<kotlin.uuid.Uuid>()
        val lastReply = hasTestTag("chat_message_row") and hasAnyDescendant(hasText(LAST_TEXT))
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val toaster = rememberToasterState()
                state = rememberLazyListState()
                val haze = rememberHazeState()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSettings provides settings, LocalTTSState provides tts,
                        LocalToaster provides toaster, LocalSharedTransitionScope provides this) {
                        // Match ChatPage's real Surface, including the theme's inherited text colour.
                        Surface(Modifier.requiredSize(width.dp, 891.dp).testTag("readability_root"),
                            color = MaterialTheme.colorScheme.background) {
                            Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                topBar = {
                                    ChatHeader("Чат", false, false, false, false, avatar = {},
                                        onOpenDrawer = {}, onClickTitle = {}, onClickMenu = {}, onNewChat = {},
                                        onFiles = {}, onTerminal = {}, onBrowser = {}, showAvatar = false)
                                },
                                bottomBar = {
                                    Column(Modifier.fillMaxWidth().testTag("readability_composer_overlay")) {
                                        Box(Modifier.fillMaxWidth().height(composerHeight.value).background(Color.Cyan))
                                        Spacer(Modifier.height(imeHeight.value))
                                    }
                                }) { padding ->
                                ChatList(padding, conversation, state, false, previewMode = false,
                                    settings = settings, hazeState = haze, onRegenerate = {
                                        regenerations++
                                        regeneratedIds += it.id
                                    })
                            }
                        }
                    }
                }
            }
        }
        val regenerateLabel = RuntimeEnvironment.getApplication().getString(R.string.regenerate)
        listOf(100 to 0, 220 to 0, 100 to 300, 220 to 300).forEachIndexed { index, (composer, ime) ->
            compose.runOnIdle {
                composerHeight.value = composer.dp
                imeHeight.value = ime.dp
            }
            compose.waitForIdle()
            compose.runOnIdle { state.requestScrollToItem(state.layoutInfo.totalItemsCount - 1) }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("The fixture must actually scroll through history", state.canScrollBackward) }
            val text = compose.onNodeWithText(LAST_TEXT, useUnmergedTree = true).assertIsDisplayed()
            val actions = compose.onNode(hasTestTag("chat_message_assistant_actions") and hasAnyAncestor(lastReply))
                .assertIsDisplayed()
            val root = compose.onNodeWithTag("readability_root").fetchSemanticsNode().boundsInRoot
            val overlay = compose.onNodeWithTag("readability_composer_overlay").fetchSemanticsNode().boundsInRoot
            val timeline = compose.onNodeWithTag("chat_list_timeline").fetchSemanticsNode().boundsInRoot
            val navigation = compose.onNodeWithTag("chat_scroll_navigation").fetchSemanticsNode().boundsInRoot
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val actionBounds = actions.fetchSemanticsNode().boundsInRoot
            assertTrue("Last text stays above the $composer+$ime dp overlay", textBounds.bottom <= overlay.top)
            assertTrue("Last actions stay above the measured overlay", actionBounds.bottom <= overlay.top)
            assertEquals("The viewport reaches the composer without a separate navigation strip", overlay.top, timeline.bottom, .5f)
            assertEquals("Floating navigation ends at the measured composer/IME boundary", overlay.top, navigation.bottom, .5f)
            assertTrue("Navigation occupies only the corner", navigation.width < timeline.width / 2f)
            assertTrue("Final actions scroll entirely above the fading region and navigation", actionBounds.bottom <= navigation.top)
            assertTrue("Readability needs only the fade and ordinary message spacing", timeline.bottom - actionBounds.bottom in 48f..128f)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val lastLine = Rect(textBounds.left, textBounds.top + layout.getLineTop(layout.lineCount - 1),
                textBounds.right, textBounds.top + layout.getLineBottom(layout.lineCount - 1))
            val pixels = compose.onNodeWithTag("readability_root").captureToImage().toPixelMap()
            if (theme.dark) {
                assertTrue("The final line keeps light ink in a dark theme", lightestInk(pixels, lastLine, root) > .6f)
                assertTrue("Last actions keep light ink in a dark theme", lightestInk(pixels, actionBounds, root) > .45f)
            } else {
                assertTrue("The final line of the last reply keeps its contrast after scrollToEnd", darkestInk(pixels, lastLine, root) < .4f)
                assertTrue("Last action icons keep their contrast after scrollToEnd", darkestInk(pixels, actionBounds, root) < .55f)
            }
            compose.onNode(hasContentDescription(regenerateLabel) and hasAnyAncestor(lastReply))
                .assertHasClickAction().performClick()
            assertEquals(index + 1, regenerations)
            assertEquals("Every callback belongs to the final reply", List(index + 1) { lastMessage.id }, regeneratedIds)
        }
    }

    private fun darkestInk(pixels: PixelMap, bounds: Rect, root: Rect): Float {
        var darkest = 1f
        for (y in ceil(bounds.top - root.top).toInt() until floor(bounds.bottom - root.top).toInt()) {
            for (x in ceil(bounds.left - root.left).toInt() until floor(bounds.right - root.left).toInt()) {
                val color = pixels[x, y]
                darkest = minOf(darkest, (color.red + color.green + color.blue) / 3f)
            }
        }
        return darkest
    }

    private fun lightestInk(pixels: PixelMap, bounds: Rect, root: Rect): Float {
        var lightest = 0f
        for (y in ceil(bounds.top - root.top).toInt() until floor(bounds.bottom - root.top).toInt()) {
            for (x in ceil(bounds.left - root.left).toInt() until floor(bounds.right - root.left).toInt()) {
                val color = pixels[x, y]
                lightest = maxOf(lightest, (color.red + color.green + color.blue) / 3f)
            }
        }
        return lightest
    }

    companion object {
        private const val LAST_TEXT = "Последний ответ завершён.\nВторая строка остаётся читаемой.\nФинальная строка ответа."
        @JvmStatic @Parameters(name = "{0}_width{1}_font{2}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme ->
                listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { font -> arrayOf(theme, width, font) } }
            }
    }
}
