package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.rerere.rikkahub.reliability.SecretRedactor
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/** The canonical WorkManager executor awaits the engine after its durable occurrence claim. */
class WorkflowTimeCronWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val registry: TriggerRegistry by inject()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(TimeCronTriggerFamily.KEY_WORKFLOW_ID) ?: return Result.failure()
        val atMillis = inputData.getLong(TimeCronTriggerFamily.KEY_OCCURRENCE_AT_MS, 0L)
        val revision = inputData.getLong(TimeCronTriggerFamily.KEY_REVISION, Long.MIN_VALUE)
        // Migration from the former periodic workers: rearm without executing an
        // unidentifiable old occurrence (the new alarm/fallback token owns execution).
        if (atMillis <= 0L || revision == Long.MIN_VALUE) {
            registry.restoreTimeCronAlarms()
            return Result.success()
        }
        return try {
            registry.fireFromTimeCronWorker(workflowId, atMillis, revision)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("WorkflowTrigger", "time_cron execution failed (${error.javaClass.simpleName}): ${SecretRedactor.redact(error.message.orEmpty())}")
            Result.failure()
        }
    }
}

/** Delayed fallback only dispatches the same KEEP executor used by the exact alarm. */
class WorkflowTimeCronFallbackWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(TimeCronTriggerFamily.KEY_WORKFLOW_ID) ?: return Result.failure()
        val atMillis = inputData.getLong(TimeCronTriggerFamily.KEY_OCCURRENCE_AT_MS, 0L)
        val revision = inputData.getLong(TimeCronTriggerFamily.KEY_REVISION, Long.MIN_VALUE)
        if (atMillis <= 0L || revision == Long.MIN_VALUE) return Result.failure()
        return try {
            runInterruptible(Dispatchers.IO) {
                AndroidWorkflowTimeCronScheduling.enqueueExecution(applicationContext, workflowId, atMillis, revision)
                    .result.get(8L, TimeUnit.SECONDS)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Cold-process workers read the live enabled row; JSON's stale enabled value is ignored. */
internal object TimeCronWorkerHelper {
    suspend fun repositoryLookup(workflowId: String): WorkflowRepository.Loaded? = runCatching {
        KoinJavaComponent.getKoin().get<WorkflowRepository>().getById(workflowId)
    }.getOrNull()
}
