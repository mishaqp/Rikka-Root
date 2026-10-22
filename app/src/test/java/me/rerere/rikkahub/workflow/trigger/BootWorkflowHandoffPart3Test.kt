package me.rerere.rikkahub.workflow.trigger

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.CronBootReceiver
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.scheduling.WorkManagerTestFixture
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult

/** Existing receiver/registry/engine APIs; assertions inspect real WorkManager Room rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class BootWorkflowHandoffPart3Test {
    private lateinit var context: Context
    private lateinit var database: WorkflowDatabase
    private lateinit var manager: WorkManagerImpl
    private lateinit var scope: AppScope
    private lateinit var registry: TriggerRegistry
    private lateinit var repository: WorkflowRepository
    private var recoveryEntered = CompletableDeferred<Unit>()
    private var releaseRecovery = CompletableDeferred<Unit>()
    private val releaseLookup = CompletableDeferred<Unit>()
    @Volatile private var holdLookup = false
    private val releaseAction = CompletableDeferred<Unit>()
    private val effects = AtomicInteger()
    private val owner = Assistant(localTools = listOf(LocalToolOption.Workflows))
    private val definition = WorkflowDefinition("boot-handoff-part3", "Проверка boot handoff",
        trigger = TriggerSpec.BootCompleted, authoringAssistantId = owner.id.toString(),
        actions = listOf(WorkflowAction("show_toast", buildJsonObject {})))

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        AndroidSettings.Global.putInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 17)
        listOf("", ".bak", ".new").forEach {
            java.io.File(context.noBackupFilesDir, "workflow-boot-outbox.json" + it).deleteRecursively()
        }
        manager = WorkManagerTestFixture.create(context, Configuration.Builder().build())
        WorkManagerImpl.setDelegate(manager)
        database = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        val rows = database.workflowDao()
        val guardedRows = object : WorkflowDao by rows {
            override suspend fun listEnabled(): List<WorkflowEntity> {
                if (holdLookup) releaseLookup.await()
                return rows.listEnabled()
            }
        }
        repository = WorkflowRepository(guardedRows, database.workflowRunDao(), database) { it }
        repository.upsert(definition)
        scope = AppScope()
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(init = false, assistants = listOf(owner), assistantId = owner.id))
        val engine = WorkflowEngine(repository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            releaseAction.await()
            effects.incrementAndGet()
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        })
        registry = TriggerRegistry(context, scope, repository).also { it.setEngineCallback(engine.triggerCallback) }
        registry.resync(listOf(definition))
        val scheduler = mockk<CronJobScheduler>()
        coEvery { scheduler.rearmAll() } coAnswers {
            val gate = releaseRecovery
            recoveryEntered.complete(Unit)
            gate.await()
        }
        startKoin { modules(module {
            single<Context> { context }; single { scheduler }; single { registry }
            single { repository }; single { engine }
        }) }
    }

    @After fun cleanup(): Unit = runBlocking {
        releaseRecovery.complete(Unit); releaseLookup.complete(Unit); releaseAction.complete(Unit)
        registry.shutdown()
        val triggerScope = TriggerRegistry::class.java.getDeclaredField("triggerScope").apply { isAccessible = true }
            .get(registry) as kotlinx.coroutines.CoroutineScope
        withTimeout(5_000) { triggerScope.coroutineContext[Job]?.join() }
        scope.cancel()
        WorkManagerTestFixture.close(manager); WorkManagerImpl.setDelegate(null)
        stopKoin(); database.close(); Dispatchers.resetMain()
    }

    private fun workRows(): Int = manager.workDatabase.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM workspec").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun dispatchBootAndObserveRowsAtFinish(holdRecovery: Boolean = false, waitForDeadline: Boolean = false): Int {
        recoveryEntered = CompletableDeferred(); releaseRecovery = CompletableDeferred()
        val receiver = CronBootReceiver()
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(5_000) { recoveryEntered.await() }
            val pending = requireNotNull(shadowOf(receiver).originalPendingResult)
            val finished = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
            val rowsAtFinish = AtomicInteger(-1)
            val failure = AtomicReference<Throwable?>()
            finished.addListener({
                try { rowsAtFinish.set(workRows()) } catch (error: Throwable) { failure.set(error); rowsAtFinish.set(-2) }
            }, Executor { it.run() })
            if (!holdRecovery) releaseRecovery.complete(Unit)
            withContext(Dispatchers.IO) { finished.get(if (waitForDeadline) 10 else 5, TimeUnit.SECONDS) }
            withTimeout(5_000) { while (rowsAtFinish.get() == -1) delay(10) }
            assertNull("Не удалось прочитать actual WorkManager rows", failure.get())
            return rowsAtFinish.get()
        } finally { releaseRecovery.complete(Unit); context.unregisterReceiver(receiver) }
    }

    @Test fun slowCronRecoveryCannotConsumeReceiverDeadlineBeforeDurableBootHandoff() = runBlocking {
        assertTrue("Даже при зависшем восстановлении cron Android уже владеет boot-задачей",
            dispatchBootAndObserveRowsAtFinish(holdRecovery = true, waitForDeadline = true) > 0)
        assertEquals(0, effects.get())
    }

    @Test fun slowWorkflowLookupCannotConsumeReceiverDeadlineBeforeDurableBootHandoff() = runBlocking {
        holdLookup = true
        try {
            assertTrue("Долгий запрос Room не должен оставлять законченный receiver без WorkSpec",
                dispatchBootAndObserveRowsAtFinish(waitForDeadline = true) > 0)
            assertEquals(0, effects.get())
        } finally { releaseLookup.complete(Unit) }
    }

    @Test fun receiverFinishesOnlyAfterDurableWorkManagerHandoffWhileEngineStillWaits() = runBlocking {
        val rows = dispatchBootAndObserveRowsAtFinish()
        assertTrue("До PendingResult.finish boot workflow должен иметь durable Android worker", rows > 0)
        assertEquals("Receiver должен ждать handoff, а не завершения action", 0, effects.get())
        assertFalse(releaseAction.isCompleted)
    }

    @Test fun damagedEnabledDefinitionStillGetsAnAndroidOwnerForRepair() = runBlocking {
        val row = requireNotNull(database.workflowDao().getById(definition.id))
        database.workflowDao().update(row.copy(definitionJson = "{damaged", updatedAtMs = row.updatedAtMs + 1))
        assertTrue("Повреждённый включённый сценарий требует worker retry, а не тихого завершения события",
            dispatchBootAndObserveRowsAtFinish() > 0)
        assertEquals(0, effects.get())
        assertTrue(WorkflowBootOutbox(context).pending().isNotEmpty())
    }

    @Test fun disabledBootWorkflowDoesNotCreateExecutionOrEffects() = runBlocking {
        repository.setEnabled(definition.id, false)
        registry.resync(emptyList())
        assertEquals(0, dispatchBootAndObserveRowsAtFinish())
        assertEquals(0, effects.get())
    }

    @Test fun duplicateBootEventAfterFirstCompletionCannotRunTheSameAcceptedEventAgain() = runBlocking {
        releaseAction.complete(Unit)
        dispatchBootAndObserveRowsAtFinish()
        val work = manager.workDatabase.openHelper.readableDatabase
            .query("SELECT id, generation FROM workspec WHERE worker_class_name = ?",
                arrayOf(WorkflowBootWorker::class.java.name)).use { cursor ->
                assertTrue("Receiver поставил настоящий boot worker", cursor.moveToFirst())
                cursor.getString(0) to cursor.getInt(1)
            }
        withContext(Dispatchers.Default) { WorkManagerTestFixture.startWork(manager, work.first, work.second) }
        withTimeout(5_000) { while (effects.get() < 1 || database.workflowRunDao().running().isNotEmpty()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        } }
        val rowsAfterFirst = workRows()
        dispatchBootAndObserveRowsAtFinish()
        val triggerScope = TriggerRegistry::class.java.getDeclaredField("triggerScope").apply { isAccessible = true }
            .get(registry) as kotlinx.coroutines.CoroutineScope
        withTimeout(5_000) { triggerScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() } }
        assertEquals("KEEP не должен повторять уже завершённый boot event", 1, effects.get())
        assertEquals("Завершённый event не создаёт нового executor", rowsAfterFirst, workRows())
    }
}
