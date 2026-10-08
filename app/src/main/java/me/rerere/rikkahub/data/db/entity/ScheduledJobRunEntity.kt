package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_job_runs", indices = [Index(value = ["job_id"]), Index(value = ["job_id", "scheduled_at_ms"])])
data class ScheduledJobRunEntity(
    @PrimaryKey @ColumnInfo("occurrence_id") val occurrenceId: String,
    @ColumnInfo("job_id") val jobId: String,
    @ColumnInfo("job_revision") val jobRevision: Long,
    val kind: String,
    @ColumnInfo("scheduled_at_ms") val scheduledAtMs: Long,
    @ColumnInfo("manual_sequence") val manualSequence: Long = 0,
    val status: String = "queued",
    @ColumnInfo("claim_token") val claimToken: String? = null,
    @ColumnInfo("started_at_ms") val startedAtMs: Long? = null,
    @ColumnInfo("finished_at_ms") val finishedAtMs: Long? = null,
    @ColumnInfo("outcome_code") val outcomeCode: String? = null,
)

/** Shared by the transaction and JVM tests; statuses never carry tool responses or secrets. */
object CronRunPolicy {
    val terminal = setOf("succeeded", "failed", "blocked", "cancelled", "indeterminate")
    fun occurrenceId(jobId: String, revision: Long, scheduledAt: Long, manualSequence: Long = 0): String =
        java.util.UUID.nameUUIDFromBytes("$jobId/$revision/$scheduledAt/$manualSequence".toByteArray(Charsets.UTF_8)).toString()
    fun mayClaim(job: ScheduledJobEntity, run: ScheduledJobRunEntity): Boolean =
        job.enabled && !job.deleted && job.id == run.jobId && job.revision == run.jobRevision &&
            run.status == "queued" && (job.maxRuns == null || job.totalRuns < job.maxRuns) &&
            (if (run.kind == "manual") run.manualSequence > job.lastClaimedManualSequence
             else job.lastClaimedScheduledAtMs == null || run.scheduledAtMs > job.lastClaimedScheduledAtMs)
    fun invalidateSchedule(job: ScheduledJobEntity): ScheduledJobEntity = job.copy(revision = Math.addExact(job.revision, 1L), nextRunAtMs = null)
    enum class ClaimDecision { CLAIM, RETRY_BEFORE_CLAIM, BLOCKED, GONE }
    fun claimDecision(job: ScheduledJobEntity?, run: ScheduledJobRunEntity, queued: List<ScheduledJobRunEntity>, hasRunning: Boolean, now: Long): ClaimDecision {
        if (run.status != "queued") return ClaimDecision.GONE
        if (job == null || !mayClaim(job, run)) return ClaimDecision.BLOCKED
        if (hasRunning || (run.kind != "manual" && run.scheduledAtMs > now)) return ClaimDecision.RETRY_BEFORE_CLAIM
        val older = queued.any { candidate ->
            candidate.occurrenceId != run.occurrenceId && mayClaim(job, candidate) &&
                if (run.kind == "manual") candidate.kind == "manual" && candidate.manualSequence < run.manualSequence
                else candidate.kind != "manual" && candidate.scheduledAtMs < run.scheduledAtMs && candidate.scheduledAtMs <= now
        }
        return if (older) ClaimDecision.RETRY_BEFORE_CLAIM else ClaimDecision.CLAIM
    }
    fun isLive(job: ScheduledJobEntity?, run: ScheduledJobRunEntity?, expected: ScheduledJobEntity, token: String): Boolean =
        job != null && run != null && job.enabled && !job.deleted && job.id == expected.id &&
            job.ownerAssistantId == expected.ownerAssistantId && job.creatorConversationId == expected.creatorConversationId &&
            job.revision == expected.revision && run.jobId == expected.id && run.jobRevision == expected.revision &&
            run.status == "running" && run.claimToken == token
    private val safeCodes = setOf("approval_required", "feature_disabled", "job_disabled", "stale_revision", "run_limit", "android_permission_required", "tool_unavailable", "model_unavailable", "root_command_blocked", "web_content_blocked", "payload_unavailable", "execution_failed", "deadline_exceeded", "execution_cancelled", "process_interrupted", "enqueue_failed", "concurrent_run", "hard_budget_exhausted", "step_limit", "interactive_tool", "mandatory_confirmation", "token_budget_exceeded", "root_interaction_required", "run_no_longer_allowed", "command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate", "root_approval_required", "root_not_granted", "tool_result_indeterminate", "tool_failed", "tool_execution_failed", "execution_interrupted", "run_already_claimed", "unknown_owner", "unknown_or_ambiguous_model", "invalid_task", "invalid_limits", "invalid_actions", "tool_not_allowed", "no_response", "run_failed")
    fun safeOutcome(code: String?): String? = code?.let { if (it in safeCodes) it else "execution_failed" }
}
