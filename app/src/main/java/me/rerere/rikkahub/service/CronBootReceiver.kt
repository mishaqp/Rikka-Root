package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Recovery schedules WorkManager work only; it never executes an action in a receiver. */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: CronJobScheduler by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8000) { scheduler.rearmAll() } } catch (_: Exception) { /* Next process start also rearms. */ }
            finally { pending.finish() }
        }
    }
}
