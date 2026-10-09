package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.service.scheduling.ScheduleRecoveryReason
import me.rerere.rikkahub.service.scheduling.recoverSchedules
import me.rerere.rikkahub.workflow.trigger.TriggerRegistry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Recovery restores alarms/fallback work; tool actions stay in the workers. */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: CronJobScheduler by inject()
    private val workflowTriggers: TriggerRegistry by inject()
    override fun onReceive(context: Context, intent: Intent) {
        val reason = ScheduleRecoveryReason.fromAction(intent.action) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8000) {
                recoverSchedules(reason,
                    restoreCron = { clockChanged ->
                        if (clockChanged) scheduler.onClockChanged() else scheduler.rearmAll()
                    },
                    restoreWorkflows = { clockChanged ->
                        // Resolve/bind the registry before the source boot dispatcher.
                        workflowTriggers.start()
                        workflowTriggers.restoreTimeCronAlarms(resetForClockChange = clockChanged)
                    },
                    dispatchBoot = { me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher.onBoot() },
                )
            } } catch (_: Exception) { /* Next process start also rearms. */ }
            finally { pending.finish() }
        }
    }
}
