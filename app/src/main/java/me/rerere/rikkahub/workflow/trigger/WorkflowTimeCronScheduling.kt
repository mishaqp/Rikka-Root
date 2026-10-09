package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import me.rerere.rikkahub.service.scheduling.AndroidExactAlarmScheduler
import me.rerere.rikkahub.service.scheduling.ExactAlarmDomain
import me.rerere.rikkahub.service.scheduling.ExactAlarmScheduler
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class WorkflowTimeCronOccurrence(
    val atMillis: Long,
    val revision: Long,
    val triggerFingerprint: String,
    val zoneId: String,
    val lastConsumedAtMillis: Long? = null,
)

/** Only time/identity metadata is persisted; tool arguments stay in the workflow repository. */
internal class WorkflowTimeCronOccurrenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("workflow_timecron_occurrences", Context.MODE_PRIVATE)

    fun ids(): Set<String> = synchronized(lock) {
        preferences.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()
    }

    fun get(id: String): WorkflowTimeCronOccurrence? = synchronized(lock) { read(id) }

    fun put(id: String, occurrence: WorkflowTimeCronOccurrence) = synchronized(lock) {
        write(id, occurrence)
    }

    /** Durable compare-and-set is the claim: the consumed occurrence can never match again. */
    fun advance(id: String, expected: WorkflowTimeCronOccurrence, next: WorkflowTimeCronOccurrence): Boolean =
        synchronized(lock) {
            if (read(id) != expected) return@synchronized false
            write(id, next)
            true
        }

    fun remove(id: String) = synchronized(lock) {
        if (!preferences.edit().remove(PREFIX + id).commit()) throw IOException("Не удалось сохранить отмену расписания сценария.")
    }

    private fun read(id: String): WorkflowTimeCronOccurrence? {
        val raw = preferences.getString(PREFIX + id, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            WorkflowTimeCronOccurrence(json.getLong("at"), json.getLong("revision"), json.getString("trigger"),
                json.getString("zone"), if (json.has("consumed") && !json.isNull("consumed")) json.getLong("consumed") else null)
        }.getOrNull()
    }

    private fun write(id: String, occurrence: WorkflowTimeCronOccurrence) {
        val raw = JSONObject().put("at", occurrence.atMillis).put("revision", occurrence.revision)
            .put("trigger", occurrence.triggerFingerprint).put("zone", occurrence.zoneId)
            .put("consumed", occurrence.lastConsumedAtMillis).toString()
        if (!preferences.edit().putString(PREFIX + id, raw).commit()) throw IOException("Не удалось сохранить следующий запуск сценария.")
    }

    companion object {
        private const val PREFIX = "occurrence:"
        private val lock = Any()
    }
}

internal interface WorkflowTimeCronScheduling {
    fun schedule(workflowId: String, occurrence: WorkflowTimeCronOccurrence)
    fun cancelTimer(workflowId: String, occurrence: WorkflowTimeCronOccurrence)
    fun cancelAll(workflowId: String)
}

/** WorkManager's delayed worker is a dispatcher, never the executor being replaced by an alarm. */
internal class AndroidWorkflowTimeCronScheduling(
    private val context: Context,
    private val alarms: ExactAlarmScheduler = AndroidExactAlarmScheduler(context),
) : WorkflowTimeCronScheduling {
    override fun schedule(workflowId: String, occurrence: WorkflowTimeCronOccurrence) {
        alarms.schedule(ExactAlarmDomain.WORKFLOW_TIME_CRON, workflowId, occurrence.atMillis, occurrence.revision)
        val fallback = OneTimeWorkRequestBuilder<WorkflowTimeCronFallbackWorker>()
            .setInitialDelay((occurrence.atMillis - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(occurrenceData(workflowId, occurrence.atMillis, occurrence.revision))
            .addTag(workflowTag(workflowId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            fallbackName(workflowId, occurrence.atMillis, occurrence.revision), ExistingWorkPolicy.KEEP, fallback,
        )
        // Cancel the periodic/one-shot WorkManager timers created by the previous app version.
        WorkManager.getInstance(context).cancelUniqueWork(TimeCronTriggerFamily.workName(workflowId))
    }

    override fun cancelTimer(workflowId: String, occurrence: WorkflowTimeCronOccurrence) {
        alarms.cancel(ExactAlarmDomain.WORKFLOW_TIME_CRON, workflowId, occurrence.atMillis, occurrence.revision)
        WorkManager.getInstance(context).cancelUniqueWork(fallbackName(workflowId, occurrence.atMillis, occurrence.revision))
    }

    override fun cancelAll(workflowId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workflowTag(workflowId))
        WorkManager.getInstance(context).cancelUniqueWork(TimeCronTriggerFamily.workName(workflowId))
    }

    companion object {
        fun enqueueExecution(context: Context, workflowId: String, atMillis: Long, revision: Long): Operation {
            val request = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
                .setInputData(occurrenceData(workflowId, atMillis, revision))
                .addTag(workflowTag(workflowId))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }.build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                executionName(workflowId, atMillis, revision), ExistingWorkPolicy.KEEP, request,
            )
        }

        private fun occurrenceData(workflowId: String, atMillis: Long, revision: Long) = workDataOf(
            TimeCronTriggerFamily.KEY_WORKFLOW_ID to workflowId,
            TimeCronTriggerFamily.KEY_OCCURRENCE_AT_MS to atMillis,
            TimeCronTriggerFamily.KEY_REVISION to revision,
        )

        fun fallbackName(workflowId: String, atMillis: Long, revision: Long) = "wf_timecron_fallback_${workflowId}_${revision}_$atMillis"
        fun executionName(workflowId: String, atMillis: Long, revision: Long) = "wf_timecron_execution_${workflowId}_${revision}_$atMillis"
        private fun workflowTag(workflowId: String) = "wf_timecron_occurrences_$workflowId"
    }
}
