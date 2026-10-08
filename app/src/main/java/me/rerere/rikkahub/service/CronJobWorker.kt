package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/** No retry after claim: provider/root cancellation stays inside this worker's coroutine. */
class CronJobWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters), KoinComponent {
    private val jobs: ScheduledJobRepository by inject()
    private val runs: ScheduledJobRunRepository by inject()
    private val scheduler: CronJobScheduler by inject()
    private val settingsStore: SettingsStore by inject()
    private val runner: HeadlessTaskRunner by inject()

    override suspend fun doWork(): Result {
        val id = inputData.getString(CronJobScheduler.OCCURRENCE_ID) ?: return Result.failure()
        try { withTimeout(5000) { scheduler.awaitRecovery() } }
        catch (_: TimeoutCancellationException) { return Result.retry() } // No occurrence has been claimed.
        val queued = runs.getById(id) ?: return Result.success()
        if (queued.status != "queued") return Result.success()
        val job = jobs.getById(queued.jobId)
        if (job == null || !isEnabled(job)) {
            runs.finishQueued(id, "blocked", "feature_disabled", System.currentTimeMillis())
            advanceUnclaimed(queued.jobId, queued.jobRevision, queued.kind)
            return Result.success()
        }
        val attempt = runs.claim(id, System.currentTimeMillis())
        val claim = when (attempt) {
            is ScheduledJobRunRepository.ClaimAttempt.Claimed -> attempt.claim
            ScheduledJobRunRepository.ClaimAttempt.RetryBeforeClaim -> return Result.retry()
            ScheduledJobRunRepository.ClaimAttempt.Gone -> return Result.success()
            ScheduledJobRunRepository.ClaimAttempt.Blocked -> {
                val code = when {
                    job.revision != queued.jobRevision -> "stale_revision"
                    job.maxRuns != null && job.totalRuns >= job.maxRuns -> "run_limit"
                    else -> "job_disabled"
                }
                runs.finishQueued(id, "blocked", code, System.currentTimeMillis())
                advanceUnclaimed(queued.jobId, queued.jobRevision, queued.kind)
                return Result.success()
            }
        }
        var outcome = CronExecutionOutcome("indeterminate", "process_interrupted")
        try {
            val payload = try { jobs.payload(claim.job) } catch (_: Exception) {
                outcome = CronExecutionOutcome("blocked", "payload_unavailable")
                return Result.success()
            }
            val fresh = jobs.getById(claim.job.id)
            if (fresh == null || fresh.revision != claim.job.revision || !isEnabled(fresh)) {
                outcome = CronExecutionOutcome("blocked", "feature_disabled")
                return Result.success()
            }
            if (claim.run.kind != "manual" && ((fresh.startAtMs != null && claim.run.scheduledAtMs < fresh.startAtMs) || (fresh.endAtMs != null && claim.run.scheduledAtMs > fresh.endAtMs))) {
                outcome = CronExecutionOutcome("blocked", "job_disabled")
                return Result.success()
            }
            val context = RunExecutionContext(
                runId = Uuid.parse(id), ownerAssistantId = Uuid.parse(fresh.ownerAssistantId), origin = RunOrigin.CRON,
                callerConversationId = Uuid.parse(fresh.creatorConversationId), callerConversationConfig = payload.conversationConfig,
                workspaceCwd = payload.workspaceCwd, allowedTools = payload.allowedToolNames, maxSteps = 12, timeoutMillis = WORKER_DEADLINE_MS,
                runStillAllowed = { runs.isLive(claim) },
            )
            val result = withTimeout(WORKER_DEADLINE_MS) {
                when (fresh.mode) {
                    "llm" -> runner.run(HeadlessTaskRequest.Prompt(payload.prompt ?: error("Missing prompt")), context)
                    "direct" -> DirectModeActionRunner(runner).run(payload.actions, context)
                    else -> HeadlessTaskResult(HeadlessTaskStatus.BLOCKED, errorCode = "execution_failed")
                }
            }
            outcome = CronExecutionOutcome.fromStatus(result.status.name, result.errorCode)
        } catch (_: TimeoutCancellationException) {
            outcome = CronExecutionOutcome("indeterminate", "deadline_exceeded")
        } catch (cancel: CancellationException) {
            outcome = CronExecutionOutcome("indeterminate", "execution_cancelled")
            throw cancel
        } catch (_: Exception) {
            outcome = CronExecutionOutcome("indeterminate", "execution_failed")
        } finally {
            withContext(NonCancellable) {
                runs.finish(id, claim.token, outcome.status, outcome.code, System.currentTimeMillis())
                // Only regular/catchup work advances; manual triggering preserves the existing cadence.
                if (claim.run.kind != "manual") scheduler.afterOccurrence(claim.job.id, claim.job.revision)
            }
        }
        return Result.success()
    }
    private suspend fun advanceUnclaimed(jobId: String, revision: Long, kind: String) {
        if (kind != "manual") withContext(NonCancellable) { scheduler.afterOccurrence(jobId, revision) }
    }
    private fun isEnabled(job: ScheduledJobEntity): Boolean = job.enabled && !job.deleted &&
        settingsStore.settingsFlow.value.assistants.any { it.id.toString() == job.ownerAssistantId && LocalToolOption.CronJobs in it.localTools }
    companion object { const val WORKER_DEADLINE_MS = 450000L }
}
