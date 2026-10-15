package me.rerere.rikkahub.ui.components.message

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelinePresentationTest {
    private val options = ToolDisplayOptions(
        showSummary = true,
        hideResultImages = false,
        autoCollapseSteps = true,
        showThinkingContent = true,
        autoCloseThinking = true,
    )

    @Test fun pendingAnswerAndApprovalRemainVisibleBeforeTheLastTwoSteps() {
        val steps = listOf(
            tool("ask", "ask_user", approval = ToolApprovalState.Pending),
            tool("approve", "root_exec", approval = ToolApprovalState.Pending),
            tool("done-1", output = "{}"),
            tool("done-2", output = "{}"),
            tool("done-3", output = "{}"),
        )
        val result = presentTimeline(steps, options, loading = false, messageKey = "message", blockOrdinal = 2)

        assertEquals(listOf(0, 1, 3, 4), result.collapsedVisibleIndices)
        assertEquals(1, result.hiddenCount)
        assertFalse(result.canFoldToSummary)
        assertEquals(TimelineStatus.AwaitingAnswer, result.steps[0].status)
        assertEquals(TimelineStatus.AwaitingApproval, result.steps[1].status)
        assertTrue(result.steps[0].key.contains("message"))
        assertTrue(result.steps[0].key.contains("ask"))
        assertTrue(result.steps[0].key != result.steps[1].key)
    }

    @Test fun failureDenialAndCheckpointPreventCompletedSummary() {
        val checkpoint = """{"error":"root_execution_indeterminate","reason":"checkpointed before launch"}"""
        val steps = listOf(
            tool("denied", approval = ToolApprovalState.Denied("blocked")),
            tool("failed", output = """{"success":false}"""),
            tool("checkpoint", "root_exec", output = checkpoint),
            tool("tail-1", output = "{}"),
            tool("tail-2", output = "{}"),
        )
        val result = presentTimeline(steps, options, loading = false, messageKey = "m", blockOrdinal = 0)

        assertEquals(listOf(0, 1, 2, 3, 4), result.collapsedVisibleIndices)
        assertFalse(result.canFoldToSummary)
        assertEquals(TimelineStatus.Denied, result.steps[0].status)
        assertEquals(TimelineStatus.Failed, result.steps[1].status)
        assertEquals(TimelineStatus.Indeterminate, result.steps[2].status)
    }

    @Test fun nonemptyPlainOutputIsNeutralAndNeverInventsToolSeconds() {
        val result = presentTimeline(
            listOf(tool("plain", output = "finished"), tool("json", output = "{}")),
            options, loading = false, messageKey = "m", blockOrdinal = 0,
        )

        assertEquals(TimelineStatus.ResultReceived, result.steps[0].status)
        assertTrue(result.canFoldToSummary)
        assertNull(result.duration)
        assertEquals(2, result.steps.size)
    }

    @Test fun reasoningDurationUsesActualMarksAndOpenThinkingPreventsWholeFold() {
        val start = Instant.parse("2026-01-02T03:04:05Z")
        val reasoning = ThinkingStep.ReasoningStep(UIMessagePart.Reasoning(
            reasoning = "Think", createdAt = start, finishedAt = start + kotlin.time.Duration.parse("2.4s"),
        ))
        val finished = presentTimeline(listOf(reasoning), options, false, "m", 0)
        assertEquals(kotlin.time.Duration.parse("2.4s"), finished.duration)
        assertTrue(finished.canFoldToSummary)

        val keepOpen = presentTimeline(listOf(reasoning), options.copy(autoCloseThinking = false), false, "m", 0)
        assertFalse(keepOpen.canFoldToSummary)
        assertFalse(presentTimeline(listOf(reasoning), options.copy(showSummary = false), false, "m", 0).canFoldToSummary)
        assertTrue(presentTimeline(listOf(reasoning), options.copy(autoCollapseSteps = false), false, "m", 0).canFoldToSummary)
    }

    @Test fun serverStatusAndDuplicateIdsRemainDistinct() {
        val steps = listOf(
            ThinkingStep.ServerToolStep(UIMessagePart.ServerTool("same", "search", status = ServerToolStatus.IN_PROGRESS)),
            ThinkingStep.ServerToolStep(UIMessagePart.ServerTool("same", "search", status = ServerToolStatus.FAILED)),
        )
        val result = presentTimeline(steps, options, loading = false, messageKey = "m", blockOrdinal = 1)
        assertEquals(TimelineStatus.Running, result.steps[0].status)
        assertEquals(TimelineStatus.Failed, result.steps[1].status)
        assertTrue(result.steps[0].key != result.steps[1].key)
        assertFalse(result.canFoldToSummary)
    }

    @Test fun nullErrorDoesNotFailAndObjectErrorDoes() {
        assertEquals(TimelineStatus.ResultReceived,
            presentToolStatus((tool("null", output = """{"error":null}""")).tool, false))
        assertEquals(TimelineStatus.Failed,
            presentToolStatus((tool("object", output = """{"error":{"code":"bad"}}""")).tool, false))
    }

    @Test fun answeredQuestionMayCollapseButUnansweredCannot() {
        val answered = tool("question", "ask_user", ToolApprovalState.Answered("yes"))
        assertTrue(presentTimeline(listOf(answered), options, false, "m", 0).canFoldToSummary)
        val unanswered = tool("question", "ask_user", ToolApprovalState.Pending)
        assertFalse(presentTimeline(listOf(unanswered), options, false, "m", 0).canFoldToSummary)
    }

    @Test fun keepThinkingOpenProtectsFinishedReasoningBeforeToolTail() {
        val time = Instant.parse("2026-01-02T03:04:05Z")
        val reasoning = ThinkingStep.ReasoningStep(UIMessagePart.Reasoning("Think", time, time))
        val steps = listOf(reasoning, tool("one", output = "{}"), tool("two", output = "{}"),
            tool("three", output = "{}"))
        val result = presentTimeline(steps, options.copy(autoCloseThinking = false), false, "m", 0)
        assertTrue(0 in result.collapsedVisibleIndices)
        assertFalse(result.canFoldToSummary)
    }

    @Test fun onlyFreshObservedCheckpointMayAppearRunning() {
        val marker = """{"error":"root_execution_indeterminate","reason":"checkpointed before launch"}"""
        val steps = listOf(tool("call", "root_exec", output = marker))
        val saved = presentTimeline(steps, options, loading = true, messageKey = "m", blockOrdinal = 0)
        assertEquals(TimelineStatus.Indeterminate, saved.steps.single().status)
        val fresh = presentTimeline(steps, options, loading = true, messageKey = "m", blockOrdinal = 0,
            activeToolCallId = "call")
        assertEquals(TimelineStatus.Running, fresh.steps.single().status)
        assertFalse(fresh.canFoldToSummary)
        val unrelated = presentTimeline(steps, options, loading = true, messageKey = "m", blockOrdinal = 0,
            activeToolCallId = "other")
        assertEquals(TimelineStatus.Indeterminate, unrelated.steps.single().status)
    }

    @Test fun nonzeroTermuxCaptureExitRemainsVisibleAndCannotFoldAsSuccessful() {
        val failed = tool("capture", "termux_run_command", output =
            """{"success":true,"mode":"capture","exit_code":1,"stdout":"","stderr":"command failed"}""")
        val result = presentTimeline(listOf(failed, tool("tail-one", output = "{}"),
            tool("tail-two", output = "{}"), tool("tail-three", output = "{}")),
            options, loading = false, messageKey = "message", blockOrdinal = 0)
        assertEquals(TimelineStatus.Failed, result.steps.first().status)
        assertTrue(0 in result.collapsedVisibleIndices)
        assertFalse(result.canFoldToSummary)
        assertEquals(TimelineStatus.Succeeded, presentToolStatus(tool("interactive", "termux_run_command",
            output = """{"success":true,"mode":"interactive"}""").tool, false))
        assertEquals(TimelineStatus.ResultReceived, presentToolStatus(tool("data", "read_json",
            output = """{"exit_code":1}""").tool, false))
    }

    @Test fun falseEmptyErrorsAndWarningsPreserveSuccessfulStatus() {
        listOf("false", "null", "\"\"", "0", "[]", "{}").forEach { error ->
            assertEquals("$error is not a failure", TimelineStatus.Succeeded,
                presentToolStatus(tool("success", "wake_screen", output =
                    """{"success":true,"error":$error,"warn":"Пользователь должен разблокировать экран."}""").tool, false))
        }
        assertEquals(TimelineStatus.Succeeded, presentToolStatus(tool("batch", "batch_copy",
            output = """{"success":true,"failed":[],"warnings":["Проверьте копию."]}""").tool, false))
    }

    @Test fun cronMcpAndPartialFailuresAreVisibleAndCannotFold() {
        listOf(
            "schedule_job" to """{"status":"error","code":"invalid_time"}""",
            "resume_job" to """{"status":"blocked","code":"job_unavailable"}""",
            "mcp_test" to """{"isError":true,"content":[{"type":"text","text":"Unauthorized"}]}""",
            "batch_move" to """{"success":1,"failed":[{"path":"a.txt","error":"Нет доступа."}]}""",
            "batch_copy" to """{"success":true,"results":[{"path":"a.txt","success":false,"error":"Нет доступа."}]}""",
        ).forEach { (name, output) ->
            val result = presentTimeline(listOf(tool("failure", name, output = output)), options, false, "m", 0)
            assertTrue("$name must remain visible", result.steps.single().mustStayVisible)
            assertFalse("$name cannot fold as successful", result.canFoldToSummary)
        }
    }

    @Test fun multipartMcpErrorEnvelopeAndPastHistoryKeepTheirOwnStatus() {
        val call = tool("mcp", "mcp__server__run").tool.copy(output = listOf(
            UIMessagePart.Text("""{"error":"mcp_tool_error","detail":"Сервер MCP сообщил об ошибке инструмента."}"""),
            UIMessagePart.Text("java.lang.IllegalStateException: native MCP failure"),
        ))
        assertEquals(TimelineStatus.Failed, presentToolStatus(call, false))
        assertEquals(TimelineStatus.Succeeded, presentToolStatus(tool("history", "get_job_history", output =
            """{"success":true,"runs":[{"status":"failed","code":"run_failed"}]}""").tool, false))
    }

    @Test fun russianCheckpointMarkersRemainRunningOnlyForTheObservedActiveCall() {
        listOf("root_exec" to "root_execution_indeterminate", "read_file" to "tool_execution_indeterminate").forEach { (name, code) ->
            val step = tool("active", name, output = """{"error":"$code","checkpointed_before_launch":true,"reason":"Вызов сохранён перед запуском. Не повторяйте его автоматически."}""")
            assertTrue(step.tool.hasExecutionCheckpoint())
            assertEquals(TimelineStatus.Running, presentTimeline(listOf(step), options, true, "m", 0,
                activeToolCallId = "active").steps.single().status)
        }
    }

    @Test fun workspaceTimeoutKeepsTheIndeterminateStatus() {
        assertEquals(TimelineStatus.Indeterminate, presentToolStatus(tool("timeout", "workspace_shell", output =
            """{"exitCode":-1,"stdout":"partial output","stderr":"","timedOut":true}""").tool, false))
    }

    private fun tool(
        id: String,
        name: String = "search_web",
        approval: ToolApprovalState = ToolApprovalState.Auto,
        output: String? = null,
    ) = ThinkingStep.ToolStep(UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
        output = output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
        approvalState = approval,
        metadata = JsonObject(emptyMap()),
    ))
}
