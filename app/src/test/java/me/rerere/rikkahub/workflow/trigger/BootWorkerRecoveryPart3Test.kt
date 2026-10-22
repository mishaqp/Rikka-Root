package me.rerere.rikkahub.workflow.trigger

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import android.provider.Settings as AndroidSettings
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.scheduling.WorkManagerTestFixture
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** New-worker controls, not a claim to simulate Android killing an actual phone process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class BootWorkerRecoveryPart3Test {
    private lateinit var context: Context
    private lateinit var database: WorkflowDatabase
    private lateinit var manager: WorkManagerImpl
    private lateinit var repository: WorkflowRepository
    private lateinit var engine: WorkflowEngine
    private lateinit var outbox: WorkflowBootOutbox
    private lateinit var liveSettings: MutableStateFlow<Settings>
    private var effects = 0
    private var snapshotFailure = false
    private var terminalFailure = false
    private var action: suspend () -> HeadlessTaskResult = { HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED) }
    private val eventId = "a".repeat(64)
    private val owner = Assistant(localTools = listOf(LocalToolOption.Workflows))
    private val definition = WorkflowDefinition("boot-worker-part3", "Проверка worker recovery",
        trigger = TriggerSpec.BootCompleted, authoringAssistantId = owner.id.toString(), cooldownSeconds = 0,
        actions = listOf(WorkflowAction("show_toast", buildJsonObject {})))
    private val journal: File get() = File(context.noBackupFilesDir, "workflow-boot-outbox.json")

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        listOf("", ".bak", ".new").forEach { File(journal.path + it).deleteRecursively() }
        manager = WorkManagerTestFixture.create(context, Configuration.Builder().build())
        database = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        val rows = database.workflowDao()
        val dao = object : WorkflowDao by rows {
            override suspend fun listEnabled(): List<WorkflowEntity> {
                if (snapshotFailure) throw IOException("temporary snapshot failure")
                return rows.listEnabled()
            }
        }
        val receipts = database.workflowRunDao()
        val runDao = object : WorkflowRunDao by receipts {
            override suspend fun insert(entity: WorkflowRunEntity): Long {
                if (terminalFailure) throw IOException("temporary terminal history failure")
                return receipts.insert(entity)
            }
        }
        repository = WorkflowRepository(dao, runDao, database) { it }
        repository.upsert(definition)
        liveSettings = MutableStateFlow(Settings(init = false, assistants = listOf(owner), assistantId = owner.id))
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns liveSettings
        engine = WorkflowEngine(repository, settings, ContextProvider(context), WorkflowActionRunner { _, _, _ ->
            effects++
            action()
        })
        installKoin()
        outbox = WorkflowBootOutbox(context)
        outbox.accept(eventId)
    }
    private fun installKoin() { startKoin { modules(module {
        single<Context> { context }; single { repository }; single { engine }
    }) } }
    @After fun cleanup() {
        stopKoin(); WorkManagerTestFixture.close(manager); database.close()
        listOf("", ".bak", ".new").forEach { File(journal.path + it).deleteRecursively() }
        Dispatchers.resetMain()
    }
    private fun worker(): WorkflowBootWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        val parameters = WorkerParameters(UUID.randomUUID(),
            Data.Builder().putString(WorkflowBootScheduling.EVENT_ID, eventId).build(), emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, Executor { it.run() }, Dispatchers.Default,
            manager.workTaskExecutor, factory, mockk<ProgressUpdater>(relaxed = true), mockk<ForegroundUpdater>(relaxed = true))
        return WorkflowBootWorker(context, parameters)
    }
    private suspend fun runWorker() = withContext(Dispatchers.Default) { worker().doWork() }

    @Test fun dependencyFailureBeforeClaimRetriesThenHealthyWorkerExecutesOnlyOnce() = runBlocking {
        stopKoin()
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertTrue(outbox.get(eventId)!!.claimed.isEmpty()); assertEquals(0, effects)
        installKoin()
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
    }

    @Test fun databaseSnapshotFailureBeforeClaimRetainsAcceptedEventForHealthyRetry() = runBlocking {
        snapshotFailure = true
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertNull(outbox.get(eventId)!!.workflowIds)
        assertTrue(outbox.get(eventId)!!.claimed.isEmpty()); assertEquals(0, effects)
        snapshotFailure = false
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
    }

    @Test fun damagedEnabledDefinitionBeforeSnapshotStaysPendingUntilRepair() = runBlocking {
        damageDefinition(enabled = true)
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(0, effects)
        assertNull(outbox.get(eventId)!!.workflowIds)
        assertFalse(outbox.get(eventId)!!.complete)
        repository.upsert(definition)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
    }

    @Test fun damagedEnabledDefinitionAfterSnapshotCannotConsumeItsUnclaimedId() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        damageDefinition(enabled = true)
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.claimed.isEmpty())
        assertFalse(outbox.get(eventId)!!.complete)
        repository.upsert(definition)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
    }

    @Test fun damagedDisabledDefinitionMayFinishWithoutAnEffect() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        damageDefinition(enabled = false)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.complete)
    }

    private suspend fun damageDefinition(enabled: Boolean) {
        val row = requireNotNull(database.workflowDao().getById(definition.id))
        database.workflowDao().update(row.copy(enabled = enabled, definitionJson = "{damaged",
            updatedAtMs = row.updatedAtMs + 1))
    }

    @Test fun freshStoreAndActualWorkerNeverReplayTheClaimCommittedBeforeAProcessInterruption() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        assertTrue(outbox.claim(eventId, definition.id))
        val recreated = WorkflowBootOutbox(context)
        assertEquals(setOf(definition.id), recreated.get(eventId)!!.claimed)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Claim — консервативный предел, даже если engine не успел начаться", 0, effects)
        assertTrue(recreated.get(eventId)!!.complete)
    }

    @Test fun parentCancellationAfterActionStartsRemainsCancellationAndCannotReplayOnFreshWorker() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        action = { entered.complete(Unit); awaitCancellation() }
        val job = launch(Dispatchers.Default) { worker().doWork() }
        withTimeout(5_000) { entered.await() }
        assertEquals(setOf(definition.id), WorkflowBootOutbox(context).get(eventId)!!.claimed)
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(1, effects)
        action = { HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
    }

    @Test fun terminalHistoryFaultCannotReplayEffectsOnAutomaticRetryEvenWithZeroCooldown() = runBlocking {
        terminalFailure = true
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
        assertEquals(1, database.workflowRunDao().running().size)
        terminalFailure = false
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
        assertEquals(setOf(definition.id), outbox.get(eventId)!!.claimed)
    }

    @Test fun realFinalOutboxWriteFaultRetriesOnlyStorageAfterTheClaimedEffect() = runBlocking {
        val blockedNewFile = File(journal.path + ".new")
        action = {
            assertTrue("Actual atomic-write path заблокирован после выполненного эффекта", blockedNewFile.mkdir())
            File(blockedNewFile, "block").writeText("Не удалять fault при AtomicFile.openRead")
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        }
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(1, effects)
        assertEquals(setOf(definition.id), WorkflowBootOutbox(context).get(eventId)!!.claimed)
        assertFalse(outbox.get(eventId)!!.complete)
        assertTrue(blockedNewFile.deleteRecursively())
        stopKoin()
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Повторяется только terminal storage, не action", 1, effects)
        assertTrue(outbox.get(eventId)!!.complete)
    }

    @Test
    @Config(sdk = [28, 35], shadows = [PostCommitReadFailureAtomicFile::class])
    fun verificationFailureAfterCommitPreservesClaimsOnLegacyAndModernAndroid() = runBlocking {
        action = {
            PostCommitReadFailureAtomicFile.failAfterNextCommit.set(true)
            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
        }
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(1, effects)
        assertTrue("Ошибка проверки после commit не должна удалять единственный журнал", journal.isFile)
        val recreated = WorkflowBootOutbox(context)
        assertEquals(setOf(definition.id), recreated.get(eventId)!!.claimed)
        assertFalse(recreated.claim(eventId, definition.id))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Новый worker не повторяет уже выполненное действие", 1, effects)
    }

    @Test fun revokedOwnerFeatureStillBlocksTheActualEngineWithoutEffects() = runBlocking {
        liveSettings.value = liveSettings.value.copy(assistants = listOf(owner.copy(localTools = emptyList())))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.complete)
        assertTrue(repository.lastRuns(definition.id).single().errorMessage!!.contains("Владелец"))
    }

    @Test fun actualClaimWriteFailurePreventsEffectsUntilTheSameEventCanBeClaimed() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        val blocked = File(journal.path + ".new")
        assertTrue(blocked.mkdir())
        File(blocked, "block").writeText("Сбой записи claim")
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.claimed.isEmpty())
        assertTrue(blocked.deleteRecursively())
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
        assertTrue(outbox.get(eventId)!!.complete)
    }

    @Test fun disabledLiveWorkflowRemainsBlockedAfterItsIdWasSnapshotted() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        repository.setEnabled(definition.id, false)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.complete)
        assertEquals(me.rerere.rikkahub.workflow.model.WorkflowRunStatus.SKIPPED_DISABLED,
            repository.lastRuns(definition.id).single().status)
    }

    @Test fun changedTriggerCannotRunAnotherWorkflowFromAnOldBootSnapshot() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        repository.upsert(definition.copy(trigger = TriggerSpec.Manual))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, effects)
        assertTrue(outbox.get(eventId)!!.complete)
    }

    @Test fun snapshotNeverAdoptsAWorkflowCreatedAfterTheAcceptedEvent() = runBlocking {
        outbox.snapshot(eventId, listOf(definition.id))
        repository.upsert(definition.copy(id = "boot-created-later"))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, effects)
        assertEquals(listOf(definition.id), outbox.get(eventId)!!.workflowIds)
        assertTrue(repository.lastRuns("boot-created-later").isEmpty())
    }

    @Test fun damagedJournalCannotBecomeAnEmptyQueueAndReplayEffects() = runBlocking {
        journal.writeText("{damaged")
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(0, effects)
        assertEquals("{damaged", journal.readText())
    }

    @Test fun completedBootIdentitySurvivesPackageMetadataChangesButNewBootHasItsOwnEvent() = runBlocking {
        repository.setEnabled(definition.id, false)
        AndroidSettings.Global.putInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 17)
        WorkflowBootScheduling(context).accept("BOOT")
        val afterFirst = journal.readText()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime += 1_000
        shadowOf(context.packageManager).installPackage(info)
        WorkflowBootScheduling(context).accept("BOOT")
        assertEquals("Обновление пакета не меняет identity той же загрузки", afterFirst, journal.readText())
        AndroidSettings.Global.putInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 18)
        WorkflowBootScheduling(context).accept("BOOT")
        assertEquals(org.json.JSONObject(afterFirst).length() + 1, org.json.JSONObject(journal.readText()).length())
        assertEquals(0, effects)
    }
}

/** Fault only the read-back after the real platform commit; keep SDK-specific AtomicFile semantics. */
@Implements(AtomicFile::class)
class PostCommitReadFailureAtomicFile {
    @RealObject private lateinit var atomic: AtomicFile

    @Implementation
    fun finishWrite(stream: FileOutputStream) {
        Shadow.directlyOn<Any?, AtomicFile>(atomic, AtomicFile::class.java, "finishWrite",
            ClassParameter.from(FileOutputStream::class.java, stream))
        if (atomic.baseFile.name == "workflow-boot-outbox.json" && failAfterNextCommit.compareAndSet(true, false)) {
            failNextRead.set(true)
        }
    }

    @Implementation
    fun openRead(): FileInputStream {
        if (atomic.baseFile.name == "workflow-boot-outbox.json" && failNextRead.compareAndSet(true, false)) {
            throw IOException("Временный сбой проверки уже сохранённого журнала")
        }
        return Shadow.directlyOn(atomic, AtomicFile::class.java, "openRead")
    }

    companion object {
        val failAfterNextCommit = AtomicBoolean()
        private val failNextRead = AtomicBoolean()
        @JvmStatic @Resetter fun reset() { failAfterNextCommit.set(false); failNextRead.set(false) }
    }
}
