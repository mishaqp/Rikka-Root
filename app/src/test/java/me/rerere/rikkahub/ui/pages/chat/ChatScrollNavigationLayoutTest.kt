package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the production circles and gutter with narrow message constraints. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScrollNavigationLayoutTest(private val width: Int, private val fontScale: Float, private val onLeft: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `36dp circles and their touch targets fit outside message content`() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Box(Modifier.requiredSize(width.dp, 240.dp)) {
                    Box(Modifier.fillMaxSize().padding(chatScrollNavigationGutter(enabled.value, onLeft)).padding(16.dp)) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("scroll_message_content")) {
                            items(8) { Box(Modifier.height(80.dp)) }
                        }
                    }
                    MessageJumper(show = enabled.value, onLeft = onLeft, scope = scope, state = state)
                }
            }
        }

        val content = compose.onNodeWithTag("scroll_message_content").fetchSemanticsNode().boundsInRoot
        assertEquals(if (onLeft) 52f else 16f, content.left, .5f)
        assertEquals(width - if (onLeft) 16f else 52f, content.right, .5f)
        listOf("К началу чата", "Предыдущее сообщение", "Следующее сообщение", "Прокрутить вниз").forEach { label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
            assertEquals("$label has a 36dp circle", 36f, node.boundsInRoot.width, .5f)
            assertEquals("$label has a 36dp circle", 36f, node.boundsInRoot.height, .5f)
            assertTrue("$label retains a minimum touch target", node.touchBoundsInRoot.width >= 48f)
            assertTrue("$label fits beside the message content", if (onLeft) {
                node.touchBoundsInRoot.right <= content.left
            } else {
                node.touchBoundsInRoot.left >= content.right
            })
        }

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("К началу чата").assertDoesNotExist()
        val fullContent = compose.onNodeWithTag("scroll_message_content").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, fullContent.left, .5f)
        assertEquals(width - 16f, fullContent.right, .5f)
    }

    companion object {
        @JvmStatic @Parameters(name = "width{0}_font{1}_left{2}") fun cases(): List<Array<Any>> =
            listOf(320, 411).flatMap { width ->
                listOf(1f, 1.3f).flatMap { font -> listOf(false, true).map { arrayOf(width, font, it) } }
            }
    }
}
