package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.ScheduledJobPayload
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.scheduling.WorkManagerTestFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Exercises the scheduler's durable Room queue, rather than a second scheduling implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class CronJobExactSchedulingTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var jobs: ScheduledJobRepository
    private lateinit var runs: ScheduledJobRunRepository
    private lateinit var scheduler: CronJobScheduler
    private lateinit var scope: AppScope
    private lateinit var manager: WorkManagerImpl
    private lateinit var settings: SettingsStore
    private var now = 0L
    private val owner = Assistant(localTools = listOf(LocalToolOption.CronJobs))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        now = System.currentTimeMillis()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Preserve real WorkManager request/KEEP/expedited semantics without running real tools.
        manager = WorkManagerTestFixture.create(context, Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(appContext, parameters) {
                    override suspend fun doWork(): Result = awaitCancellation()
                }
        }).build())
        WorkManagerImpl.setDelegate(manager)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        jobs = ScheduledJobRepository(database, object : CronPayloadCipher {
            override fun seal(jobId: String, plaintext: String): String = plaintext
            override fun open(jobId: String, encrypted: String): String = encrypted
        })
        runs = ScheduledJobRunRepository(database)
        scope = AppScope().apply { cancel() }
        settings = SettingsStore(context, scope).also {
            it.settingsFlow.value = Settings(assistants = listOf(owner), assistantId = owner.id)
        }
        scheduler = CronJobScheduler(context, jobs, runs, settings, currentTimeMillis = { now })
    }

    @After fun tearDown() {
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestFixture.close(manager)
        WorkManagerImpl.setDelegate(null)
        database.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun futureOccurrenceIsAnExactWakeupAlarmAtThePersistedTime() = runBlocking {
        val at = now + 3_600_000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))

        assertTrue(scheduler.schedule(job))

        val run = runs.queuedForJob(job.id).single()
        assertEquals(at, run.scheduledAtMs)
        val alarm = cronAlarms().singleOrNull()
        assertNotNull("A future cron occurrence must use AlarmManager, not only delayed WorkManager", alarm)
        assertEquals(at, alarm!!.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.allowWhileIdle)
        val fallback = manager.getWorkInfosForUniqueWork(CronJobScheduler.fallbackWorkName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).single()
        assertEquals(CronJobFallbackWorker::class.java.name,
            workSpec(fallback.id).workerClassName)
        assertTrue(manager.getWorkInfosForUniqueWork(CronJobScheduler.workName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).isEmpty())
    }

    @Test fun successfulClaimArmsTheNextRepeatBeforeTheCurrentRunFinishes() = runBlocking {
        val job = jobs.create(job("cron"), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val first = runs.queuedForJob(job.id).single()
        val claimed = runs.claim(first.occurrenceId, first.scheduledAtMs) as ScheduledJobRunRepository.ClaimAttempt.Claimed

        scheduler.onOccurrenceClaimed(claimed.claim.run)

        assertEquals("running", runs.getById(first.occurrenceId)!!.status)
        val next = runs.queuedForJob(job.id).single()
        assertTrue(next.scheduledAtMs > first.scheduledAtMs)
        assertTrue(cronAlarms().any { it.triggerAtTime == next.scheduledAtMs })
        scheduler.onOccurrenceClaimed(claimed.claim.run)
        assertEquals(listOf(next.occurrenceId), runs.queuedForJob(job.id).map { it.occurrenceId })
    }

    @Test fun alarmAndFallbackShareOneImmediateExpeditedExecutorWithoutReplacingIt() = runBlocking {
        val at = now - 1000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))
        val run = runs.queue(job, at)!!

        assertTrue(scheduler.dispatchDueOccurrence(run.occurrenceId, at, job.revision))
        val first = manager.getWorkInfosForUniqueWork(CronJobScheduler.workName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).single()
        assertTrue(scheduler.dispatchDueOccurrence(run.occurrenceId))
        val second = manager.getWorkInfosForUniqueWork(CronJobScheduler.workName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).single()
        assertEquals(first.id, second.id)
        val work = workSpec(first.id)
        assertEquals(0L, work.initialDelay)
        assertTrue(work.expedited)
        assertEquals(CronJobWorker::class.java.name, work.workerClassName)
        assertEquals("queued", runs.getById(run.occurrenceId)!!.status)
    }

    @Test fun earlyAlarmAfterClockMovedBackCannotDispatchAnExecution() = runBlocking {
        val at = now + 3_600_000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val run = runs.queuedForJob(job.id).single()

        assertFalse(scheduler.dispatchDueOccurrence(run.occurrenceId, at, job.revision))

        assertEquals("queued", runs.getById(run.occurrenceId)!!.status)
        assertTrue(manager.getWorkInfosForUniqueWork(CronJobScheduler.workName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).isEmpty())
        assertEquals(at, cronAlarms().single().triggerAtTime)
    }

    @Test fun clockRecoveryRearmsTheSameFutureImmutableOccurrence() = runBlocking {
        val at = now + 3_600_000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val first = runs.queuedForJob(job.id).single()

        scheduler.onClockChanged()

        assertEquals(listOf(first.occurrenceId), runs.queuedForJob(job.id).map { it.occurrenceId })
        assertEquals(job.revision, jobs.getById(job.id)!!.revision)
        assertEquals(at, cronAlarms().single().triggerAtTime)
    }

    @Test fun clockMovingBackRecomputesAnEarlierNextCronWithoutTombstoningTheLaterId() = runBlocking {
        now = Instant.parse("2026-10-09T20:00:00Z").toEpochMilli()
        val job = jobs.create(job("cron", expression = "0 4 * * *"), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val later = runs.queuedForJob(job.id).single()
        assertEquals(Instant.parse("2026-10-10T04:00:00Z").toEpochMilli(), later.scheduledAtMs)
        now = Instant.parse("2026-10-08T20:00:00Z").toEpochMilli()

        scheduler.onClockChanged()
        scheduler.onClockChanged()

        val earlier = Instant.parse("2026-10-09T04:00:00Z").toEpochMilli()
        assertEquals(earlier, jobs.getById(job.id)!!.nextRunAtMs)
        assertEquals(job.revision, jobs.getById(job.id)!!.revision)
        val queued = runs.queuedForJob(job.id)
        assertEquals(setOf(earlier, later.scheduledAtMs), queued.map { it.scheduledAtMs }.toSet())
        assertEquals(2, queued.size)
        assertEquals("queued", runs.getById(later.occurrenceId)!!.status)
        assertEquals(setOf(earlier, later.scheduledAtMs), cronAlarms().map { it.triggerAtTime }.toSet())
    }

    @Test fun pausingCancelsTheAlarmAndQueuedLedgerBeforeAnyActionCanBeClaimed() = runBlocking {
        val at = now + 3_600_000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val run = runs.queuedForJob(job.id).single()
        jobs.setEnabled(job.id, job.ownerAssistantId, false)

        scheduler.cancel(job.id)

        assertTrue(cronAlarms().isEmpty())
        assertEquals("cancelled", runs.getById(run.occurrenceId)!!.status)
        assertTrue(scheduler.dispatchDueOccurrence(run.occurrenceId, at, job.revision))
        assertTrue(manager.getWorkInfosForUniqueWork(CronJobScheduler.workName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).isEmpty())
    }

    @Test fun missingExactAlarmAccessKeepsTheDistinctDelayedFallback() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val at = now + 3_600_000L
        val job = jobs.create(job("once", at = at), ScheduledJobPayload("fixture"))

        assertTrue(scheduler.schedule(job))

        val run = runs.queuedForJob(job.id).single()
        assertTrue(cronAlarms().isEmpty())
        val fallback = manager.getWorkInfosForUniqueWork(CronJobScheduler.fallbackWorkName(run.occurrenceId))
            .get(5, TimeUnit.SECONDS).single()
        assertEquals(CronJobFallbackWorker::class.java.name,
            workSpec(fallback.id).workerClassName)
    }

    @Test fun completedRepeatingOccurrenceArmsTheNextExactAlarmWithoutReplayingItsId() = runBlocking {
        val job = jobs.create(job("cron"), ScheduledJobPayload("fixture"))
        assertTrue(scheduler.schedule(job))
        val first = runs.queuedForJob(job.id).single()
        val claim = runs.claim(first.occurrenceId, first.scheduledAtMs) as ScheduledJobRunRepository.ClaimAttempt.Claimed
        runs.finish(first.occurrenceId, claim.claim.token, "succeeded", null, first.scheduledAtMs)

        scheduler.afterOccurrence(job.id, job.revision)

        val next = runs.queuedForJob(job.id).single()
        assertTrue(next.scheduledAtMs > first.scheduledAtMs)
        assertTrue(next.occurrenceId != first.occurrenceId)
        assertEquals(next.scheduledAtMs, jobs.getById(job.id)!!.nextRunAtMs)
        assertTrue("Every repeat must arm its next AlarmManager occurrence",
            cronAlarms().any { it.triggerAtTime == next.scheduledAtMs && it.allowWhileIdle })
    }

    private fun cronAlarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className ==
            "me.rerere.rikkahub.service.CronJobAlarmReceiver" }

    private suspend fun workSpec(id: java.util.UUID) = withContext(Dispatchers.IO) {
        manager.workDatabase.workSpecDao().getWorkSpec(id.toString())!!
    }

    private fun job(type: String, at: Long? = null, expression: String = "* * * * *") = ScheduledJobEntity(
        id = "cron-exact-fixture", ownerAssistantId = owner.id.toString(),
        creatorConversationId = owner.id.toString(), payloadCiphertext = "fixture",
        mode = "direct", scheduleType = type, atUnixMs = at,
        cronExpression = if (type == "cron") expression else null,
        timezone = "UTC", createdAtMs = now,
    )
}
