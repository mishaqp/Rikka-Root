package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.ToolApprovalAction
import me.rerere.rikkahub.ui.components.message.presentToolHeadline
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the real pending tool renderer, including the actual scope buttons. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingToolApprovalLayoutTest internal constructor(
    private val theme: ScreenshotThemeMode,
    private val fontScale: Float,
    private val width: Int,
) {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingToolIdentityAndWrappedActionsRemainVisibleInsideCard() {
        val tool = UIMessagePart.Tool(
            toolCallId = "pending-root-layout",
            toolName = "root_exec",
            input = """{"command":"ls /sdcard"}""",
            approvalState = ToolApprovalState.Pending,
        )
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface {
                    Box(Modifier.width(width.dp)) {
                        ChainOfThought(
                            modifier = Modifier.fillMaxWidth().testTag("approval_card"),
                            steps = listOf(tool),
                        ) {
                            ChatMessageToolStep(it, onToolApproval = { _, _, _, _, _ -> Job().apply { complete() } })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val card = compose.onNodeWithTag("approval_card").fetchSemanticsNode().boundsInRoot
        // The step title carries the call's essence instead of the bare tool name.
        val title = presentToolHeadline(tool.toolName, tool.inputAsJson())!!
        assertEquals("Root: ls /sdcard", title)
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        assertTrue("Pending tool title must have positive visible width: $titleBounds", titleBounds.width > 0f)
        assertTrue("Pending tool title must have positive visible height: $titleBounds", titleBounds.height > 0f)
        assertContained("tool title", card, titleBounds)

        val layouts = mutableListOf<TextLayoutResult>()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The production title must expose its text layout", 1, layouts.size)
        val layout = layouts.single()
        (title.indices).forEach { offset ->
            val glyph = layout.getBoundingBox(offset)
            assertTrue("Tool title glyph at $offset must have visible width: $glyph", glyph.width > 0f)
            assertContained("tool title glyph at $offset", Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat()), glyph)
            assertFalse("Tool title must not be ellipsized", layout.isLineEllipsized(layout.getLineForOffset(offset)))
        }

        val actions = listOf("chat_timeline_approval_once:pending-root-layout",
            "chat_timeline_approval_deny:pending-root-layout", "chat_timeline_approval_scopes:pending-root-layout").map { tag ->
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.also { bounds ->
                assertTrue("$tag must be visible", bounds.width > 0f && bounds.height > 0f)
                assertContained(tag, card, bounds)
                assertFalse("$tag must not overlap the tool title", bounds.overlaps(titleBounds))
            }
        }
        actions.forEachIndexed { index, bounds ->
            actions.drop(index + 1).forEach { other ->
                assertFalse("Scope actions must not overlap: $bounds / $other", bounds.overlaps(other))
            }
        }
        assertEquals("Compact approval controls stay on one row at every supported font and width",
            1, actions.map { it.center.y }.distinct().size)
        actions.forEach { bounds ->
            assertTrue("Approval target must remain comfortably touchable: $bounds", bounds.width >= 40f && bounds.height >= 40f)
        }
    }

    private fun assertContained(name: String, outer: Rect, inner: Rect) {
        assertTrue("$name must stay inside $outer: $inner",
            inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f &&
                inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f)
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}_font{1}_width{2}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme ->
                listOf(1f, 1.3f).flatMap { font -> listOf(320, 360, 411).map { arrayOf(theme, font, it) } }
            }
    }
}
