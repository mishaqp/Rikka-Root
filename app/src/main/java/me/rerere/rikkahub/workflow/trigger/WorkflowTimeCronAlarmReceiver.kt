package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.service.scheduling.ExactAlarmScheduler
import java.util.concurrent.TimeUnit

/** Non-exported receiver carries identity only; all actions run through the guarded WM executor. */
class WorkflowTimeCronAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val workflowId = intent.getStringExtra(ExactAlarmScheduler.KEY) ?: return
        val atMillis = intent.getLongExtra(ExactAlarmScheduler.AT_MILLIS, 0L)
        val revision = intent.getLongExtra(ExactAlarmScheduler.REVISION, Long.MIN_VALUE)
        if (atMillis <= 0L || revision == Long.MIN_VALUE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AndroidWorkflowTimeCronScheduling.enqueueExecution(context.applicationContext, workflowId, atMillis, revision)
                    .result.get(8L, TimeUnit.SECONDS)
            } catch (error: Exception) {
                // A delayed dispatcher was installed with the alarm and remains the fallback.
                Log.w("WorkflowTrigger", "time_cron alarm enqueue failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }
}
