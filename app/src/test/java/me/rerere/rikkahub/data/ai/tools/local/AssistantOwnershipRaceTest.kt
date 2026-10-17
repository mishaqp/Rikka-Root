package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ScheduledJobPayload
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.di.repositoryModule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AssistantOwnershipRaceTest {
    private lateinit var database: AppDatabase
    private lateinit var workflowDatabase: WorkflowDatabase

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workflowDatabase = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        workflowDatabase.close()
        database.close()
    }

    private inner class Fixture {
        val currentOwner = Assistant(name = "Текущий")
        val target = Assistant(name = "Удаляемый")
        var settings = Settings(assistants = listOf(currentOwner, target), assistantId = currentOwner.id)
        var onOwnerRead: suspend (String) -> Unit = {}
        var onCounted: suspend () -> Unit = {}
        var ownerReads = 0
        var dependencyReads = 0
        val guard = AssistantOwnershipGuard { id ->
            ownerReads++
            onOwnerRead(id)
            settings.assistants.any { it.id.toString() == id }
        }
        val conversations = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, mockk(relaxed = true), mockk(relaxed = true), ownershipGuard = guard)
        val memories = MemoryRepository(database.memoryDao(), ownershipGuard = guard)
        val jobs = ScheduledJobRepository(database, object : CronPayloadCipher {
            override fun seal(jobId: String, plaintext: String) = "encrypted test fixture"
            override fun open(jobId: String, encrypted: String) = encrypted
        }, ownershipGuard = guard)
        val workflows = WorkflowRepository(workflowDatabase.workflowDao(), workflowDatabase.workflowRunDao(), workflowDatabase,
            ownershipGuard = guard) { it }
        val conversation = Conversation(assistantId = target.id, messageNodes = emptyList(), title = "Сохранить")
        val job = ScheduledJobEntity(Uuid.random().toString(), target.id.toString(), "chat", "", mode = "prompt",
            scheduleType = "at", timezone = "UTC", createdAtMs = 1)
        val workflow = WorkflowDefinition(Uuid.random().toString(), "Сохранить", enabled = false,
            trigger = TriggerSpec.Manual, actions = emptyList(), authoringAssistantId = target.id.toString())
        val manager = AssistantManagerEngine({ settings }, { transform ->
            transform(settings).also { settings = it }
        }, { AssistantManagerCatalog() }, AssistantManagerCaller({ currentOwner.id }, {}), dependencies = { id ->
            dependencyReads++
            // Capture the SQL result before the barrier: this is the original COUNT→delete race.
            val counts = counts(id)
            onCounted()
            counts
        }, ownershipGuard = guard)

        suspend fun counts(id: Uuid = target.id): AssistantManagerDependencies {
            val owner = id.toString()
            return AssistantManagerDependencies(
                database.conversationDao().countOfAssistant(owner), database.memoryDao().countOfAssistant(owner),
                database.scheduledJobDao().countOfAssistant(owner), workflowDatabase.workflowDao().countReferencingAssistant(owner))
        }

        suspend fun write(kind: String) {
            when (kind) {
                "chat" -> conversations.insertConversation(conversation)
                "memory" -> memories.addMemory(target.id.toString(), "Не потерять")
                "cron" -> jobs.create(job, ScheduledJobPayload(name = "Не потерять", prompt = "Проверить"))
                "workflow" -> workflows.upsert(workflow)
                else -> error("Unknown test writer")
            }
        }

        suspend fun delete() = manager.execute(buildJsonObject { put("action", "delete"); put("assistant_id", target.id.toString()) })
        fun removeTarget() { settings = settings.copy(assistants = listOf(currentOwner)) }
        fun hasTarget() = settings.assistants.any { it.id == target.id }
    }

    @Test(timeout = 30_000) fun `delete first excludes every binding writer until owner removal commits`() = runBlocking {
        for (kind in writerKinds) {
            val f = Fixture()
            val counted = CompletableDeferred<Unit>()
            val finishDelete = CompletableDeferred<Unit>()
            f.onCounted = { counted.complete(Unit); finishDelete.await() }
            val deleting = async { f.delete() }
            withTimeout(5_000) { counted.await() }
            val writing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.write(kind) } }
            try {
                finishDelete.complete(Unit)
                val deleted = withTimeout(5_000) { deleting.await() }
                val written = withTimeout(5_000) { writing.await() }
                assertFalse("$kind delete: $deleted", deleted.containsKey("error"))
                assertFalse(f.hasTarget())
                assertTrue("$kind must reject writing to the removed owner", written.isFailure)
                assertEquals("No orphan row may be inserted by $kind", AssistantManagerDependencies(), f.counts())
            } finally {
                finishDelete.complete(Unit)
                writing.cancel()
                deleting.cancel()
            }
        }
    }

    @Test(timeout = 30_000) fun `writer first excludes delete until each dependency is durably visible`() = runBlocking {
        for (kind in writerKinds) {
            val f = Fixture()
            val readingOwner = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            f.onOwnerRead = { readingOwner.complete(Unit); finishWrite.await() }
            val writing = async { f.write(kind) }
            withTimeout(5_000) { readingOwner.await() }
            val deleting = async(start = CoroutineStart.UNDISPATCHED) { f.delete() }
            try {
                // UNDIDSPATCHED reaches either Mutex.lock or the callback synchronously. No sleeps.
                assertEquals("$kind: delete must not count ahead of the in-flight owner write", 0, f.dependencyReads)
                finishWrite.complete(Unit)
                withTimeout(5_000) { writing.await() }
                val deleted = withTimeout(5_000) { deleting.await() }
                assertEquals("$kind: $deleted", "ASSISTANT_HAS_DEPENDENCIES", deleted["error"]?.jsonPrimitive?.content)
                assertTrue("The owner of the committed $kind must remain", f.hasTarget())
                assertNotEquals(AssistantManagerDependencies(), f.counts())
            } finally {
                finishWrite.complete(Unit)
                writing.cancel()
                deleting.cancel()
            }
        }
    }

    @Test(timeout = 30_000) fun `all four repositories reject an absent owner before inserting anything`() = runBlocking {
        for (kind in writerKinds) {
            val f = Fixture()
            f.removeTarget()
            val result = runCatching { f.write(kind) }
            assertTrue("$kind accepted a stale owner", result.isFailure)
            assertEquals(AssistantManagerDependencies(), f.counts())
        }
    }

    @Test(timeout = 30_000) fun `stale conversation updates checkpoints and owner reassignment cannot bind a removed assistant`() = runBlocking {
        for (operation in listOf("update", "checkpoint", "assign")) {
            val f = Fixture()
            val existing = f.conversation.copy(assistantId = f.currentOwner.id)
            f.conversations.insertConversation(existing)
            f.removeTarget()
            val result = runCatching {
                when (operation) {
                    "update" -> f.conversations.updateConversation(f.conversation)
                    "checkpoint" -> f.conversations.checkpointGeneratedNodes(f.conversation, emptyList())
                    "assign" -> f.conversations.updateConversationAssistant(existing.id, f.target.id)
                }
            }
            assertTrue("$operation accepted a removed owner", result.isFailure)
            assertEquals(f.currentOwner.id.toString(), database.conversationDao().getConversationById(existing.id.toString())?.assistantId)
            assertEquals(0, database.conversationDao().countOfAssistant(f.target.id.toString()))
        }
    }

    @Test(timeout = 30_000) fun `memory copy and workflow replacement reject a removed target without changing existing rows`() = runBlocking {
        val f = Fixture()
        val sourceMemory = database.memoryDao().insertMemory(MemoryEntity(assistantId = f.currentOwner.id.toString(), content = "Сохранить источник")).toInt()
        f.workflows.upsert(f.workflow.copy(authoringAssistantId = f.currentOwner.id.toString()))
        val original = workflowDatabase.workflowDao().getById(f.workflow.id)
        f.removeTarget()
        assertTrue(runCatching { f.memories.copyMemories(f.currentOwner.id.toString(), f.target.id.toString()) }.isFailure)
        assertTrue(runCatching { f.workflows.upsert(f.workflow) }.isFailure)
        assertEquals("Сохранить источник", database.memoryDao().getMemoryById(sourceMemory)?.content)
        assertEquals(0, database.memoryDao().countOfAssistant(f.target.id.toString()))
        assertEquals(original, workflowDatabase.workflowDao().getById(f.workflow.id))
    }

    @Test(timeout = 30_000) fun `global memory and ownerless legacy workflow keep their existing semantics`() = runBlocking {
        val f = Fixture()
        f.onOwnerRead = { error("Global and legacy writes must not require an assistant identity") }
        val memory = f.memories.addMemory(MemoryRepository.GLOBAL_MEMORY_ID, "Общая память")
        val legacy = f.workflow.copy(authoringAssistantId = null)
        f.workflows.upsert(legacy)
        assertEquals("Общая память", database.memoryDao().getMemoryById(memory.id)?.content)
        assertEquals(legacy.id, workflowDatabase.workflowDao().getById(legacy.id)?.id)
        assertEquals(0, f.ownerReads)
    }

    @Test(timeout = 30_000) fun `global memory sentinel cannot bypass cron or workflow ownership checks`() = runBlocking {
        val f = Fixture()
        val sentinel = MemoryRepository.GLOBAL_MEMORY_ID
        val job = f.job.copy(ownerAssistantId = sentinel)
        val workflow = f.workflow.copy(authoringAssistantId = sentinel)
        val cronResult = runCatching { f.jobs.create(job, ScheduledJobPayload(name = "Проверка", prompt = "Проверить")) }
        val workflowResult = runCatching { f.workflows.upsert(workflow) }
        assertTrue("Cron requires a real assistant owner", cronResult.isFailure)
        assertTrue("Workflow requires a real owner or null legacy identity", workflowResult.isFailure)
        assertEquals(0, database.scheduledJobDao().countOfAssistant(sentinel))
        assertEquals(0, workflowDatabase.workflowDao().countReferencingAssistant(sentinel))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test(timeout = 30_000) fun `manual removal waits for an existing owner write before deleting its data`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        val live = MutableStateFlow(f.settings)
        val store = mockk<SettingsStore>()
        var settingsWrites = 0
        every { store.settingsFlow } returns live
        coEvery { store.update(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(live.value).also {
                settingsWrites++
                live.value = it
                f.settings = it
            }
        }
        val vm = AssistantVM(store, f.memories, f.conversations, mockk(relaxed = true), ownershipGuard = f.guard)
        val readingOwner = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        f.onOwnerRead = { readingOwner.complete(Unit); finishWrite.await() }
        val writing = async { f.write("memory") }
        try {
            withTimeout(5_000) { readingOwner.await() }
            vm.removeAssistant(f.target)
            assertEquals("Manual settings removal must wait behind the writer", 0, settingsWrites)
            assertTrue(f.hasTarget())
            finishWrite.complete(Unit)
            withTimeout(5_000) { writing.await() }
            withTimeout(5_000) { live.first { state -> state.assistants.none { it.id == f.target.id } } }
            withTimeout(5_000) { f.memories.getMemoriesOfAssistantFlow(f.target.id.toString()).first { it.isEmpty() } }
            assertFalse(f.hasTarget())
            assertEquals(0, database.memoryDao().countOfAssistant(f.target.id.toString()))
        } finally {
            finishWrite.complete(Unit)
            writing.cancel()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test(timeout = 30_000) fun `DI ownership source rejects a removed owner despite stale UI presence`() = runBlocking {
        val owner = Assistant(name = "Удалён из DataStore")
        val raw = MutableStateFlow(Settings(assistants = emptyList()))
        val cached = MutableStateFlow(Settings(assistants = listOf(owner), assistantId = owner.id))
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlowRaw } returns raw
        every { settings.settingsFlow } returns cached
        val application = koinApplication { modules(repositoryModule, module { single { settings } }) }
        try {
            val guard = application.koin.get<AssistantOwnershipGuard>()
            assertSame("All repository bindings must resolve the singleton guard", guard, application.koin.get<AssistantOwnershipGuard>())
            var writes = 0
            val result = runCatching { guard.withOwner(owner.id.toString()) { writes++ } }
            assertTrue("Authoritative removal must override stale UI identity", result.isFailure)
            assertEquals("A stale UI cache cannot authorize a persisted write", 0, writes)
        } finally {
            application.close()
        }
    }

    @Test(timeout = 30_000) fun `DI ownership source accepts an authoritative owner despite stale UI absence`() = runBlocking {
        val owner = Assistant(name = "Есть в DataStore")
        val raw = MutableStateFlow(Settings(assistants = listOf(owner), assistantId = owner.id))
        val cached = MutableStateFlow(Settings(assistants = emptyList()))
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlowRaw } returns raw
        every { settings.settingsFlow } returns cached
        val application = koinApplication { modules(repositoryModule, module { single { settings } }) }
        try {
            val guard = application.koin.get<AssistantOwnershipGuard>()
            var writes = 0
            val result = runCatching { guard.withOwner(owner.id.toString()) { writes++ } }
            assertTrue("A lagging UI cache cannot deny an existing authoritative owner: $result", result.isSuccess)
            assertEquals(1, writes)
        } finally {
            application.close()
        }
    }

    companion object {
        private val writerKinds = listOf("chat", "memory", "cron", "workflow")
    }
}
