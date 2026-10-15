package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.root.executeRootToolOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/** Protects the production observation of runtime checkpoints, without executing commands. */
class RunningToolPresentationTest {
    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000101")
    private val messageId = Uuid.parse("00000000-0000-0000-0000-000000000102")
    private val nodeId = Uuid.parse("00000000-0000-0000-0000-000000000103")
    private val root = UIMessagePart.Tool("root-1", "root_exec", "{\"command\":\"printf hello\\nsleep 2\"}", approvalState = ToolApprovalState.Approved)

    @Test fun approvedAndAutomaticCallsWithoutExecutionCheckpointStayHidden() {
        listOf(ToolApprovalState.Approved, ToolApprovalState.Auto).forEach { approval ->
            val observed = observeRunningTools(RunningToolObservation(), conversation(root.copy(approvalState = approval)), true, 1_000)
            assertNull(observed.presentation)
        }
    }

    @Test fun actualRootCheckpointStartsObservedTimerAndDoesNotRenderRecoveryErrorAsOutput() {
        runBlocking {
            val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
            executeRootToolOnce(root, persistStarted = { checkpoint ->
                val active = observeRunningTools(initial, conversation(checkpoint), true, 2_000).presentation!!
                assertEquals("root-1", active.toolCallId)
                assertEquals("printf hello", active.command)
                assertNull(active.latestOutputLine)
                assertEquals("Ожидание вывода", active.outputLabel)
                assertEquals(2_000L, active.observedAtMillis)
                assertEquals(0, active.otherActiveCount)
            }) { listOf(UIMessagePart.Text("completed")) }
        }
    }

