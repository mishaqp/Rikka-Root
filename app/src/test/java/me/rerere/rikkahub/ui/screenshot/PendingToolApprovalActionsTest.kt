package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatService.ApprovalScope
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.ToolApprovalAction
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingToolApprovalActionsTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<ApprovalCall>()

    @Test fun wrappedApprovalButtonsDispatchOriginalScopesAndToolIdentity() {
        render()
        ToolApprovalAction.entries.filter { it.approved }.forEach { action ->
            compose.onNodeWithText(action.label).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(ApprovalCall("pending-action", true, "", action.scope, "root_exec"), calls.last())
        }
        assertEquals(3, calls.size)
        compose.onNodeWithText(ToolApprovalAction.Deny.label).assertIsEnabled()
    }

    @Test fun wrappedButtonsStayDisabledWhileSubmissionIsInFlight() {
        val save = Job()
        render(job = save)
        compose.onNodeWithText(ToolApprovalAction.Once.label).performClick()
        compose.waitForIdle()
        ToolApprovalAction.entries.forEach { compose.onNodeWithText(it.label).assertIsNotEnabled() }
        assertEquals(1, calls.size)
        save.complete()
        compose.waitForIdle()
        ToolApprovalAction.entries.forEach { compose.onNodeWithText(it.label).assertIsEnabled() }
    }

    @Test fun browserJavaScriptStillOmitsAlwaysApproval() {
        render(toolName = "browser_eval_js", input = """{"code":"document.title"}""")
        compose.onNodeWithText(ToolApprovalAction.Always.label).assertDoesNotExist()
        compose.onNodeWithText(ToolApprovalAction.Once.label).assertIsEnabled()
        compose.onNodeWithText(ToolApprovalAction.ChatScope.label).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ApprovalCall("pending-action", true, "", ApprovalScope.ChatScope, "browser_eval_js"), calls.single())
    }

    @Test fun hardlineCommandStillRequiresPerCallApproval() {
        render(input = """{"command":"rm -rf /sdcard"}""")
        compose.onNodeWithText("Этот инструмент всегда требует отдельного подтверждения.").assertExists()
        compose.onNodeWithText(ToolApprovalAction.Always.label).assertIsNotEnabled()
        compose.onNodeWithText(ToolApprovalAction.ChatScope.label).assertIsNotEnabled()
        compose.onNodeWithText(ToolApprovalAction.Once.label).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(calls.single().approved)
        assertEquals(ApprovalScope.Once, calls.single().scope)
    }

    private fun render(
        toolName: String = "root_exec",
        input: String = """{"command":"ls /sdcard"}""",
        job: Job? = null,
    ) {
        val tool = UIMessagePart.Tool("pending-action", toolName, input, approvalState = ToolApprovalState.Pending)
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Amoled, 1.3f) {
                Surface {
                    Box(Modifier.width(320.dp)) {
                        ChainOfThought(steps = listOf(tool)) {
                            ChatMessageToolStep(it, onToolApproval = { id, approved, reason, scope, name ->
                                calls += ApprovalCall(id, approved, reason, scope, name)
                                job ?: Job().apply { complete() }
                            })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private data class ApprovalCall(val id: String, val approved: Boolean, val reason: String, val scope: ApprovalScope, val name: String)
}
