package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.workflow.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowRootSafetyTest {
    private fun definition(vararg actions: WorkflowAction) = WorkflowDefinition(
        id = "workflow", name = "Test", trigger = TriggerSpec.Manual, actions = actions.toList(),
    )

    @Test fun `dangerous ssh stdin wrapper is blocked before dispatch`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ -> calls++; HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED) }
        val result = runner.run(definition(WorkflowAction("ssh_exec", buildJsonObject {
            put("command", "sh -s"); put("stdin", "rm -rf /")
        })))
        assertFalse(result.success)
        assertEquals(0, calls)
        assertTrue(result.error.orEmpty().contains("hardline"))
    }

    @Test fun `root mandatory confirmation cannot be autoapproved`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ -> calls++; HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED) }
        val result = runner.run(definition(WorkflowAction("root_exec", buildJsonObject {
            put("command", "setenforce 0")
        })))
        assertFalse(result.success)
        assertEquals(0, calls)
    }

    @Test fun `revoked permission result stops remaining actions`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ -> calls++; HeadlessTaskResult(HeadlessTaskStatus.BLOCKED, errorCode = "feature_disabled") }
        val result = runner.run(definition(WorkflowAction("show_toast", buildJsonObject {}), WorkflowAction("show_toast", buildJsonObject {})))
        assertFalse(result.success)
        assertEquals(1, calls)
        assertTrue(result.error.orEmpty().contains("feature_disabled"))
    }

    @Test fun `failed Root action keeps its diagnostic output in the workflow summary`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ ->
            calls++
            HeadlessTaskResult(HeadlessTaskStatus.FAILED,
                output = "stdout=BEFORE\nstderr=permission denied\nexit_code=7", errorCode = "tool_failed")
        }

        val result = runner.run(definition(
            WorkflowAction("root_exec", buildJsonObject { put("command", "id") }),
            WorkflowAction("show_toast", buildJsonObject {}),
        ))

        assertFalse(result.success)
        assertEquals("action 0: tool_failed", result.error)
        assertEquals("[0] root_exec: stdout=BEFORE\nstderr=permission denied\nexit_code=7", result.summary)
        assertFalse(result.summaryTruncated)
        assertEquals(1, calls)
    }

    @Test fun `failure after completed actions still bounds the complete workflow summary`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ ->
            calls++
            if (calls == 13) HeadlessTaskResult(HeadlessTaskStatus.INDETERMINATE,
                output = "BEFORE", errorCode = "command_timeout")
            else HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = "🙂".repeat(100))
        }

        val result = runner.run(definition(*Array(14) {
            WorkflowAction("root_exec", buildJsonObject { put("command", "id") })
        }))

        assertFalse(result.success)
        assertEquals("action 12: command_timeout", result.error)
        assertEquals(13, calls)
        assertTrue("Early failure must respect the complete 2000-character summary cap", result.summary.length <= 2000)
        assertTrue("Truncated early-failure summaries must say so", result.summaryTruncated)
        assertFalse(result.summary.last().isHighSurrogate())
    }

    @Test fun `policy rejection after completed actions also bounds and marks the summary`() = runBlocking {
        var calls = 0
        val runner = WorkflowActionRunner { _, _, _ ->
            calls++
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = "x".repeat(200))
        }
        val completed = List(12) { WorkflowAction("root_exec", buildJsonObject { put("command", "id") }) }

        val result = runner.run(definition(*(completed + WorkflowAction("root_exec", buildJsonObject {
            put("command", "setenforce 0")
        })).toTypedArray()))

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("hardline"))
        assertEquals(12, calls)
        assertTrue(result.summary.length <= 2000)
        assertTrue(result.summaryTruncated)
    }

    @Test fun `unsupported special access triggers and inverted conditions remain unavailable`() {
        assertNotNull(WorkflowAvailability.triggerReason(TriggerSpec.AppLaunched("example.app")))
        assertNotNull(WorkflowAvailability.triggerReason(TriggerSpec.NotificationReceived(packageName = "example.app")))
        assertNotNull(WorkflowAvailability.triggerReason(TriggerSpec.GeofenceEnter(10.0, 10.0, 100)))
        assertNotNull(WorkflowAvailability.conditionReason(listOf(ConditionSpec.ForegroundAppIs("example.app", invert = true))))
        assertNull(WorkflowAvailability.triggerReason(TriggerSpec.Manual))
        assertNull(WorkflowAvailability.triggerReason(TriggerSpec.PowerConnected))
        assertNull(WorkflowAvailability.conditionReason(listOf(ConditionSpec.IsCharging())))
    }
}
