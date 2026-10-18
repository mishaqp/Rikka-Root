package me.rerere.rikkahub.ui.pages.assistant

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ScheduledJobPayload
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AssistantManualDeletionTest {
    private lateinit var database: AppDatabase
    private lateinit var workflowDatabase: WorkflowDatabase

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workflowDatabase = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        workflowDatabase.close()
        database.close()
        Dispatchers.resetMain()
    }

    private inner class Fixture(
        private val countFailure: Exception? = null,
        private val cleanupFailure: Exception? = null,
    ) : AutoCloseable {
        val target = Assistant(name = "Удаляемый")
        private val retained = Assistant(name = "Оставшийся")
        val live = MutableStateFlow(Settings(assistants = listOf(retained, target), assistantId = retained.id))
        var onOwnerRead: suspend () -> Unit = {}
        var settingsWrites = 0
        private val store = mockk<SettingsStore> {
            every { settingsFlow } returns live
            coEvery { update(any()) } coAnswers {
                firstArg<(Settings) -> Settings>()(live.value).also { settingsWrites++; live.value = it }
            }
        }
        private val guard = AssistantOwnershipGuard { owner ->
            onOwnerRead()
            live.value.assistants.any { it.id.toString() == owner }
        }
        private val conversations = if (cleanupFailure != null) mockk<ConversationRepository> {
            coEvery { deleteConversationOfAssistant(any()) } throws cleanupFailure
        } else ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, mockk(relaxed = true), mockk(relaxed = true), ownershipGuard = guard)
        val memories = MemoryRepository(database.memoryDao(), ownershipGuard = guard)
        val jobs = ScheduledJobRepository(database, object : CronPayloadCipher {
            override fun seal(jobId: String, plaintext: String) = "encrypted fixture"
            override fun open(jobId: String, encrypted: String) = encrypted
        }, ownershipGuard = guard)
        val workflows = if (countFailure != null) mockk<WorkflowRepository> {
            coEvery { countReferencingAssistant(any()) } throws countFailure
        } else WorkflowRepository(workflowDatabase.workflowDao(), workflowDatabase.workflowRunDao(),
            workflowDatabase, ownershipGuard = guard) { it }
        val job = ScheduledJobEntity(Uuid.random().toString(), target.id.toString(), "chat", "", mode = "prompt",
            scheduleType = "at", timezone = "UTC", createdAtMs = 1)
        val definition = WorkflowDefinition(Uuid.random().toString(), "Сохранить", enabled = false,
            trigger = TriggerSpec.Manual, actions = emptyList(), authoringAssistantId = target.id.toString())
        private val application = koinApplication {
            modules(viewModelModule, module {
                single { store }
                single { guard }
                single { conversations }
                single { memories }
                single { jobs }
                single { workflows }
                single<FilesManager> { mockk(relaxed = true) }
            })
        }
        // Exercise the production VM binding: new optional dependencies must not remain null here.
        val vm = application.koin.get<AssistantVM>()
        fun hasOwner() = live.value.assistants.any { it.id == target.id }

        fun remove(): List<Job> {
            val scopeJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            val previous = scopeJob.children.toSet()
            vm.removeAssistant(target)
            return scopeJob.children.filter { it !in previous }.toList()
        }

        override fun close() {
            vm.viewModelScope.cancel()
            application.close()
        }
    }

    private suspend fun awaitRemoval(jobs: List<Job>) = withTimeout(5_000) { jobs.forEach { it.join() } }

    private fun writerFirst(kind: String) = runBlocking {
        Fixture().use { f ->
            val memory = f.memories.addMemory(f.target.id.toString(), "Сохранить память при отказе")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.onOwnerRead = { entered.complete(Unit); release.await() }
            val writer = async {
                if (kind == "cron") f.jobs.create(f.job, ScheduledJobPayload(name = "Сохранить", prompt = "Проверить"))
                else f.workflows.upsert(f.definition)
            }
            try {
                withTimeout(5_000) { entered.await() }
                val removing = f.remove()
                assertEquals("Deletion must wait for the owner writer", 0, f.settingsWrites)
                release.complete(Unit)
                withTimeout(5_000) { writer.await() }
                awaitRemoval(removing)
                assertTrue("$kind must retain its assistant after manual removal", f.hasOwner())
                assertEquals("Rejected removal must not commit settings", 0, f.settingsWrites)
                val error = requireNotNull(f.vm.removalError.value)
                assertEquals(R.string.assistant_page_delete_has_scheduled_work, error.messageRes)
                assertEquals(if (kind == "cron") 1 else 0, error.cronJobs)
                assertEquals(if (kind == "workflow") 1 else 0, error.workflows)
                f.vm.clearRemovalError()
                assertNull(f.vm.removalError.value)
                assertNotNull(database.memoryDao().getMemoryById(memory.id))
                if (kind == "cron") assertNotNull(database.scheduledJobDao().getById(f.job.id))
                else assertNotNull(workflowDatabase.workflowDao().getById(f.definition.id))
            } finally {
                release.complete(Unit)
                writer.cancel()
            }
        }
    }

    @Test(timeout = 30_000) fun `cron writer first preserves owner and existing data on manual deletion`() = writerFirst("cron")

    @Test(timeout = 30_000) fun `workflow writer first preserves owner and existing data on manual deletion`() = writerFirst("workflow")

    @Test(timeout = 30_000) fun `manual removal cleans cron tombstone and its run history`() = runBlocking {
        Fixture().use { f ->
            val tombstone = f.job.copy(deleted = true, enabled = false)
            database.scheduledJobDao().insert(tombstone)
            val run = ScheduledJobRunEntity("run-${tombstone.id}", tombstone.id, tombstone.revision,
                "scheduled", 1, status = "succeeded", finishedAtMs = 2)
            database.scheduledJobRunDao().insert(run)
            awaitRemoval(f.remove())
            assertFalse("Only deleted jobs must not prevent assistant removal", f.hasOwner())
            assertNull(f.vm.removalError.value)
            assertNull(database.scheduledJobDao().getById(tombstone.id))
            assertNull(database.scheduledJobRunDao().getById(run.occurrenceId))
        }
    }

    @Test(timeout = 30_000) fun `manual removal retains owner referenced by malformed workflow JSON`() = runBlocking {
        Fixture().use { f ->
            val row = WorkflowEntity(id = f.definition.id, name = "Сохранить", enabled = false,
                definitionJson = "{\"authoring_assistant_id\":\"${f.target.id}\", BROKEN", createdAtMs = 1, updatedAtMs = 1)
            workflowDatabase.workflowDao().upsert(row)
            awaitRemoval(f.remove())
            assertTrue("Malformed persisted workflow must retain its owner", f.hasOwner())
            assertEquals(row, workflowDatabase.workflowDao().getById(row.id))
        }
    }

    @Test(timeout = 30_000) fun `manual removal still clears personal memory when no scheduled work exists`() = runBlocking {
        Fixture().use { f ->
            f.memories.addMemory(f.target.id.toString(), "Удалить вместе с ассистентом")
            awaitRemoval(f.remove())
            assertFalse(f.hasOwner())
            assertEquals(0, database.memoryDao().countOfAssistant(f.target.id.toString()))
        }
    }

    @Test(timeout = 30_000) fun `dependency count failure is visible and retains owner and memory`() = runBlocking {
        val failure = java.io.IOException("count failed")
        Fixture(countFailure = failure).use { f ->
            val memory = f.memories.addMemory(f.target.id.toString(), "Не удалять")
            awaitRemoval(f.remove())
            assertTrue(f.hasOwner())
            assertEquals(0, f.settingsWrites)
            assertNotNull(database.memoryDao().getMemoryById(memory.id))
            val error = requireNotNull(f.vm.removalError.value)
            assertEquals(R.string.assistant_page_delete_failed, error.messageRes)
            assertSame(failure, error.cause)
        }
    }

    @Test(timeout = 30_000) fun `cleanup failure is visible after owner removal without claiming rollback`() = runBlocking {
        val failure = java.io.IOException("cleanup failed")
        Fixture(cleanupFailure = failure).use { f ->
            f.memories.addMemory(f.target.id.toString(), "Удалить")
            awaitRemoval(f.remove())
            assertFalse(f.hasOwner())
            val error = requireNotNull(f.vm.removalError.value)
            assertEquals(R.string.assistant_page_delete_cleanup_failed, error.messageRes)
            assertSame(failure, error.cause)
        }
    }

    @Test(timeout = 30_000) fun `cancellation during dependency check remains cancellation`() = runBlocking {
        Fixture(countFailure = CancellationException("cancelled")).use { f ->
            awaitRemoval(f.remove())
            assertTrue(f.hasOwner())
            assertEquals(0, f.settingsWrites)
            assertNull(f.vm.removalError.value)
        }
    }
}
