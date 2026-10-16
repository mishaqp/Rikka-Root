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
class WorkflowProcessDeathTest {
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
        repository = WorkflowRepository(database.workflowDao(), database.workflowRunDao(), database) { it }
        startKoin { modules(module { single<Context> { context } }) }
    }

    @After fun tearDown() {
        stopKoin()
        database.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `running workflow has a Russian history label`() {
        assertEquals("выполняется", me.rerere.rikkahub.workflow.ui.workflowStatusLabel("RUNNING"))
    }

    @Test fun `a durable start receipt exists before the first action can affect the device`() = runBlocking {
        val definition = definition("running-receipt")
        repository.upsert(definition)
        var observed = emptyList<WorkflowRunEntity>()
        WorkflowEngine(repository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            observed = database.workflowRunDao().lastN(definition.id, 20)
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        }).fire(definition.id)

        assertEquals("A process kill must leave a start receipt before any side effect", 1, observed.size)
        assertEquals("RUNNING", observed.single().status)
        assertEquals("Finishing replaces the start receipt without duplicating history", 1, repository.lastRuns(definition.id).size)
        assertEquals(WorkflowRunStatus.SUCCESS, repository.lastRuns(definition.id).single().status)
        assertEquals(1, repository.getById(definition.id)!!.entity.runsTodayCount)
    }

    @Test fun `a fresh engine closes old running receipts without executing their actions again`() = runBlocking {
        val definition = definition("killed-process")
        repository.upsert(definition)
        val startedAt = System.currentTimeMillis() - 5_000
        database.workflowRunDao().insert(WorkflowRunEntity(workflowId = definition.id,
            firedAtMs = startedAt, status = "RUNNING", durationMs = 0))
        database.workflowDao().recordFire(definition.id, startedAt, "RUNNING", null, 1,
            java.time.LocalDate.now().toString())
        var effects = 0
        val newRepository = WorkflowRepository(database.workflowDao(), database.workflowRunDao(), database) { it }
        val outcome = WorkflowEngine(newRepository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            effects++
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        }).fire(definition.id)

        assertEquals(0, effects)
        assertEquals(WorkflowRunStatus.SKIPPED_DAILY_CAP, outcome.status)
        val history = database.workflowRunDao().lastN(definition.id, 20)
        assertTrue("No stale running row may survive process recovery", history.none { it.status == "RUNNING" })
        val interrupted = history.single { it.status == "FAILED" }
        assertEquals("process_interrupted", interrupted.errorMessage)
        assertEquals(startedAt, interrupted.firedAtMs)
        assertEquals(1, newRepository.getById(definition.id)!!.entity.runsTodayCount)
        val details = Json.parseToJsonElement((workflowGetTool(newRepository).execute(buildJsonObject {
            put("id", definition.id)
        }).single() as UIMessagePart.Text).text).jsonObject
        val message = details["history"]!!.jsonArray.first { it.jsonObject["status"]!!.jsonPrimitive.content == "FAILED" }
            .jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("Прервано: приложение было закрыто"))
    }

    private fun definition(id: String) = WorkflowDefinition(id, "Проверка восстановления", trigger = TriggerSpec.Manual,
        authoringAssistantId = owner.id.toString(), maxRunsPerDay = 1,
        actions = listOf(WorkflowAction("show_toast", buildJsonObject {})))
}
