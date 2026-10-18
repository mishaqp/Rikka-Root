package me.rerere.rikkahub.workflow.tools

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.local.createCronJobTools
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.MemoryNotFoundException
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.model.*
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ManagementOwnedErrorsPart2Test {
    private val owner = Uuid.random()
    private val definition = WorkflowDefinition("wf", "Сценарий", trigger = TriggerSpec.Manual,
        actions = listOf(WorkflowAction("get_time_info", buildJsonObject {})), authoringAssistantId = owner.toString())
    private fun loaded(def: WorkflowDefinition = definition) = WorkflowRepository.Loaded(
        WorkflowEntity(def.id, def.name, definitionJson = WorkflowJson.encode(def), createdAtMs = 1, updatedAtMs = 2), def)
    private fun arguments(def: WorkflowDefinition) = buildJsonObject {
        put("definition", JsonObject(Json.parseToJsonElement(WorkflowJson.encode(def)).jsonObject.filterKeys {
            it in setOf("id", "name", "description", "enabled", "trigger", "conditions", "actions", "cooldown_seconds", "max_runs_per_day")
        }))
    }
    private suspend fun invoke(tool: Tool, args: JsonObject): JsonObject =
        Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
    private fun idArguments() = buildJsonObject { put("id", "wf") }
    private fun assertRussian(text: String) {
        assertTrue("Expected app-authored Russian explanation: $text", Regex("[А-Яа-яЁё]").containsMatchIn(text))
    }
    private fun assertWorkflowFailure(result: JsonObject, code: String, oldEnglish: String) {
        assertEquals(JsonPrimitive(false), result["ok"])
        assertEquals(JsonPrimitive(code), result["error"])
        assertRussian(result.getValue("message").jsonPrimitive.content)
        val detail = result.getValue("detail").jsonPrimitive.content
        assertRussian(detail)
        assertFalse(detail, detail.contains(oldEnglish, ignoreCase = true))
    }

    @Test fun getMissingWorkflowExplainsRuntimeNotFoundInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository> { coEvery { getById("wf") } returns null }
        assertWorkflowFailure(invoke(workflowGetTool(repo), idArguments()), "not_found", "no workflow with")
    }

    @Test fun enableMissingWorkflowExplainsRuntimeNotFoundInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository> { coEvery { getById("wf") } returns null }
        val args = buildJsonObject { put("id", "wf"); put("enabled", true) }
        assertWorkflowFailure(invoke(workflowSetEnabledTool(repo, owner.toString()), args), "not_found", "no workflow with")
        coVerify(exactly = 0) { repo.setEnabled(any(), any()) }
    }

    @Test fun runMissingWorkflowExplainsRuntimeNotFoundInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository> { coEvery { getById("wf") } returns null }
        assertWorkflowFailure(invoke(workflowRunTool(mockk(), repo, owner.toString()), idArguments()), "not_found", "no workflow with")
    }

    @Test fun updateMissingWorkflowExplainsRuntimeNotFoundInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository> { coEvery { getById("wf") } returns null }
        assertWorkflowFailure(invoke(workflowUpdateTool(repo, { listOf("get_time_info") }, owner.toString()), arguments(definition)),
            "not_found", "no workflow with")
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun deleteRacedWorkflowExplainsRuntimeNotFoundInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository> {
            coEvery { getById("wf") } returns loaded()
            coEvery { deleteCascading("wf") } returns false
        }
        assertWorkflowFailure(invoke(workflowDeleteTool(repo, owner.toString()), idArguments()), "not_found", "no workflow with")
        coVerify(exactly = 1) { repo.deleteCascading("wf") }
    }

    @Test fun unknownActionExplainsActualRegistryFailureInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository>()
        val def = definition.copy(actions = listOf(WorkflowAction("unavailable_tool", buildJsonObject {})))
        assertWorkflowFailure(invoke(workflowCreateTool(repo, { listOf("get_time_info") }, owner.toString()), arguments(def)),
            "unknown_tool", "not registered for this assistant")
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun workflowChainingExplainsSemanticRefusalInRussian() = runBlocking {
        val repo = mockk<WorkflowRepository>()
        val def = definition.copy(actions = listOf(WorkflowAction("workflow_run", buildJsonObject { put("id", "other") })))
        assertWorkflowFailure(invoke(workflowCreateTool(repo, { listOf("workflow_run") }, owner.toString()), arguments(def)),
            "workflow_chaining_disabled", "cannot be used as a workflow action")
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun cronMissingOwnedJobAlreadyUsesRussianMessage() = runBlocking {
        val repo = mockk<ScheduledJobRepository> { coEvery { getOwned("wf", owner.toString()) } returns null }
        val tool = createCronJobTools(repo, mockk(), mockk(), Conversation(assistantId = owner, messageNodes = emptyList()), { emptySet() })
            .single { it.name == "get_job_history" }
        val result = invoke(tool, idArguments())
        assertEquals(JsonPrimitive("not_found"), result["code"])
        assertRussian(result.getValue("message").jsonPrimitive.content)
    }

    @Test fun missingOwnedMemoryAlreadyUsesRussianMessage() = runBlocking {
        val tool = buildMemoryTools(Json, { error("Unexpected creation") }, { _, _ -> throw MemoryNotFoundException() }, {}).single()
        val result = invoke(tool, buildJsonObject { put("action", "edit"); put("id", 7); put("content", "Заметка") })
        assertEquals(JsonPrimitive("not_found"), result["code"])
        assertRussian(result.getValue("error").jsonPrimitive.content)
    }

    @Test fun workflowGetPreservesEnglishUserData() = runBlocking {
        val text = "User text: no workflow with this title"
        val def = definition.copy(name = "My English workflow", description = text,
            actions = listOf(WorkflowAction("get_time_info", buildJsonObject { put("note", text) })))
        val repo = mockk<WorkflowRepository> {
            coEvery { getById("wf") } returns loaded(def)
            coEvery { lastRuns("wf", any()) } returns emptyList()
        }
        val result = invoke(workflowGetTool(repo), idArguments()).getValue("definition").jsonObject
        assertEquals(JsonPrimitive(def.name), result["name"])
        assertEquals(JsonPrimitive(text), result["description"])
        assertEquals(JsonPrimitive(text), result.getValue("actions").jsonArray.single().jsonObject.getValue("args").jsonObject["note"])
    }
}
