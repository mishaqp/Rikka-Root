package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AssistantRemovalReferenceRegressionTest {
    private lateinit var db: AppDatabase
    private lateinit var workflowDb: WorkflowDatabase
    private lateinit var live: MutableStateFlow<Settings>
    private lateinit var store: SettingsStore
    private lateinit var conversations: ConversationRepository
    private lateinit var memories: MemoryRepository
    private lateinit var jobs: ScheduledJobRepository
    private lateinit var workflows: WorkflowRepository
    private lateinit var guard: AssistantOwnershipGuard
    private val current = Assistant(name = "Текущий", localTools = listOf(LocalToolOption.ManageAssistants))
    private val target = Assistant(name = "Удаляемый")

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workflowDb = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        live = MutableStateFlow(Settings(assistants = listOf(current, target), assistantId = current.id))
        store = mockk {
            every { settingsFlow } returns live
            every { settingsFlowRaw } returns live
            coEvery { update(any()) } coAnswers { firstArg<(Settings) -> Settings>()(live.value).also { live.value = it } }
        }
        guard = AssistantOwnershipGuard { id -> live.value.assistants.any { it.id.toString() == id } }
        conversations = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            mockk(relaxed = true), mockk(relaxed = true), ownershipGuard = guard)
        memories = MemoryRepository(db.memoryDao(), guard)
        jobs = ScheduledJobRepository(db, object : CronPayloadCipher {
            override fun seal(jobId: String, plaintext: String) = plaintext
            override fun open(jobId: String, encrypted: String) = encrypted
        }, guard)
        workflows = WorkflowRepository(workflowDb.workflowDao(), workflowDb.workflowRunDao(), workflowDb, ownershipGuard = guard) { it }
        startKoin { modules(module {
            single { guard }; single { conversations }; single { memories }; single { jobs }; single { workflows }
        }) }
    }

    @After fun cleanup() {
        stopKoin()
        workflowDb.close()
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun `count excludes tombstones but counts disabled live rows beyond list limit`() = runBlocking {
        repeat(101) { db.scheduledJobDao().insert(job(deleted = false).copy(enabled = false)) }
        val deleted = job(deleted = true)
        db.scheduledJobDao().insert(deleted)
        assertEquals(101, db.scheduledJobDao().countOfAssistant(target.id.toString()))
        assertEquals(100, db.scheduledJobDao().listOwned(target.id.toString()).size)
        assertEquals(deleted, db.scheduledJobDao().getById(deleted.id))
    }

    @Test fun `manual assistant removal cleans deleted jobs and their history`() = runBlocking {
        val deleted = seedDeletedHistory()
        val other = job(deleted = true).copy(ownerAssistantId = current.id.toString())
        db.scheduledJobDao().insert(other)
        val otherRun = run(other)
        db.scheduledJobRunDao().insert(otherRun)
        val vm = AssistantVM(store, memories, conversations, mockk(relaxed = true), guard, jobs, workflows)
        try {
            val previous = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            vm.removeAssistant(target)
            vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in previous }.toList().joinAll()
            assertNull(vm.removalError.value)
            assertFalse(live.value.assistants.any { it.id == target.id })
            assertNull(db.scheduledJobDao().getById(deleted.id))
            assertTrue(db.scheduledJobRunDao().recent(deleted.id, 100).isEmpty())
            assertEquals(other, db.scheduledJobDao().getById(other.id))
            assertEquals(otherRun, db.scheduledJobRunDao().getById(otherRun.occurrenceId))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `actual management tool cleans tombstone history on successful deletion`() = runBlocking {
        val deleted = seedDeletedHistory()
        val tool = managementTool()
        val output = tool.execute(buildJsonObject { put("action", "delete"); put("assistant_id", target.id.toString()) })
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertFalse(result.toString(), result.containsKey("error"))
        assertFalse(live.value.assistants.any { it.id == target.id })
        assertNull(db.scheduledJobDao().getById(deleted.id))
        assertTrue(db.scheduledJobRunDao().recent(deleted.id, 100).isEmpty())
    }

    @Test fun `live disabled job still blocks management deletion and preserves deleted history too`() = runBlocking {
        val deleted = seedDeletedHistory()
        val active = job(deleted = false).copy(enabled = false)
        db.scheduledJobDao().insert(active)
        val before = live.value
        val output = managementTool().execute(buildJsonObject { put("action", "delete"); put("assistant_id", target.id.toString()) })
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ASSISTANT_HAS_DEPENDENCIES", result["error"]?.jsonPrimitive?.content)
        assertEquals(before, live.value)
        assertEquals(active, db.scheduledJobDao().getById(active.id))
        assertEquals(deleted, db.scheduledJobDao().getById(deleted.id))
        assertEquals(1, db.scheduledJobRunDao().recent(deleted.id, 100).size)
    }

    @Test fun `workflow UUID in prose or ordinary argument text does not count as ownership`() = runBlocking {
        val id = target.id.toString()
        val row = workflow("""{"name":"$id","description":"Mention $id","actions":[{"tool":"root_exec","args":{"command":"echo $id","assistant_id":"$id"}}]}""")
        workflowDb.workflowDao().upsert(row)
        assertEquals(0, workflowDb.workflowDao().countReferencingAssistant(id))
        assertEquals(row, workflowDb.workflowDao().getById(row.id))
    }

    @Test fun `workflow exact author and action assistant reference count once per row`() = runBlocking {
        val id = target.id.toString()
        val other = current.id.toString()
        val own = workflow("""{"authoring_assistant_id":"$id","actions":[{"tool":"manage_assistants","args":{"action":"get","assistant_id":"$id"}}]}""")
        val action = workflow("""{"authoring_assistant_id":"$other","actions":[{"tool":"manage_assistants","args":{"action":"get","assistant_id":"$id"}}]}""")
        val substring = workflow("""{"authoring_assistant_id":"prefix-$id-suffix"}""")
        listOf(own, action, substring).forEach { workflowDb.workflowDao().upsert(it) }
        assertEquals(2, workflowDb.workflowDao().countReferencingAssistant(id))
        listOf(own, action, substring).forEach { assertEquals(it, workflowDb.workflowDao().getById(it.id)) }
    }

    @Test fun `malformed workflow with an actual owner reference remains protected`() = runBlocking {
        val id = target.id.toString()
        val row = workflow("""{"authoring_assistant_id":"$id", BROKEN""")
        workflowDb.workflowDao().upsert(row)
        assertEquals(1, workflowDb.workflowDao().countReferencingAssistant(id))
        val before = live.value
        val output = managementTool().execute(buildJsonObject { put("action", "delete"); put("assistant_id", id) })
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ASSISTANT_HAS_DEPENDENCIES", result["error"]?.jsonPrimitive?.content)
        assertEquals(before, live.value)
        assertEquals(row, workflowDb.workflowDao().getById(row.id))
    }

    @Test fun `actual management description explains deletion dependencies in Russian`() {
        val description = managementTool().description
        assertTrue(description, description.contains("Нельзя удалять"))
        listOf("чатами", "памятью", "неудалёнными заданиями", "ссылками из сценариев").forEach {
            assertTrue("Missing deletion dependency '$it': $description", description.contains(it))
        }
        assertTrue(description, description.contains("Сначала перенесите или удалите связанные данные"))
        assertTrue(description, description.contains("история уже удалённых заданий очищается"))
    }

    @Test fun `actual management refuses each live dependency and preserves linked data`() = runBlocking {
        for (kind in listOf("chat", "memory", "schedule", "workflow")) {
            val owner = Assistant(name = "Связь $kind")
            live.value = live.value.copy(assistants = live.value.assistants + owner)
            val chat = Conversation(assistantId = owner.id, title = "Сохранённый чат", messageNodes = emptyList(),
                createAt = java.time.Instant.EPOCH, updateAt = java.time.Instant.EPOCH)
            val scheduled = job(deleted = false).copy(ownerAssistantId = owner.id.toString())
            val scenario = workflow("""{"authoring_assistant_id":"${owner.id}"}""")
            when (kind) {
                "chat" -> conversations.insertConversation(chat)
                "memory" -> memories.addMemory(owner.id.toString(), "Сохранённая память")
                "schedule" -> db.scheduledJobDao().insert(scheduled)
                "workflow" -> workflowDb.workflowDao().upsert(scenario)
            }
            val beforeSettings = live.value
            val beforeMemory = memories.getMemoriesOfAssistant(owner.id.toString())
            val output = managementTool().execute(buildJsonObject {
                put("action", "delete")
                put("assistant_id", owner.id.toString())
            })
            val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(kind, "ASSISTANT_HAS_DEPENDENCIES", result["error"]?.jsonPrimitive?.content)
            assertEquals(kind, beforeSettings, live.value)
            when (kind) {
                "chat" -> assertEquals(chat, conversations.getConversationById(chat.id))
                "memory" -> {
                    assertEquals(1, beforeMemory.size)
                    assertEquals(beforeMemory, memories.getMemoriesOfAssistant(owner.id.toString()))
                }
                "schedule" -> assertEquals(scheduled, db.scheduledJobDao().getById(scheduled.id))
                "workflow" -> assertEquals(scenario, workflowDb.workflowDao().getById(scenario.id))
            }
        }
    }

    private fun managementTool() = LocalTools(RuntimeEnvironment.getApplication(), AppEventBus(), mockk(), store,
        mockk(), mockk(), mockk(), mockk(), mockk()).getTools(current.localTools, current.id.toString(),
        managerCaller = AssistantManagerCaller({ current.id }, {}), managerCatalog = { AssistantManagerCatalog() })
        .single { it.name == "manage_assistants" }
    private suspend fun seedDeletedHistory(): ScheduledJobEntity = job(deleted = true).also {
        db.scheduledJobDao().insert(it)
        db.scheduledJobRunDao().insert(run(it))
    }
    private fun job(deleted: Boolean) = ScheduledJobEntity(Uuid.random().toString(), target.id.toString(), "chat", "",
        mode = "prompt", scheduleType = "at", timezone = "UTC", createdAtMs = 1, deleted = deleted, enabled = !deleted)
    private fun run(job: ScheduledJobEntity) = ScheduledJobRunEntity(Uuid.random().toString(), job.id, job.revision,
        "scheduled", 1, status = "succeeded", finishedAtMs = 2)
    private fun workflow(raw: String) = WorkflowEntity(Uuid.random().toString(), "Сценарий", enabled = false,
        definitionJson = raw, createdAtMs = 1, updatedAtMs = 1)
}
