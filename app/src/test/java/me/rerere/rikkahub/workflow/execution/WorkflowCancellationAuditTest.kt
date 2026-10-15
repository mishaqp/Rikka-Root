package me.rerere.rikkahub.workflow.execution

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.tools.workflowGetTool
import me.rerere.rikkahub.workflow.tools.workflowListTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowCancellationAuditTest {
    private lateinit var context: Context
    private lateinit var database: WorkflowDatabase
    private lateinit var repository: WorkflowRepository
    private lateinit var settings: SettingsStore
    private lateinit var scope: AppScope
    private val owner = Assistant(localTools = listOf(LocalToolOption.Workflows))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        scope = AppScope().apply { cancel() }
        settings = SettingsStore(context, scope).also {
            it.settingsFlow.value = Settings(assistants = listOf(owner), assistantId = owner.id)
        }
        database = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        repository = WorkflowRepository(database.workflowDao(), database.workflowRunDao()) { it }
        startKoin { modules(module { single<Context> { context } }) }
    }

    @After fun tearDown() {
        stopKoin()
        database.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `cancellation after an action begins preserves durable history and the daily execution limit`() = runBlocking {
        val definition = WorkflowDefinition(
            "cancelled-workflow", "Cancellation fixture", trigger = TriggerSpec.Manual,
            authoringAssistantId = owner.id.toString(), maxRunsPerDay = 1,
            actions = listOf(WorkflowAction("show_toast", buildJsonObject {})),
        )
        repository.upsert(definition)
        var effects = 0
        val actionStarted = CompletableDeferred<Unit>()
        val engine = WorkflowEngine(repository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            effects++
            actionStarted.complete(Unit)
            awaitCancellation()
        })

        val running = launch(Dispatchers.Default) { engine.fire(definition.id) }
        withTimeout(5_000) { actionStarted.await() }
        running.cancelAndJoin()

        assertTrue("Caller cancellation must still propagate", running.isCancelled)
        assertEquals("A cancelled attempt after a possible effect must not disappear", 1,
            repository.lastRuns(definition.id).size)
        assertEquals("A partly executed attempt must consume the daily limit", 1,
            repository.getById(definition.id)!!.entity.runsTodayCount)

        // Read the real persisted cancellation through both public tool producers.
        val arguments = buildJsonObject { put("id", definition.id) }
        val details = Json.parseToJsonElement((workflowGetTool(repository).execute(arguments).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("execution_interrupted", details["last_run_error"]!!.jsonPrimitive.content)
        val listed = Json.parseToJsonElement((workflowListTool(repository).execute(buildJsonObject {}).single() as UIMessagePart.Text).text)
            .jsonObject["items"]!!.jsonArray.single().jsonObject
        for (message in listOf(
            details["last_run_message"]!!.jsonPrimitive.content,
            details["history"]!!.jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content,
            listed["last_run_message"]!!.jsonPrimitive.content,
        )) {
            assertTrue("Cancellation must be explained: $message", message.contains("остановлено", ignoreCase = true))
            assertTrue("Possible earlier effects must be explained: $message", message.contains("могла выполниться", ignoreCase = true))
            assertTrue("Check state before retry: $message", message.contains("Проверьте состояние"))
        }

        val following = WorkflowEngine(repository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            effects++
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        }).fire(definition.id)
        assertEquals(WorkflowRunStatus.SKIPPED_DAILY_CAP, following.status)
        assertEquals("The following trigger must not replay the earlier effect", 1, effects)
    }

    @Test fun `cancellation before final history insert retains a completed action and its quota`() = runBlocking {
        assertCompletedActionAccountingSurvivesCancellation(gateBeforeInsert = true)
    }

    @Test fun `cancellation after final history insert cannot lose quota or replay the completed action`() = runBlocking {
        assertCompletedActionAccountingSurvivesCancellation(gateBeforeInsert = false)
    }

    private suspend fun assertCompletedActionAccountingSurvivesCancellation(gateBeforeInsert: Boolean) = coroutineScope {
        val originalWorkflowDao = database.workflowDao()
        val originalRunDao = database.workflowRunDao()
        val historyInserted = AtomicBoolean(false)
        val gateUsed = AtomicBoolean(false)
        val accountingPaused = CompletableDeferred<Unit>()
        val releaseAccounting = CompletableDeferred<Unit>()
        suspend fun pauseAccounting() {
            accountingPaused.complete(Unit)
            releaseAccounting.await()
        }
        val definition = WorkflowDefinition(
            "final-accounting-$gateBeforeInsert", "Completed action fixture", trigger = TriggerSpec.Manual,
            authoringAssistantId = owner.id.toString(), maxRunsPerDay = 1,
            actions = listOf(WorkflowAction("show_toast", buildJsonObject {})),
        )
        val runDao = object : WorkflowRunDao by originalRunDao {
            override suspend fun insert(entity: WorkflowRunEntity): Long {
                if (gateBeforeInsert && gateUsed.compareAndSet(false, true)) pauseAccounting()
                return originalRunDao.insert(entity).also { historyInserted.set(true) }
            }
        }
        val workflowDao = object : WorkflowDao by originalWorkflowDao {
            override suspend fun getById(id: String): WorkflowEntity? {
                if (!gateBeforeInsert && id == definition.id && historyInserted.get() && gateUsed.compareAndSet(false, true)) {
                    pauseAccounting()
                }
                return originalWorkflowDao.getById(id)
            }
        }
        val gatedRepository = WorkflowRepository(workflowDao, runDao) { it }
        gatedRepository.upsert(definition)
        var effects = 0
        val engine = WorkflowEngine(gatedRepository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            effects++
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = "completed")
        })
        val running = launch(Dispatchers.Default) { engine.fire(definition.id) }
        try {
            withTimeout(5_000) { accountingPaused.await() }
            assertEquals("The action has already completed before cancellation", 1, effects)
            running.cancel()
        } finally {
            // Let a protected final write finish after cancellation; never hang a correct implementation.
            releaseAccounting.complete(Unit)
        }
        running.cancelAndJoin()

        assertTrue("Cancellation still propagates after final accounting", running.isCancelled)
        val history = gatedRepository.lastRuns(definition.id)
        assertEquals("A completed action must keep exactly one history row", 1, history.size)
        assertEquals(WorkflowRunStatus.SUCCESS, history.single().status)
        assertEquals("History and the consumed daily quota must stay consistent", 1,
            gatedRepository.getById(definition.id)!!.entity.runsTodayCount)
        assertEquals(WorkflowRunStatus.SKIPPED_DAILY_CAP, engine.fire(definition.id).status)
        assertEquals("A following trigger must not repeat the completed effect", 1, effects)
    }
}
