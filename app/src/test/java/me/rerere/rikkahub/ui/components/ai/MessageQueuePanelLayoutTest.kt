package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real queue panel with Russian labels and platform text metrics. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w600dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageQueuePanelLayoutTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val message = QueuedMessage(parts = listOf(UIMessagePart.Text("Проверить следующий шаг")))
    private val title = "Очередь приостановлена · 1"
    private val summary = "1. Проверить следующий шаг"

    @After fun restoreSystemFont() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun `queue content remains identifiable and controls reach their callbacks`() {
        RuntimeEnvironment.setFontScale(fontScale)
        var resumed = 0
        var edited = 0
        var removed = 0
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Box(Modifier.requiredWidth(width.dp).padding(horizontal = 12.dp)) {
                    MessageQueuePanel(
                        state = MessageQueueState(messages = listOf(message), paused = true),
                        onRemove = { assertEquals(message.id, it); removed++ },
                        onBeginEdit = { assertEquals(message.id, it); edited++; null },
                        onFinishEdit = { _, _ -> error("No edit dialog opened") },
                        onResume = { resumed++ },
                    )
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val titleLayout = layout(title)
        val summaryLayout = layout(summary)
        assertFalse("Queue summary must show identifiable content, not only an ellipsis", summaryLayout.isLineEllipsized(summaryLayout.lineCount - 1))
        assertEquals("Fixture summary must remain fully readable", summary.length, summaryLayout.getLineEnd(summaryLayout.lineCount - 1))
        assertTrue("Queue status must not break into fragments: ${titleLayout.lineCount} lines", titleLayout.lineCount <= 2)
        if (width == 600 || (width == 411 && fontScale == 1f)) {
            val summaryBounds = compose.onNodeWithText(summary).fetchSemanticsNode().boundsInRoot
            val editBounds = compose.onNodeWithText("Редактировать").fetchSemanticsNode().boundsInRoot
            assertEquals("Wide queue preserves inline actions", summaryBounds.center.y, editBounds.center.y, 1f)
        }
        compose.onNodeWithText("Возобновить отправку").assertIsDisplayed().performClick()
        compose.onNodeWithText("Редактировать").assertIsDisplayed().performClick()
        compose.onNodeWithText("Удалить").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(1, edited); assertEquals(1, removed) }
    }

    companion object {
        @JvmStatic @Parameters(name = "width{0}_font{1}") fun cases(): List<Array<Any>> =
            listOf(320, 411, 600).flatMap { width -> listOf(1f, 1.3f).map { arrayOf(width, it) } }
    }
}
