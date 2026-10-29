package me.rerere.rikkahub.workflow.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowFailureMessageTest {
    @Test fun `incomplete root output warns about effects and prohibits automatic replay`() {
        val message = workflowRunMessage("FAILED", "action 0: root_output_incomplete")
        assertTrue(message, message.contains("вывод", ignoreCase = true) && message.contains("неполн", ignoreCase = true))
        assertTrue(message, message.contains("автоматически не повтор", ignoreCase = true))
    }
    private val codes = listOf("root_cleanup_unconfirmed", "root_execution_indeterminate", "tool_result_indeterminate")
    private val definition = WorkflowDefinition(
        id = "workflow", name = "Workflow", trigger = TriggerSpec.Manual,
        actions = listOf(WorkflowAction("root_exec", buildJsonObject { put("command", "echo test") })),
        authoringAssistantId = "owner",
    )

    private fun repository(error: String): WorkflowRepository {
        val workflowDao = mockk<WorkflowDao>()
        val runDao = mockk<WorkflowRunDao>()
        val entity = WorkflowEntity(
            id = definition.id, name = definition.name, definitionJson = WorkflowJson.encode(definition),
            createdAtMs = 0, updatedAtMs = 0, lastRunAtMs = 5,
            lastRunStatus = "FAILED", lastRunError = error,
        )
        val run = WorkflowRunEntity(
            rowId = 1, workflowId = definition.id, firedAtMs = 5, status = "FAILED", durationMs = 20,
            errorMessage = error,
        )
        coEvery { workflowDao.getById(definition.id) } returns entity
        coEvery { workflowDao.listAll() } returns listOf(entity)
        coEvery { runDao.lastN(definition.id, any()) } returns listOf(run)
        return WorkflowRepository(workflowDao, runDao) { it }
    }

    private suspend fun execute(tool: Tool): JsonObject = Json.parseToJsonElement(
        (tool.execute(buildJsonObject { put("id", definition.id) }).single() as UIMessagePart.Text).text,
    ).jsonObject

    private fun assertUnknownOutcome(message: String) {
        assertTrue("Unknown outcome must be explicit: $message", message.contains("неизвестен", ignoreCase = true))
        assertTrue("Partial effects must be explicit: $message", message.contains("могла выполниться", ignoreCase = true))
        assertTrue("State check must be required: $message", message.contains("Проверьте состояние"))
        assertTrue("Automatic replay must be forbidden: $message", message.contains("автоматически не повтор", ignoreCase = true))
    }

    @Test fun `stored indeterminate action errors retain codes and warn about partial effects in get and list`() = runBlocking {
        for (code in codes) {
            val error = "action 0: $code"
            val repo = repository(error)
            val result = execute(workflowGetTool(repo))
            assertEquals(error, result["last_run_error"]!!.jsonPrimitive.content)
            assertEquals("FAILED", result["last_run_status"]!!.jsonPrimitive.content)
            assertUnknownOutcome(result["last_run_message"]!!.jsonPrimitive.content)
            val run = result["history"]!!.jsonArray.single().jsonObject
            assertEquals(error, run["error"]!!.jsonPrimitive.content)
            assertEquals("FAILED", run["status"]!!.jsonPrimitive.content)
            assertUnknownOutcome(run["message"]!!.jsonPrimitive.content)

            val item = execute(workflowListTool(repo))["items"]!!.jsonArray.single().jsonObject
            assertEquals("FAILED", item["last_run_status"]!!.jsonPrimitive.content)
            assertUnknownOutcome(item["last_run_message"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `manual indeterminate run keeps engine outcome and warns against automatic replay`() = runBlocking {
        for (code in codes) {
            val error = "action 0: $code"
            val engine = mockk<WorkflowEngine>()
            coEvery { engine.fire(definition.id) } returns WorkflowEngine.FireOutcome(WorkflowRunStatus.FAILED, error, "partial summary")

            val result = execute(workflowRunTool(engine, repository(error), "owner"))

            assertEquals("false", result["ok"]!!.jsonPrimitive.content)
            assertEquals("FAILED", result["status"]!!.jsonPrimitive.content)
            assertEquals(error, result["error"]!!.jsonPrimitive.content)
            assertEquals("partial summary", result["output_summary"]!!.jsonPrimitive.content)
            assertUnknownOutcome(result["message"]!!.jsonPrimitive.content)
        }
    }
}
