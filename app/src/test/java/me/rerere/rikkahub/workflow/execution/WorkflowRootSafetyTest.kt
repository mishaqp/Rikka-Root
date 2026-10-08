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
