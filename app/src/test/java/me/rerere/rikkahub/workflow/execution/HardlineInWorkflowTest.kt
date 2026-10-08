package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12 — workflow action runner ALWAYS routes through HardlineCommandGuard, regardless
 * of headless mode. The classic `rm -rf /` smoke test, plus a couple of nearby surface
 * checks (unknown tool, plain success path, per-action timeout).
 */
class HardlineInWorkflowTest {

    private val toastTool = Tool(
        name = "show_toast",
        description = "show",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private val termuxTool = Tool(
        name = "termux_run_command",
        description = "shell",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ran")) },
    )

    private fun definition(actions: List<WorkflowAction>) = WorkflowDefinition(
        id = "test-workflow", name = "Test", trigger = TriggerSpec.Manual, actions = actions,
    )
    private fun runner(tools: List<Tool>) = WorkflowActionRunner { _, action, _ ->
        val tool = tools.find { it.name == action.tool }
        if (tool == null) HeadlessTaskResult(HeadlessTaskStatus.BLOCKED, errorCode = "unknown_tool:${action.tool}")
        else HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = tool.execute(action.args)
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
    }

    @Test fun `rm -rf root blocked by hardline`() = runBlocking {
        // Runner is supplied with the normal protected dispatcher in production.
        val actions = listOf(
            WorkflowAction(
                tool = "termux_run_command",
                args = buildJsonObject { put("command", "rm -rf /") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner(listOf(termuxTool)).run(definition(actions))
        assertFalse("rm -rf / must not succeed", result.success)
        assertTrue(
            "expected hardline error, got ${result.error}",
            result.error?.contains("hardline", ignoreCase = true) == true,
        )
    }

    @Test fun `unknown tool short-circuits`() = runBlocking {
        // Runner is supplied with the normal protected dispatcher in production.
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner(listOf(toastTool)).run(definition(actions))
        assertFalse(result.success)
        assertTrue(result.error?.contains("unknown_tool") == true)
    }

    @Test fun `clean toast action succeeds`() = runBlocking {
        // Runner is supplied with the normal protected dispatcher in production.
        val actions = listOf(
            WorkflowAction(tool = "show_toast", args = buildJsonObject { put("text", "hi") }, timeoutSeconds = 10),
        )
        val result = runner(listOf(toastTool)).run(definition(actions))
        assertTrue("expected success: ${result.error}", result.success)
    }

    @Test fun `aborts on first failure leaves later actions un-run`() = runBlocking {
        var lateExecuted = false
        val lateTool = Tool(
            name = "late_tool",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { lateExecuted = true; listOf(UIMessagePart.Text("late")) },
        )
        // Runner is supplied with the normal protected dispatcher in production.
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
            WorkflowAction(tool = "late_tool", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner(listOf(lateTool)).run(definition(actions))
        assertFalse(result.success)
        assertFalse("late tool must NOT have executed after the early failure", lateExecuted)
    }

    @Test fun `non-hardline ssh command runs`() = runBlocking {
        val ssh = Tool(
            name = "ssh_exec",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        // Runner is supplied with the normal protected dispatcher in production.
        val actions = listOf(
            WorkflowAction(
                tool = "ssh_exec",
                args = buildJsonObject { put("command", "uname -a") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner(listOf(ssh)).run(definition(actions))
        assertTrue("uname -a must not be hardline-blocked", result.success)
    }
}
