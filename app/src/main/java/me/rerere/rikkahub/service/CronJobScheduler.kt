package me.rerere.rikkahub.service

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.CronRunPolicy
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.scheduling.AndroidExactAlarmScheduler
import me.rerere.rikkahub.service.scheduling.ExactAlarmDomain
import me.rerere.rikkahub.service.scheduling.ExactAlarmScheduler
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class CronJobScheduler(
    context: Context,
    private val jobs: ScheduledJobRepository,
    private val runs: ScheduledJobRunRepository,
    private val settingsStore: SettingsStore,
    private val alarms: ExactAlarmScheduler = AndroidExactAlarmScheduler(context),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val manager by lazy { WorkManager.getInstance(context.applicationContext) }
    private val mutex = Mutex()
    private val recovery = CronRecoveryBarrier {
        settingsStore.settingsFlow.first { !it.init }
        runs.recoverInterrupted(currentTimeMillis())
    }

    suspend fun awaitRecovery() = recovery.awaitReady()

    suspend fun restoreAfterProcessStart() {
        awaitRecovery()
        rearmAll()
    }
    suspend fun onClockChanged() {
        awaitRecovery()
        mutex.withLock {
            val now = currentTimeMillis()
            for (job in jobs.active()) {
                val previous = job.nextRunAtMs ?: continue
                // Once timestamps remain absolute; overdue plans retain the existing catchup policy.
                if (job.scheduleType != "cron" || previous <= now || !eligible(job)) continue
                val next = nextScheduled(job, now)
                if (next != previous) jobs.setNext(job.id, job.revision, next)
                // A queued later cron occurrence is still legitimate. Keep its immutable ID rather
                // than tombstoning it or revoking a claimed run by changing the job revision.
            }
        }
        rearmAll()
    }
    suspend fun rearmAll() {
        awaitRecovery()
        // Room is the durable outbox: restore every existing unclaimed ID before adding new plans.
        for ((jobId, queued) in runs.queued().groupBy { it.jobId }) mutex.withLock {
            reconcileQueued(jobs.getById(jobId), queued)
        }
        for (job in jobs.active()) rearm(job.id)
    }
    suspend fun rearm(jobId: String): Boolean = mutex.withLock {
        val job = jobs.getById(jobId) ?: return@withLock false
        if (!featureEnabled(job)) {
            // Re-enabling must use a fresh future ID, while claimed watermarks and totals stay durable.
            jobs.invalidateSchedule(job.id, job.revision)
            cancelWorkAndLedger(job.id)
            return@withLock false
        }
        reconcileQueued(job, runs.queuedForJob(jobId))
        if (!eligible(job)) { jobs.setNext(job.id, job.revision, null); cancelWorkAndLedger(job.id); return@withLock false }
        val now = currentTimeMillis()
        val first = job.nextRunAtMs ?: nextScheduled(job, now)
        val plan = CatchupPlanner.plan(first, now, job.catchup) { nextScheduled(job, it) }
        var queued = true
        for (missed in plan.missed) if (enqueue(job, missed, false) == null) queued = false
        if (!jobs.setNext(job.id, job.revision, plan.next)) return@withLock false
        plan.next?.let { if (enqueue(job, it, false) == null) queued = false }
        queued && (plan.next != null || plan.missed.isNotEmpty())
    }
    suspend fun schedule(job: ScheduledJobEntity): Boolean = rearm(job.id)
    suspend fun afterOccurrence(jobId: String, revision: Long) = mutex.withLock {
        val job = jobs.getById(jobId) ?: return@withLock
        if (job.revision != revision) return@withLock
        if (!eligible(job)) {
            reconcileQueued(job, runs.queuedForJob(job.id))
            jobs.setNext(job.id, revision, null)
            return@withLock
        }
        val next = nextScheduled(job, currentTimeMillis())
        if (jobs.setNext(job.id, revision, next)) next?.let { enqueue(job, it, false) }
    }
    suspend fun triggerNow(jobId: String, owner: String): ScheduledJobRunEntity? = mutex.withLock {
        val job = jobs.getOwned(jobId, owner) ?: return@withLock null
        if (!eligible(job)) return@withLock null
        enqueue(job, currentTimeMillis(), true)
    }
    /** Metadata is invalidated before this call. One shared tag includes scheduled/manual/catchup. */
    suspend fun cancel(jobId: String) = mutex.withLock { cancelWorkAndLedger(jobId) }
    private suspend fun cancelWorkAndLedger(jobId: String) {
        try {
            runs.queuedForJob(jobId).forEach { cancelOccurrenceTriggers(it) }
            cancelWork(jobId)
        }
        finally { withContext(NonCancellable) { runs.cancelForJob(jobId, currentTimeMillis()) } }
    }
    private suspend fun cancelWork(jobId: String) = runInterruptible(Dispatchers.IO) {
        manager.cancelAllWorkByTag(jobTag(jobId)).result.get(15, TimeUnit.SECONDS)
    }
    private fun featureEnabled(job: ScheduledJobEntity): Boolean =
        settingsStore.settingsFlow.value.assistants.any { it.id.toString() == job.ownerAssistantId && LocalToolOption.CronJobs in it.localTools }
    private fun eligible(job: ScheduledJobEntity): Boolean = job.enabled && !job.deleted &&
        (job.maxRuns == null || job.totalRuns < job.maxRuns) && featureEnabled(job)

    private suspend fun reconcileQueued(job: ScheduledJobEntity?, queued: List<ScheduledJobRunEntity>) {
        val liveJob = job?.takeIf { eligible(it) }
        CronOutboxPlanner.reconcile(liveJob, queued,
            enqueue = { enqueuePersisted(it); Unit },
            discard = { run ->
                cancelOccurrenceTriggers(run)
                val (status, code) = when {
                    job == null || job.deleted || !job.enabled -> "cancelled" to "job_disabled"
                    job.revision != run.jobRevision -> "cancelled" to "stale_revision"
                    job.maxRuns != null && job.totalRuns >= job.maxRuns -> "blocked" to "run_limit"
                    liveJob == null -> "blocked" to "feature_disabled"
                    else -> "blocked" to "run_already_claimed"
                }
                runs.finishQueued(run.occurrenceId, status, code, currentTimeMillis())
            },
        )
    }

    private suspend fun enqueue(job: ScheduledJobEntity, at: Long, manual: Boolean): ScheduledJobRunEntity? {
        val run = runs.queue(job, at, manual) ?: return null
        return enqueuePersisted(run)
    }

    private suspend fun enqueuePersisted(occurrence: ScheduledJobRunEntity): ScheduledJobRunEntity? {
        // An existing WorkManager request can race recovery. Never enqueue a claimed/terminal row.
        val run = runs.getById(occurrence.occurrenceId)?.takeIf { it.status == "queued" } ?: return null
        val future = run.kind != "manual" && run.scheduledAtMs > currentTimeMillis()
        val exactArmed = future && alarms.schedule(ExactAlarmDomain.CRON_JOBS, run.occurrenceId,
            run.scheduledAtMs, run.jobRevision)
        try {
            if (future) enqueueFallback(run) else enqueueExecution(run)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // The committed queued row survives and the next rearm restores this exact occurrence ID.
            throw cancel
        } catch (_: Exception) {
            // A successfully armed alarm remains usable even if the backup enqueue failed.
            if (exactArmed) return runs.getById(run.occurrenceId)
            cancelOccurrenceTriggers(run)
            runs.finishQueued(run.occurrenceId, "failed", "enqueue_failed", currentTimeMillis())
            return null
        }
        return runs.getById(run.occurrenceId)
    }

    /** Receivers dispatch immutable queued IDs; the worker still owns the only durable claim. */
    suspend fun dispatchDueOccurrence(
        occurrenceId: String, expectedAt: Long? = null, expectedRevision: Long? = null,
    ): Boolean {
        awaitRecovery()
        return mutex.withLock {
            val run = runs.getById(occurrenceId)?.takeIf { it.status == "queued" } ?: return@withLock true
            if ((expectedAt != null && run.scheduledAtMs != expectedAt) ||
                (expectedRevision != null && run.jobRevision != expectedRevision)) return@withLock true
            val job = jobs.getById(run.jobId)
            if (job == null || !eligible(job) || !CronRunPolicy.mayClaim(job, run)) {
                reconcileQueued(job, listOf(run))
                return@withLock true
            }
            if (run.kind != "manual" && run.scheduledAtMs > currentTimeMillis()) {
                // TIME_SET can move the wall clock backwards after an alarm was delivered.
                alarms.schedule(ExactAlarmDomain.CRON_JOBS, run.occurrenceId, run.scheduledAtMs, run.jobRevision)
                return@withLock false
            }
            enqueueExecution(run)
            cancelOccurrenceTriggers(run)
            true
        }
    }

    /** Arm the following repeat before a potentially long LLM/tool run begins. */
    suspend fun onOccurrenceClaimed(run: ScheduledJobRunEntity) {
        cancelOccurrenceTriggers(run)
        if (run.kind != "manual") afterOccurrence(run.jobId, run.jobRevision)
    }

    private fun cancelOccurrenceTriggers(run: ScheduledJobRunEntity) {
        alarms.cancel(ExactAlarmDomain.CRON_JOBS, run.occurrenceId, run.scheduledAtMs, run.jobRevision)
        // This worker never claims effects, so cancelling it cannot cancel a claimed execution.
        manager.cancelUniqueWork(fallbackWorkName(run.occurrenceId))
    }

    private suspend fun enqueueExecution(run: ScheduledJobRunEntity) {
        val builder = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(OCCURRENCE_ID, run.occurrenceId).build())
            .addTag(jobTag(run.jobId))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val request = builder.build()
        runInterruptible(Dispatchers.IO) {
            manager.enqueueUniqueWork(workName(run.occurrenceId), ExistingWorkPolicy.KEEP, request)
                .result.get(15, TimeUnit.SECONDS)
        }
    }

    private suspend fun enqueueFallback(run: ScheduledJobRunEntity) {
        val request = OneTimeWorkRequestBuilder<CronJobFallbackWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(OCCURRENCE_ID, run.occurrenceId).build())
            .setInitialDelay((run.scheduledAtMs - currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(jobTag(run.jobId)).build()
        runInterruptible(Dispatchers.IO) {
            manager.enqueueUniqueWork(fallbackWorkName(run.occurrenceId), ExistingWorkPolicy.KEEP, request)
                .result.get(15, TimeUnit.SECONDS)
        }
    }

    companion object {
        const val OCCURRENCE_ID = "cron_occurrence_id"
        fun jobTag(id: String) = "cron_job:$id"
        // A new namespace bypasses delayed execution requests left by older releases, without
        // replacing/cancelling a potentially claimed old worker. Room still prevents double effects.
        fun workName(id: String) = "cron_execution:$id"
        fun fallbackWorkName(id: String) = "cron_fallback:$id"
        fun nextScheduled(job: ScheduledJobEntity, after: Long): Long? {
            if (!job.enabled || job.deleted || (job.maxRuns != null && job.totalRuns >= job.maxRuns)) return null
            val basis = maxOf(after, job.startAtMs?.let { it - 1 } ?: after, job.lastClaimedScheduledAtMs ?: after)
            val next = when (job.scheduleType) {
                "once" -> job.atUnixMs?.takeIf { it > basis }
                "cron" -> job.cronExpression?.let { expression ->
                    CronExpressionParser.parse(expression).getOrNull()?.let {
                        runCatching { ZoneId.of(job.timezone) }.getOrNull()?.let { zone ->
                            CronExpressionParser.nextExecution(it, basis, zone, job.createdAtMs)
                        }
                    }
                }
                else -> null
            }
            return next?.takeIf { job.endAtMs == null || it <= job.endAtMs }
        }
    }
}

/** One process recovery must finish before any new occurrence can be claimed. */
class CronRecoveryBarrier(private val recover: suspend () -> Unit) {
    private val mutex = Mutex()
    private var ready = false
    suspend fun awaitReady() = mutex.withLock {
        if (!ready) {
            recover()
            ready = true
        }
    }
}

/** The Room queue is a durable outbox; replay only unclaimed, still-live immutable occurrences. */
object CronOutboxPlanner {
    suspend fun reconcile(
        job: ScheduledJobEntity?, queued: List<ScheduledJobRunEntity>,
        enqueue: suspend (ScheduledJobRunEntity) -> Unit,
        discard: suspend (ScheduledJobRunEntity) -> Unit,
    ) {
        for (run in queued) {
            if (run.status != "queued") continue
            if (job != null && CronRunPolicy.mayClaim(job, run)) enqueue(run) else discard(run)
        }
    }
}
