package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.service.scheduling.ExactAlarmScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Exact wake-up dispatches WorkManager; receivers never execute models or tools themselves. */
class CronJobAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: CronJobScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ExactAlarmScheduler.KEY) ?: return
        if (!intent.hasExtra(ExactAlarmScheduler.AT_MILLIS) || !intent.hasExtra(ExactAlarmScheduler.REVISION)) return
        val at = intent.getLongExtra(ExactAlarmScheduler.AT_MILLIS, -1L)
        val revision = intent.getLongExtra(ExactAlarmScheduler.REVISION, -1L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8000) { scheduler.dispatchDueOccurrence(id, at, revision) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The distinct delayed trigger remains the backup if dispatch fails.
            } finally {
                pending.finish()
            }
        }
    }
}
