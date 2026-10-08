package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class CronJobScheduler(
    context: Context,
    private val jobs: ScheduledJobRepository,
    private val runs: ScheduledJobRunRepository,
    private val settingsStore: SettingsStore,
) {
    private val manager by lazy { WorkManager.getInstance(context.applicationContext) }
    private val mutex = Mutex()
    private val recovery = CronRecoveryBarrier {
        settingsStore.settingsFlow.first { !it.init }
        runs.recoverInterrupted(System.currentTimeMillis())
    }

    suspend fun awaitRecovery() = recovery.awaitReady()

    suspend fun restoreAfterProcessStart() {
        awaitRecovery()
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
        val now = System.currentTimeMillis()
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
        if (!eligible(job)) { jobs.setNext(job.id, revision, null); return@withLock }
        val next = nextScheduled(job, System.currentTimeMillis())
        if (jobs.setNext(job.id, revision, next)) next?.let { enqueue(job, it, false) }
    }
    suspend fun triggerNow(jobId: String, owner: String): ScheduledJobRunEntity? = mutex.withLock {
        val job = jobs.getOwned(jobId, owner) ?: return@withLock null
        if (!eligible(job)) return@withLock null
        enqueue(job, System.currentTimeMillis(), true)
    }
    /** Metadata is invalidated before this call. One shared tag includes scheduled/manual/catchup. */
    suspend fun cancel(jobId: String) = mutex.withLock { cancelWorkAndLedger(jobId) }
    private suspend fun cancelWorkAndLedger(jobId: String) {
        try { cancelWork(jobId) }
        finally { withContext(NonCancellable) { runs.cancelForJob(jobId, System.currentTimeMillis()) } }
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
                val (status, code) = when {
                    job == null || job.deleted || !job.enabled -> "cancelled" to "job_disabled"
                    job.revision != run.jobRevision -> "cancelled" to "stale_revision"
                    job.maxRuns != null && job.totalRuns >= job.maxRuns -> "blocked" to "run_limit"
                    liveJob == null -> "blocked" to "feature_disabled"
                    else -> "blocked" to "run_already_claimed"
                }
                runs.finishQueued(run.occurrenceId, status, code, System.currentTimeMillis())
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
        val request = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(OCCURRENCE_ID, run.occurrenceId).build())
            .setInitialDelay(if (run.kind == "manual") 0 else (run.scheduledAtMs - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(jobTag(run.jobId)).build()
        try {
            runInterruptible(Dispatchers.IO) {
                manager.enqueueUniqueWork(workName(run.occurrenceId), ExistingWorkPolicy.KEEP, request).result.get(15, TimeUnit.SECONDS)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // The committed queued row survives and the next rearm restores this exact occurrence ID.
            throw cancel
        } catch (_: Exception) {
            runs.finishQueued(run.occurrenceId, "failed", "enqueue_failed", System.currentTimeMillis())
            return null
        }
        return runs.getById(run.occurrenceId)
    }

    companion object {
        const val OCCURRENCE_ID = "cron_occurrence_id"
        fun jobTag(id: String) = "cron_job:$id"
        fun workName(id: String) = "cron_occurrence:$id"
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
