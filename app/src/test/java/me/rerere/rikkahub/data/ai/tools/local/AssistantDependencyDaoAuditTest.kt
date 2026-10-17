package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AssistantDependencyDaoAuditTest {
    private lateinit var database: AppDatabase
    private lateinit var workflows: WorkflowDatabase

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workflows = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        workflows.close()
        database.close()
    }

    private fun conversation(owner: String, id: String = Uuid.random().toString()) = ConversationEntity(
        id = id, assistantId = owner, title = "Историю сохранить", nodes = "broken legacy JSON",
        createAt = 1, updateAt = 2, chatSuggestions = "[]", isPinned = false,
    )

    private fun job(owner: String, deleted: Boolean = false) = ScheduledJobEntity(
        id = Uuid.random().toString(), ownerAssistantId = owner, creatorConversationId = "chat",
        payloadCiphertext = "unchanged encrypted payload", mode = "prompt", scheduleType = "at",
        timezone = "UTC", createdAtMs = 1, enabled = !deleted, deleted = deleted,
    )

    private fun workflow(owner: String, malformed: Boolean = false, proseOnly: Boolean = false) = WorkflowEntity(
        id = Uuid.random().toString(), name = "Сценарий сохранить", enabled = false,
        definitionJson = when {
            malformed -> "{\"authoring_assistant_id\":\"$owner\", BROKEN"
            proseOnly -> "{\"description\":\"Reference $owner\"}"
            else -> "{\"authoring_assistant_id\":\"$owner\"}"
        }, createdAtMs = 1, updatedAtMs = 2,
    )

    private suspend fun counts(id: Uuid): AssistantManagerDependencies {
        val owner = id.toString()
        return AssistantManagerDependencies(
            conversations = database.conversationDao().countOfAssistant(owner),
            memories = database.memoryDao().countOfAssistant(owner),
            cronJobs = database.scheduledJobDao().countOfAssistant(owner),
            workflows = workflows.workflowDao().countReferencingAssistant(owner),
        )
    }

    @Test fun `chat and memory counts isolate owners without decoding damaged chat content`() = runBlocking {
        val owner = Uuid.random().toString()
        val foreign = Uuid.random().toString()
        val chat = conversation(owner)
        database.conversationDao().insert(chat)
        database.conversationDao().insert(conversation(foreign))
        val memory = database.memoryDao().insertMemory(MemoryEntity(assistantId = owner, content = "Память сохранить")).toInt()
        database.memoryDao().insertMemory(MemoryEntity(assistantId = foreign, content = "Чужая память"))
        assertEquals(1, database.conversationDao().countOfAssistant(owner))
        assertEquals(1, database.memoryDao().countOfAssistant(owner))
        assertEquals(0, database.conversationDao().countOfAssistant(Uuid.random().toString()))
        assertEquals(0, database.memoryDao().countOfAssistant(Uuid.random().toString()))
        assertEquals(chat, database.conversationDao().getConversationById(chat.id))
        assertEquals("Память сохранить", database.memoryDao().getMemoryById(memory)?.content)
    }

    @Test fun `cron counts include tombstones and exceed the visible list limit`() = runBlocking {
        val owner = Uuid.random().toString()
        repeat(101) { database.scheduledJobDao().insert(job(owner)) }
        val tombstone = job(owner, deleted = true)
        database.scheduledJobDao().insert(tombstone)
        database.scheduledJobDao().insert(job(Uuid.random().toString()))
        assertEquals(102, database.scheduledJobDao().countOfAssistant(owner))
        assertEquals(100, database.scheduledJobDao().listOwned(owner).size)
        assertEquals(tombstone, database.scheduledJobDao().getById(tombstone.id))
    }

    @Test fun `workflow reference count retains malformed definitions and conservative prose references`() = runBlocking {
        val owner = Uuid.random().toString()
        val malformed = workflow(owner, malformed = true)
        val prose = workflow(owner, proseOnly = true)
        workflows.workflowDao().upsert(workflow(owner))
        workflows.workflowDao().upsert(malformed)
        workflows.workflowDao().upsert(prose)
        workflows.workflowDao().upsert(workflow(Uuid.random().toString()))
        // Prose can cause a false positive: preserve the assistant and let the user resolve it.
        assertEquals(3, workflows.workflowDao().countReferencingAssistant(owner))
        assertEquals(0, workflows.workflowDao().countReferencingAssistant(Uuid.random().toString()))
        assertEquals(malformed, workflows.workflowDao().getById(malformed.id))
        assertEquals(prose, workflows.workflowDao().getById(prose.id))
    }

    @Test fun `real persisted dependencies prevent assistant removal and every row survives`() = runBlocking {
        for (kind in listOf("chat", "memory", "cron", "workflow")) {
            val owner = Assistant(name = "Текущий")
            val target = Assistant(name = "Связанный с $kind")
            val id = target.id.toString()
            val chat = conversation(id)
            val memory = MemoryEntity(assistantId = id, content = "Не удалять")
            val cron = job(id, deleted = true)
            val definition = workflow(id, malformed = true)
            var memoryId = 0
            when (kind) {
                "chat" -> database.conversationDao().insert(chat)
                "memory" -> memoryId = database.memoryDao().insertMemory(memory).toInt()
                "cron" -> database.scheduledJobDao().insert(cron)
                "workflow" -> workflows.workflowDao().upsert(definition)
            }
            val before = Settings(assistants = listOf(owner, target), assistantId = owner.id)
            var settings = before
            var writes = 0
            val engine = AssistantManagerEngine({ settings }, { transform ->
                transform(settings).also { settings = it; writes++ }
            }, { AssistantManagerCatalog() }, AssistantManagerCaller({ owner.id }, {}), dependencies = ::counts)
            val result = engine.execute(buildJsonObject { put("action", "delete"); put("assistant_id", id) })
            assertEquals("$kind: $result", "ASSISTANT_HAS_DEPENDENCIES", result["error"]?.jsonPrimitive?.content)
            assertEquals(before, settings)
            assertEquals(0, writes)
            when (kind) {
                "chat" -> assertEquals(chat, database.conversationDao().getConversationById(chat.id))
                "memory" -> assertEquals(memory.copy(id = memoryId), database.memoryDao().getMemoryById(memoryId))
                "cron" -> assertEquals(cron, database.scheduledJobDao().getById(cron.id))
                "workflow" -> assertEquals(definition, workflows.workflowDao().getById(definition.id))
            }
        }
    }
}
