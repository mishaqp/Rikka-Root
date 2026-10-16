package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkflowApprovalScrollAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `audit 12 long workflow approval arguments have a scrollable parent`() {
        val tail = "Последняя строка"
        val content = "строка\\n".repeat(120) + tail
        val input = """{"name":"Проверка","trigger":{"type":"manual"},"actions":[{"tool":"write_text_file","args":{"path":"/sdcard/check.txt","content":"$content"}}]}"""
        val tool = UIMessagePart.Tool("workflow-scroll", "workflow_create", input,
            approvalState = ToolApprovalState.Pending)
        val preview = WorkflowApprovalRenderer.renderPlain(tool.toolName, input)
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Light, 1f) {
                Surface {
                    Box(Modifier.width(320.dp)) {
                        ChainOfThought(steps = listOf(tool)) {
                            ChatMessageToolStep(it, onToolApproval = { _, _, _, _, _ -> Job().apply { complete() } })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(preview)), useUnmergedTree = true)
        scroll.assertExists()
        assertTrue("The actual rendered approval must include the last action argument", preview.contains(tail))
        val text = compose.onNodeWithText(preview, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val tailGlyph = layouts.single().getBoundingBox(preview.indexOf(tail))
        val initialViewport = scroll.fetchSemanticsNode().boundsInRoot
        assertTrue("The long fixture must start with its last line outside the viewport",
            text.fetchSemanticsNode().positionInRoot.y + tailGlyph.top > initialViewport.bottom)
        assertTrue("The approval must actually overflow its scroll container",
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        for (attempt in 0 until 40) {
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.maxValue() - range.value() <= 1f) break
            scroll.performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Touch gestures must reach the end of the approval", range.maxValue(), range.value(), 1f)
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        val textTop = text.fetchSemanticsNode().positionInRoot.y
        assertTrue("The first glyph of the last argument line must be visible after scrolling",
            textTop + tailGlyph.top >= viewport.top && textTop + tailGlyph.bottom <= viewport.bottom)
    }
}
