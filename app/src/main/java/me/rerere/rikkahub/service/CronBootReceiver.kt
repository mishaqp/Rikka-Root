package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.workflow.trigger.TriggerRegistry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Recovery schedules WorkManager work only; it never executes an action in a receiver. */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: CronJobScheduler by inject()
    private val workflowTriggers: TriggerRegistry by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8000) {
                try { scheduler.rearmAll() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Workflow recovery is independent of cron recovery. */ }
                // Resolve/bind the dedicated workflow registry before the source boot dispatcher.
                workflowTriggers.start()
                me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher.onBoot()
            } } catch (_: Exception) { /* Next process start also rearms. */ }
            finally { pending.finish() }
        }
    }
}
