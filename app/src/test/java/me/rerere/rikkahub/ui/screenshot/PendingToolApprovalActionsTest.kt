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
import androidx.compose.ui.test.onNodeWithTag
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
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ApprovalCall("pending-action", true, "", ApprovalScope.Once, "root_exec"), calls.last())
        compose.onNodeWithTag("chat_timeline_approval_scopes:pending-action").assertIsEnabled().performClick()
        listOf(ToolApprovalAction.Always, ToolApprovalAction.ChatScope).forEach { action ->
            compose.onNodeWithText(action.label).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(ApprovalCall("pending-action", true, "", action.scope, "root_exec"), calls.last())
        }
        assertEquals(3, calls.size)
        compose.onNodeWithTag("chat_timeline_approval_deny:pending-action").assertIsEnabled()
    }

    @Test fun wrappedButtonsStayDisabledWhileSubmissionIsInFlight() {
        val save = Job()
        render(job = save)
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").assertIsNotEnabled()
        compose.onNodeWithTag("chat_timeline_approval_deny:pending-action").assertIsNotEnabled()
        compose.onNodeWithTag("chat_timeline_approval_scopes:pending-action").assertIsNotEnabled()
        assertEquals(1, calls.size)
        save.complete()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").assertIsEnabled()
        compose.onNodeWithTag("chat_timeline_approval_deny:pending-action").assertIsEnabled()
        compose.onNodeWithTag("chat_timeline_approval_scopes:pending-action").assertIsEnabled()
    }

    @Test fun browserJavaScriptStillOmitsAlwaysApproval() {
        render(toolName = "browser_eval_js", input = """{"code":"document.title"}""")
        compose.onNodeWithTag("chat_timeline_approval_scopes:pending-action").performClick()
        compose.onNodeWithText(ToolApprovalAction.Always.label).assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").assertIsEnabled()
        compose.onNodeWithText(ToolApprovalAction.ChatScope.label).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ApprovalCall("pending-action", true, "", ApprovalScope.ChatScope, "browser_eval_js"), calls.single())
    }

    @Test fun hardlineCommandStillRequiresPerCallApproval() {
        render(input = """{"command":"rm -rf /sdcard"}""")
        compose.onNodeWithText("Этот инструмент всегда требует отдельного подтверждения.").assertExists()
        compose.onNodeWithTag("chat_timeline_approval_once:pending-action").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(calls.single().approved)
        assertEquals(ApprovalScope.Once, calls.single().scope)
        compose.onNodeWithTag("chat_timeline_approval_scopes:pending-action").performClick()
        compose.onNodeWithText(ToolApprovalAction.Always.label).assertIsNotEnabled()
        compose.onNodeWithText(ToolApprovalAction.ChatScope.label).assertIsNotEnabled()
        assertEquals(1, calls.size)
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
