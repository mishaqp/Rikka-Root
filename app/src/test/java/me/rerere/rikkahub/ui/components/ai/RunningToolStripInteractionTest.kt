package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real observation and stop control; no runtime, fake process, or terminal output provider. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class RunningToolStripInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val tool = UIMessagePart.Tool("root-1", "root_exec", "{\"command\":\"sleep 10\"}", approvalState = ToolApprovalState.Approved)
    private val initial = Conversation.ofId(kotlin.uuid.Uuid.random(), messages = listOf(
        MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
    ))

    @Test fun freshCheckpointShowsWaitingAndStopDispatchesExistingCallback() {
        val conversation = mutableStateOf(initial)
        val loading = mutableStateOf(true)
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatRunningToolStrip(conversation.value, loading.value, onStop = { stops++; loading.value = false })
            }
        }
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
        compose.runOnIdle { conversation.value = checkpointConversation() }
        compose.onNodeWithText("sleep 10").assertExists()
        compose.onNodeWithText("Ожидание вывода").assertExists()
        compose.onNodeWithTag("running-tool-bar-stop").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).performClick()
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun existingCheckpointOnPageOpeningDoesNotShowAStaleRunningClaim() {
        compose.setContent { MaterialTheme { ChatRunningToolStrip(checkpointConversation(), true, onStop = {}) } }
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
    }

    @Test fun selectingKnownBranchRemovesObservedInvocationImmediately() {
        val alternate = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool.copy(toolCallId = "old-branch", output = checkpointOutput)))
        val seeded = initial.copy(messageNodes = listOf(initial.messageNodes.single().copy(messages = initial.currentMessages + alternate)))
        val conversation = mutableStateOf(seeded)
        compose.setContent { MaterialTheme { ChatRunningToolStrip(conversation.value, true, onStop = {}) } }
        compose.runOnIdle {
            conversation.value = checkpointConversation().let { it.copy(messageNodes = listOf(it.messageNodes.single().copy(messages = it.currentMessages + alternate))) }
        }
        compose.onNodeWithTag("running-tool-bar").assertExists()
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(conversation.value.messageNodes.single().copy(selectIndex = 1))) }
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
    }

    private val checkpointOutput = listOf(UIMessagePart.Text("{\"error\":\"root_execution_indeterminate\",\"reason\":\"This root command was checkpointed before launch.\"}"))

    private fun checkpointConversation() = initial.copy(messageNodes = listOf(initial.messageNodes.single().copy(
        messages = listOf(initial.currentMessages.single().copy(parts = listOf(tool.copy(output = checkpointOutput))))
    )))
}
