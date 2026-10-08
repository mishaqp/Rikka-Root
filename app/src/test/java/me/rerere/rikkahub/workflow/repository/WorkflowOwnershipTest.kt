package me.rerere.rikkahub.workflow.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.workflow.db.*
import me.rerere.rikkahub.workflow.model.*
import me.rerere.rikkahub.workflow.tools.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class WorkflowOwnershipTest {
    private val dao = MemoryWorkflowDao()
    private val repo = WorkflowRepository(dao, MemoryRunDao()) { it }
    private val definition = WorkflowDefinition("id", "Workflow", trigger = TriggerSpec.Manual,
        actions = listOf(WorkflowAction("show_toast", buildJsonObject { put("text", "hello") })),
        authoringAssistantId = "other-owner")
    private fun arguments(def: WorkflowDefinition = definition) = buildJsonObject {
        put("definition", Json.parseToJsonElement(WorkflowJson.encode(def)))
    }
    private fun text(output: List<UIMessagePart>) = output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }

    @Test fun `create uses trusted caller rather than model supplied owner`() = runBlocking {
        val tool = workflowCreateTool(repo, { listOf("show_toast") }, "trusted-owner")
        assertTrue(text(tool.execute(arguments())).contains("\"ok\":true"))
        assertEquals("trusted-owner", repo.getById("id")!!.definition.authoringAssistantId)
    }
    @Test fun `create cannot replace an existing workflow owned by another assistant`() = runBlocking {
        repo.upsert(definition)
        val output = workflowCreateTool(repo, { listOf("show_toast") }, "attacker").execute(arguments(definition.copy(name = "replacement")))
        assertTrue(text(output).contains("already_exists"))
        assertEquals("Workflow", repo.getById("id")!!.definition.name)
    }
    @Test fun `update cannot adopt another assistant workflow`() = runBlocking {
        repo.upsert(definition)
        val output = workflowUpdateTool(repo, { listOf("show_toast") }, "attacker").execute(arguments(definition.copy(authoringAssistantId = "attacker")))
        assertTrue(text(output).contains("owner_mismatch"))
        assertEquals("other-owner", repo.getById("id")!!.definition.authoringAssistantId)
    }
    @Test fun `missing caller cannot create an ownerless workflow`() = runBlocking {
        val output = workflowCreateTool(repo, { listOf("show_toast") }, null).execute(arguments())
        assertTrue(text(output).contains("missing_owner"))
        assertNull(repo.getById("id"))
    }
    @Test fun `unsupported special access workflow can be saved disabled but not enabled`() = runBlocking {
        val disabled = definition.copy(enabled = false, trigger = TriggerSpec.AppLaunched("example.app"))
        repo.upsert(disabled)
        val output = workflowSetEnabledTool(repo, "other-owner").execute(buildJsonObject { put("id", "id"); put("enabled", true) })
        assertTrue(text(output).contains("trigger_unavailable"))
        assertFalse(repo.getById("id")!!.entity.enabled)
    }
    @Test fun `definition args are sanitized before Room write`() = runBlocking {
        val sanitizingRepo = WorkflowRepository(dao, MemoryRunDao()) { def ->
            sanitizeWorkflowDefinition(def) { _, args -> args.replace("raw-password", "rikka-ssh-secret:reference:password") }
        }
        sanitizingRepo.upsert(definition.copy(actions = listOf(WorkflowAction("ssh_exec", buildJsonObject { put("password", "raw-password") }))))
        assertFalse(dao.getById("id")!!.definitionJson.contains("raw-password"))
        assertTrue(dao.getById("id")!!.definitionJson.contains("rikka-ssh-secret:"))
    }
    @Test fun `nested action credentials are sanitized in history and partial input fails closed`() {
        val input = arguments(definition.copy(actions = listOf(WorkflowAction("ssh_exec", buildJsonObject { put("password", "raw-password") })))).toString()
        val safe = sanitizeWorkflowToolArguments("workflow_create", input) { name,args ->
            assertEquals("ssh_exec", name); args.replace("raw-password", "vault-reference")
        }
        assertFalse(safe.contains("raw-password"))
        assertTrue(safe.contains("vault-reference"))
        assertFalse(sanitizeWorkflowToolArguments("workflow_up", "{\"definition\":{\"actions\":[{\"password\":\"raw-password") { _,args -> args }.contains("raw-password"))
    }
    @Test fun `private definitions and run history require approval`() = runBlocking {
        assertTrue(workflowListTool(repo).needsApproval(buildJsonObject {}))
        assertTrue(workflowGetTool(repo).needsApproval(buildJsonObject {}))
    }
}

internal class MemoryWorkflowDao : WorkflowDao {
    val rows = MutableStateFlow<List<WorkflowEntity>>(emptyList())
    override fun observeAll(): Flow<List<WorkflowEntity>> = rows
    override suspend fun listAll() = rows.value
    override suspend fun listEnabled() = rows.value.filter { it.enabled }
    override suspend fun getById(id: String) = rows.value.find { it.id == id }
    override fun observeById(id: String) = rows.map { list -> list.find { it.id == id } }
    override suspend fun upsert(entity: WorkflowEntity) { rows.value = rows.value.filterNot { it.id == entity.id } + entity }
    override suspend fun update(entity: WorkflowEntity) = upsert(entity)
    override suspend fun deleteById(id: String): Int { val exists = getById(id) != null; rows.value = rows.value.filterNot { it.id == id }; return if (exists) 1 else 0 }
    override suspend fun setEnabled(id: String, enabled: Boolean, updatedAtMs: Long): Int {
        val row = getById(id) ?: return 0; upsert(row.copy(enabled = enabled, updatedAtMs = updatedAtMs)); return 1
    }
    override suspend fun recordFire(id: String, firedAtMs: Long, status: String, errorMessage: String?, runsTodayCount: Int, runsTodayDate: String) = 1
}
internal class MemoryRunDao : WorkflowRunDao {
    override suspend fun insert(entity: WorkflowRunEntity) = 1L
    override suspend fun lastN(workflowId: String, limit: Int) = emptyList<WorkflowRunEntity>()
    override suspend fun trim(workflowId: String, keep: Int) = Unit
    override suspend fun deleteAllFor(workflowId: String) = 0
    override suspend fun countCountedFiresSince(workflowId: String, sinceMs: Long) = 0
    override suspend fun lastActualFireAtMs(workflowId: String): Long? = null
}