    @Test fun booleanMarkersRecognizeRussianRootAndGenericCheckpoints() {
        listOf(root, UIMessagePart.Tool("file", "read_file", "{}", approvalState = ToolApprovalState.Auto)).forEach { tool ->
            val initial = observeRunningTools(RunningToolObservation(), conversation(tool), true, 1_000)
            val checkpoint = tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
                put("error", if (tool.toolName == "root_exec") "root_execution_indeterminate" else "tool_execution_indeterminate")
                put("checkpointed_before_launch", true)
                put("reason", "Вызов сохранён перед запуском. Не повторяйте его автоматически.")
            }.toString())))
            val active = observeRunningTools(initial, conversation(checkpoint), true, 2_000)
            assertEquals(tool.toolCallId, active.activeClientKey?.toolCallId)
            if (tool.toolName == "root_exec") assertEquals(tool.toolCallId, active.presentation?.toolCallId)
            else assertNull(active.presentation)
        }
    }

    @Test fun pendingDeniedAndAnsweredNeverBecomeActiveEvenWithPlaceholderOutput() {
        listOf(ToolApprovalState.Pending, ToolApprovalState.Denied("Нет"), ToolApprovalState.Answered("Да")).forEach { approval ->
            val initial = observeRunningTools(RunningToolObservation(), conversation(root.copy(approvalState = approval)), true, 1_000)
            assertNull(observeRunningTools(initial, conversation(checkpoint(root.copy(approvalState = approval))), true, 2_000).presentation)
        }
    }

    @Test fun openingExistingRecoveryCheckpointDoesNotClaimItIsRunning() {
        assertNull(observeRunningTools(RunningToolObservation(), conversation(checkpoint(root)), true, 1_000).presentation)
    }

    @Test fun finishingOrStoppingClearsStripAndRetainedCheckpointCannotRestartTimer() {
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        val running = observeRunningTools(initial, conversation(checkpoint(root)), true, 2_000)
        assertNull(observeRunningTools(running, conversation(root.copy(output = listOf(UIMessagePart.Text("hello")))), true, 3_000).presentation)
        val stopped = observeRunningTools(running, conversation(checkpoint(root)), false, 3_000)
        assertNull(stopped.presentation)
        assertNull(observeRunningTools(stopped, conversation(checkpoint(root)), true, 4_000).presentation)
    }

    @Test fun sequentialSiblingsUseNewestFreshCheckpointAndDoNotCountOldSentinelsAsParallel() {
        val second = UIMessagePart.Tool("ssh-2", "ssh_exec_saved", "{\"command\":\"uptime\"}")
        val initial = observeRunningTools(RunningToolObservation(), conversation(root, second), true, 1_000)
        val first = observeRunningTools(initial, conversation(checkpoint(root), second), true, 2_000)
        val next = observeRunningTools(first, conversation(checkpoint(root), checkpoint(second)), true, 3_000).presentation!!
        assertEquals("ssh-2", next.toolCallId)
        assertEquals("uptime", next.command)
        assertEquals(0, next.otherActiveCount)
        assertEquals(3_000L, next.observedAtMillis)
    }

    @Test fun currentActiveTimerSurvivesUnrelatedStreamingUpdates() {
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        val running = observeRunningTools(initial, conversation(checkpoint(root)), true, 2_000)
        val updated = observeRunningTools(running, conversation(checkpoint(root), UIMessagePart.Text("Следующий текст")), true, 8_000)
        assertEquals(2_000L, updated.presentation!!.observedAtMillis)
    }

    @Test fun serverToolsNeverRenderAsForegroundTerminalCommands() {
        listOf("code_execution", "web_search", "root_exec").forEach { name ->
            val server = UIMessagePart.ServerTool("server-1", name, output = JsonPrimitive("first\nlatest\n"), status = ServerToolStatus.IN_PROGRESS)
            val initial = observeRunningTools(RunningToolObservation(), conversation(), true, 500)
            assertNull(observeRunningTools(initial, conversation(server), true, 1_000).presentation)
        }
    }

    @Test fun existingServerInProgressStatusNeedsFreshGenerationProvenance() {
        val server = UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.IN_PROGRESS)
        val existing = observeRunningTools(RunningToolObservation(), conversation(server), true, 1_000)
        assertNull(existing.presentation)
        val stopped = observeRunningTools(existing, conversation(server), false, 2_000)
        assertNull(observeRunningTools(stopped, conversation(server), true, 3_000).presentation)
    }

    @Test fun serverToolsDoNotIncreaseForegroundShellParallelCount() {
        val server = UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.IN_PROGRESS)
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        val active = observeRunningTools(initial, conversation(checkpoint(root), server), true, 2_000).presentation!!
        assertEquals("root-1", active.toolCallId)
        assertEquals(0, active.otherActiveCount)
        assertEquals("Ожидание вывода", active.outputLabel)
    }

    @Test fun pendingSiblingDoesNotHideActualAutomaticallyExecutingTool() {
        val pending = root.copy(toolCallId = "pending-root", approvalState = ToolApprovalState.Pending)
        val automatic = UIMessagePart.Tool("workspace-1", "workspace_shell", "{\"command\":\"pwd\"}")
        val initial = observeRunningTools(RunningToolObservation(), conversation(pending, automatic), true, 1_000)
        val active = observeRunningTools(initial, conversation(pending, checkpoint(automatic)), true, 2_000).presentation!!
        assertEquals("workspace-1", active.toolCallId)
        assertEquals(0, active.otherActiveCount)
    }

    @Test fun changingSelectedBranchInvalidatesClientObservationAndNeverRestoresOldRunningClaim() {
        val alternateId = Uuid.parse("00000000-0000-0000-0000-000000000104")
        val alternate = UIMessage(id = alternateId, role = MessageRole.ASSISTANT, parts = listOf(checkpoint(root.copy(toolCallId = "alternate"))))
        val initialConversation = conversation(root).let { it.copy(messageNodes = listOf(it.messageNodes.single().copy(messages = it.messageNodes.single().messages + alternate))) }
        val initial = observeRunningTools(RunningToolObservation(), initialConversation, true, 1_000)
        val runningConversation = conversation(checkpoint(root)).let { it.copy(messageNodes = listOf(it.messageNodes.single().copy(messages = it.messageNodes.single().messages + alternate))) }
        val running = observeRunningTools(initial, runningConversation, true, 2_000)
        val switchedConversation = runningConversation.copy(messageNodes = listOf(MessageNode(id = nodeId, messages = listOf(runningConversation.currentMessages.single(), alternate), selectIndex = 1)))
        val switched = observeRunningTools(running, switchedConversation, true, 3_000)
        assertNull(switched.presentation)
        assertNull(observeRunningTools(switched, runningConversation, true, 4_000).presentation)
    }

    @Test fun conflatedNewAssistantNodeCanStartFromItsFreshCheckpoint() {
        val earlier = conversation(UIMessagePart.Text("Предыдущий ответ"))
        val initial = observeRunningTools(RunningToolObservation(), earlier, true, 1_000)
        val nextNode = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(checkpoint(root))))
        val active = observeRunningTools(initial, earlier.copy(messageNodes = earlier.messageNodes + nextNode), true, 2_000).presentation!!
        assertEquals("root-1", active.toolCallId)
        assertEquals(2_000L, active.observedAtMillis)
    }

    @Test fun firstServerChunkOnNewAssistantMessageDoesNotEnterCommandStrip() {
        val user = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Найди"))))
        val earlier = Conversation.ofId(conversationId, messages = listOf(user))
        val initial = observeRunningTools(RunningToolObservation(), earlier, true, 1_000)
        val server = UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.IN_PROGRESS)
        val response = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(server)))
        assertNull(observeRunningTools(initial, earlier.copy(messageNodes = earlier.messageNodes + response), true, 2_000).presentation)
    }

    @Test fun nonCommandClientCheckpointsNeverEnterForegroundCommandStrip() {
        listOf(
            "browser_eval_js", "browser_get_dom", "mcp_add", "calendar_query", "search_web", "mcp_calendar_query",
            "workspace_background_start", "workspace_background_output", "workspace_background_stop",
            "termux_session_send", "termux_session_read", "termux_session_kill", "termux_session_list", "root_exec_extra",
        ).forEach { name ->
            val tool = UIMessagePart.Tool("call-1", name, "{\"command\":\"uptime\",\"input\":\"whoami\",\"session_id\":\"session\"}")
            val initial = observeRunningTools(RunningToolObservation(), conversation(tool), true, 1_000)
            assertNull("$name is outside stage 1 command scope", observeRunningTools(initial, conversation(checkpoint(tool)), true, 2_000).presentation)
        }
    }

    @Test fun newerNonCommandCheckpointHidesEarlierFinishedShellSentinel() {
        val browser = UIMessagePart.Tool("browser-2", "browser_get_dom", "{}")
        val initial = observeRunningTools(RunningToolObservation(), conversation(root, browser), true, 1_000)
        val shell = observeRunningTools(initial, conversation(checkpoint(root), browser), true, 2_000)
        assertEquals("root-1", shell.presentation!!.toolCallId)
        val unsupported = observeRunningTools(shell, conversation(checkpoint(root), checkpoint(browser)), true, 3_000)
        assertNull(unsupported.presentation)
        assertNull(observeRunningTools(unsupported, conversation(checkpoint(root), checkpoint(browser)), true, 4_000).presentation)
    }

    @Test fun launchingTermuxSessionDoesNotClaimForegroundCancellableCommandExecution() {
        listOf("{}", "{\"command\":\"  \"}", "{\"command\":\"ssh myhost\"}").forEach { input ->
            val session = UIMessagePart.Tool("session-1", "termux_session_start", input)
            val initial = observeRunningTools(RunningToolObservation(), conversation(session), true, 1_000)
            assertNull(observeRunningTools(initial, conversation(checkpoint(session)), true, 2_000).presentation)
        }
    }

    @Test fun detachedAndInteractiveTerminalLaunchesStayOutsideForegroundCommandStrip() {
        listOf(
            "termux_run_command" to "{\"command\":\"uptime\",\"background\":true}",
            "termux_run_command" to "{\"command\":\"uptime\",\"interactive\":true}",
            "ssh_exec" to "{\"command\":\"uptime\",\"background\":true}",
            "ssh_exec_saved" to "{\"command\":\"uptime\",\"background\":\"true\"}",
        ).forEach { (name, input) ->
            val tool = UIMessagePart.Tool("detached-1", name, input)
            val initial = observeRunningTools(RunningToolObservation(), conversation(tool), true, 1_000)
            assertNull(observeRunningTools(initial, conversation(checkpoint(tool)), true, 2_000).presentation)
        }
    }

    @Test fun conflatedNewRegeneratedAlternativeCanStartButSelectingKnownAlternativeCannot() {
        val earlier = conversation(UIMessagePart.Text("Предыдущий ответ"))
        val initial = observeRunningTools(RunningToolObservation(), earlier, true, 1_000)
        val newMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(checkpoint(root)))
        val regenerated = earlier.copy(messageNodes = listOf(earlier.messageNodes.single().copy(messages = earlier.currentMessages + newMessage, selectIndex = 1)))
        val active = observeRunningTools(initial, regenerated, true, 2_000)
        assertEquals("root-1", active.presentation!!.toolCallId)
        val originalBranch = regenerated.copy(messageNodes = listOf(regenerated.messageNodes.single().copy(selectIndex = 0)))
        val original = observeRunningTools(active, originalBranch, true, 3_000)
        assertNull(original.presentation)
        assertNull(observeRunningTools(original, regenerated, true, 4_000).presentation)
    }

    @Test fun regeneratingEarlierAssistantReplyTracksItsToolWithoutDiscardingLaterMessages() {
        val later = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Более поздний ответ"))))
        val earlier = conversation(UIMessagePart.Text("Старый ответ")).copy(messageNodes = conversation(UIMessagePart.Text("Старый ответ")).messageNodes + later)
        val initial = observeRunningTools(RunningToolObservation(), earlier, true, 1_000)
        val regenerated = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(checkpoint(root)))
        val during = earlier.copy(messageNodes = listOf(earlier.messageNodes.first().copy(messages = earlier.messageNodes.first().messages + regenerated, selectIndex = 1), later))
        assertEquals("root-1", observeRunningTools(initial, during, true, 2_000).presentation!!.toolCallId)
    }

    @Test fun olderMessageCheckpointsAndNonAssistantOrIncompleteHistoryStayHidden() {
        val earlier = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(checkpoint(root))))
        val current = conversation(UIMessagePart.Text("next")).copy(messageNodes = listOf(earlier) + conversation().messageNodes)
        assertNull(observeRunningTools(RunningToolObservation(), current, true, 1_000).presentation)
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        val nonAssistant = conversation(checkpoint(root)).copy(messageNodes = listOf(MessageNode(id = nodeId, messages = listOf(UIMessage(id = messageId, role = MessageRole.USER, parts = listOf(checkpoint(root)))))))
        assertNull(observeRunningTools(initial, nonAssistant, true, 2_000).presentation)
        assertNull(observeRunningTools(initial, conversation(checkpoint(root)).copy(historyLoadState = HistoryLoadState.INCOMPLETE), true, 2_000).presentation)
    }

    @Test fun newConversationNeverInheritsPreviousActiveState() {
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        val running = observeRunningTools(initial, conversation(checkpoint(root)), true, 2_000)
        assertNull(observeRunningTools(running, conversation(checkpoint(root)).copy(id = Uuid.random()), true, 3_000).presentation)
    }

    @Test fun shellArgumentsUseExistingFieldNamesAndMalformedInputHasRussianFallback() {
        val inputs = listOf(
            UIMessagePart.Tool("termux", "termux_run_command", "{\"executable\":\"/bin/sh\",\"arguments\":[\"-c\",\"uptime\"]}") to "/bin/sh -c uptime",
            UIMessagePart.Tool("termux-command", "termux_run_command", "{\"command\":\"uptime\",\"arguments\":[\"ignored-by-runtime\"]}") to "uptime",
            UIMessagePart.Tool("ssh", "ssh_exec", "{\"command\":\"df -h\"}") to "df -h",
            UIMessagePart.Tool("ssh-saved", "ssh_exec_saved", "{\"command\":\"uptime\"}") to "uptime",
            UIMessagePart.Tool("workspace", "workspace_shell", "{\"command\":\"pwd\"}") to "pwd",
            UIMessagePart.Tool("bad", "root_exec", "{broken") to "Команда root",
        )
        inputs.forEach { (tool, label) ->
            val initial = observeRunningTools(RunningToolObservation(), conversation(tool), true, 1_000)
            assertEquals(label, observeRunningTools(initial, conversation(checkpoint(tool)), true, 2_000).presentation!!.command)
        }
    }

    @Test fun ordinaryToolResultsNeverBecomeRunningCheckpoints() {
        val initial = observeRunningTools(RunningToolObservation(), conversation(root), true, 1_000)
        listOf("{\"stdout\":\"hello\"}", "{\"error\":\"command_timeout\"}", "completed").forEach { output ->
            assertNull(observeRunningTools(initial, conversation(root.copy(output = listOf(UIMessagePart.Text(output)))), true, 2_000).presentation)
        }
    }

    @Test fun elapsedUsesObservedMonotonicTimeWithRussianSecondsAndClampsNegativeTime() {
        assertEquals("0 с", runningToolElapsed(2_000, 1_000))
        assertEquals("9 с", runningToolElapsed(2_000, 11_000))
        assertEquals("1:05", runningToolElapsed(2_000, 67_000))
    }

    private fun conversation(vararg parts: UIMessagePart) = Conversation.ofId(conversationId, messages = listOf(
        MessageNode(id = nodeId, messages = listOf(UIMessage(id = messageId, role = MessageRole.ASSISTANT, parts = parts.toList())))
    ))

    private fun checkpoint(tool: UIMessagePart.Tool) = tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
        put("error", if (tool.toolName == "root_exec") "root_execution_indeterminate" else "tool_execution_indeterminate")
        put("reason", "This call was checkpointed before launch.")
    }.toString())))
}
