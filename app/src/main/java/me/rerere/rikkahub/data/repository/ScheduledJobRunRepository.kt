package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.CronRunPolicy
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import java.util.UUID

class ScheduledJobRunRepository(private val db: AppDatabase) {
    private val runs get() = db.scheduledJobRunDao()
    private val jobs get() = db.scheduledJobDao()
    data class Claim(val job: ScheduledJobEntity, val run: ScheduledJobRunEntity, val token: String)
    sealed interface ClaimAttempt {
        data class Claimed(val claim: Claim) : ClaimAttempt
        data object RetryBeforeClaim : ClaimAttempt
        data object Blocked : ClaimAttempt
        data object Gone : ClaimAttempt
    }

    suspend fun queue(job: ScheduledJobEntity, scheduledAt: Long, manual: Boolean = false): ScheduledJobRunEntity? = db.withTransaction {
        val fresh = jobs.getById(job.id) ?: return@withTransaction null
        if (!fresh.enabled || fresh.deleted || fresh.revision != job.revision || (fresh.maxRuns != null && fresh.totalRuns >= fresh.maxRuns)) return@withTransaction null
        if (runs.pendingCount(fresh.id) >= 64) return@withTransaction null
        if (!manual && fresh.lastClaimedScheduledAtMs != null && scheduledAt <= fresh.lastClaimedScheduledAtMs) return@withTransaction null
        val sequence = if (manual) {
            if (fresh.manualSequence == Long.MAX_VALUE || jobs.allocateManual(fresh.id, fresh.revision) == 0) return@withTransaction null
            jobs.getById(fresh.id)!!.manualSequence
        } else 0L
        val run = ScheduledJobRunEntity(CronRunPolicy.occurrenceId(fresh.id, fresh.revision, scheduledAt, sequence), fresh.id, fresh.revision, if (manual) "manual" else "scheduled", scheduledAt, sequence)
        runs.insert(run)
        runs.getById(run.occurrenceId)?.takeIf { it.status == "queued" }
    }

    suspend fun claim(id: String, now: Long): ClaimAttempt = db.withTransaction {
        val run = runs.getById(id) ?: return@withTransaction ClaimAttempt.Gone
        val job = jobs.getById(run.jobId)
        val decision = CronRunPolicy.claimDecision(job, run, runs.queuedForJob(run.jobId), runs.hasRunning(run.jobId), now)
        when (decision) {
            CronRunPolicy.ClaimDecision.RETRY_BEFORE_CLAIM -> return@withTransaction ClaimAttempt.RetryBeforeClaim
            CronRunPolicy.ClaimDecision.BLOCKED -> return@withTransaction ClaimAttempt.Blocked
            CronRunPolicy.ClaimDecision.GONE -> return@withTransaction ClaimAttempt.Gone
            CronRunPolicy.ClaimDecision.CLAIM -> Unit
        }
        checkNotNull(job)
        val token = UUID.randomUUID().toString()
        if (runs.claim(id, token, now) != 1) return@withTransaction ClaimAttempt.Gone
        check(jobs.recordClaim(job.id, job.revision, run.kind == "manual", run.scheduledAtMs, run.manualSequence) == 1)
        ClaimAttempt.Claimed(Claim(job, run.copy(status = "running", claimToken = token, startedAtMs = now), token))
    }

    suspend fun isLive(claim: Claim): Boolean = db.withTransaction {
        CronRunPolicy.isLive(jobs.getById(claim.job.id), runs.getById(claim.run.occurrenceId), claim.job, claim.token)
    }

    suspend fun queued() = runs.queued()
    suspend fun queuedForJob(jobId: String) = runs.queuedForJob(jobId)
    suspend fun getById(id: String) = runs.getById(id)
    suspend fun recent(jobId: String, limit: Int) = runs.recent(jobId, limit.coerceIn(1, 100))
    suspend fun finish(id: String, token: String, status: String, code: String?, now: Long) = db.withTransaction {
        require(status in CronRunPolicy.terminal)
        val run = runs.getById(id) ?: return@withTransaction false
        if (runs.finish(id, token, status, CronRunPolicy.safeOutcome(code), now) != 1) return@withTransaction false
        jobs.recordOutcome(run.jobId, status)
        runs.trim(run.jobId, 100)
        true
    }
    suspend fun finishQueued(id: String, status: String, code: String?, now: Long) = db.withTransaction {
        require(status in CronRunPolicy.terminal)
        val run = runs.getById(id) ?: return@withTransaction
        if (runs.finishQueued(id, status, CronRunPolicy.safeOutcome(code), now) == 1) {
            jobs.recordOutcome(run.jobId, status)
            runs.trim(run.jobId, 100)
        }
    }
    suspend fun cancelForJob(jobId: String, now: Long) = db.withTransaction {
        val cancelledQueued = runs.cancelQueued(jobId, now)
        repeat(cancelledQueued) { jobs.recordOutcome(jobId, "cancelled") }
        for (run in runs.runningForJob(jobId)) run.claimToken?.let { finish(run.occurrenceId, it, "indeterminate", "execution_cancelled", now) }
        runs.trim(jobId, 100)
    }
    /** Call exactly once before new-process recovery, never from the boot receiver. */
    suspend fun recoverInterrupted(now: Long) {
        for (run in runs.running()) run.claimToken?.let { finish(run.occurrenceId, it, "indeterminate", "process_interrupted", now) }
    }
    suspend fun deleteForJob(jobId: String) = runs.deleteForJob(jobId)
}
