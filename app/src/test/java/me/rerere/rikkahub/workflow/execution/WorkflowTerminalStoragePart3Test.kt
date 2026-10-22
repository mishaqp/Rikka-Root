package me.rerere.rikkahub.workflow.execution

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTerminalStoragePart3Test {
    private lateinit var context: Context
    private lateinit var database: WorkflowDatabase
    private lateinit var repository: WorkflowRepository
    private lateinit var settings: SettingsStore
    private lateinit var scope: AppScope
    private var terminalFailures = 0
    private var effects = 0
    private val owner = Assistant(localTools = listOf(LocalToolOption.Workflows))
    private val definition = WorkflowDefinition("terminal-storage-part3", "Проверка сохранения результата",
        trigger = TriggerSpec.Manual, authoringAssistantId = owner.id.toString(), cooldownSeconds = 3600,
        actions = listOf(WorkflowAction("show_toast", buildJsonObject {})))

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        scope = AppScope().apply { cancel() }
        settings = SettingsStore(context, scope).also {
            it.settingsFlow.value = Settings(init = false, assistants = listOf(owner), assistantId = owner.id)
        }
        database = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        val actual = database.workflowRunDao()
        val faultDao = object : WorkflowRunDao by actual {
            override suspend fun insert(entity: WorkflowRunEntity): Long {
                if (terminalFailures > 0) {
                    terminalFailures--
                    throw IOException("temporary receipt storage failure")
                }
                return actual.insert(entity)
            }
        }
        repository = WorkflowRepository(database.workflowDao(), faultDao, database) { it }
        repository.upsert(definition)
        startKoin { modules(module { single<Context> { context } }) }
    }
    @After fun cleanup() { stopKoin(); database.close(); scope.cancel(); Dispatchers.resetMain() }
    private fun engine(repo: WorkflowRepository = repository) = WorkflowEngine(repo, settings, ContextProvider(context),
        WorkflowActionRunner { _, _, _ -> effects++; HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = "Завершённый результат") })

    @Test fun failedTerminalWriteCannotBeReportedAsAnOrdinarySuccess() = runBlocking {
        terminalFailures = 1
        val result = engine().fire(definition.id)
        assertNotEquals("Нельзя обещать сохранённый успех при ошибке terminal receipt", WorkflowRunStatus.SUCCESS, result.status)
        assertTrue("Нужна видимая безопасная причина неопределённости", !result.error.isNullOrBlank())
        assertEquals(1, effects)
        assertEquals("Durable receipt эффекта нельзя удалять", "RUNNING", database.workflowRunDao().running().single().status)
        assertEquals(1, repository.getById(definition.id)!!.entity.runsTodayCount)
    }

    @Test fun oneShotTerminalFaultCannotAllowASecondEffectInsideCooldown() = runBlocking {
        terminalFailures = 1
        val engine = engine()
        engine.fire(definition.id)
        val next = engine.fire(definition.id)
        assertEquals("Неопределённый terminal receipt должен удерживать cooldown", 1, effects)
        assertNotEquals(WorkflowRunStatus.SUCCESS, next.status)
        assertEquals(1, repository.getById(definition.id)!!.entity.runsTodayCount)
    }

    @Test fun permanentTerminalFaultRetainsOneDurableUncertainReceiptWithoutBlindReplay() = runBlocking {
        terminalFailures = 100
        val engine = engine()
        repeat(3) { engine.fire(definition.id) }
        assertEquals("Повторяется сохранение receipt, но не завершённый action", 1, effects)
        assertEquals(1, database.workflowRunDao().running().size)
        assertEquals(1, repository.getById(definition.id)!!.entity.runsTodayCount)
    }

    @Test fun freshRepositoryRecoversFailedTerminalWriteWithoutReplayingTheEffect() = runBlocking {
        terminalFailures = 1
        engine().fire(definition.id)
        val fresh = WorkflowRepository(database.workflowDao(), database.workflowRunDao(), database) { it }
        val outcome = engine(fresh).fire(definition.id)
        assertEquals(1, effects)
        assertEquals(WorkflowRunStatus.SKIPPED_COOLDOWN, outcome.status)
        assertTrue(database.workflowRunDao().running().isEmpty())
        assertEquals("process_interrupted", fresh.lastRuns(definition.id).first { it.status == WorkflowRunStatus.FAILED }.errorMessage)
        assertEquals(1, fresh.getById(definition.id)!!.entity.runsTodayCount)
    }
}
