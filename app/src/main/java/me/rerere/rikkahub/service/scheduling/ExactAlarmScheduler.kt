package me.rerere.rikkahub.service.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

enum class ExactAlarmDomain(internal val receiverClass: String) {
    CRON_JOBS("me.rerere.rikkahub.service.CronJobAlarmReceiver"),
    WORKFLOW_TIME_CRON("me.rerere.rikkahub.workflow.trigger.WorkflowTimeCronAlarmReceiver"),
}

/** The alarm carries only the durable occurrence identity, never prompts or tool arguments. */
interface ExactAlarmScheduler {
    fun schedule(domain: ExactAlarmDomain, key: String, atMillis: Long, revision: Long = 0L): Boolean
    fun cancel(domain: ExactAlarmDomain, key: String, atMillis: Long, revision: Long = 0L)

    companion object {
        const val KEY = "exact_alarm_key"
        const val AT_MILLIS = "exact_alarm_at_ms"
        const val REVISION = "exact_alarm_revision"
    }
}

/** Android 8–11 allow exact alarms without a separate grant. */
object ExactAlarmAccess {
    fun canSchedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } catch (_: SecurityException) {
            false
        }
    }
}

class AndroidExactAlarmScheduler(context: Context) : ExactAlarmScheduler {
    private val context = context.applicationContext

    override fun schedule(domain: ExactAlarmDomain, key: String, atMillis: Long, revision: Long): Boolean {
        if (!ExactAlarmAccess.canSchedule(context)) return false
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val operation = PendingIntent.getBroadcast(context, 0, alarmIntent(domain, key, atMillis, revision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            true
        } catch (_: SecurityException) {
            // The special access may be revoked after the capability check.
            // Callers keep a durable WorkManager fallback for this occurrence.
            operation.cancel()
            false
        }
    }

    override fun cancel(domain: ExactAlarmDomain, key: String, atMillis: Long, revision: Long) {
        val operation = PendingIntent.getBroadcast(context, 0, alarmIntent(domain, key, atMillis, revision),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(operation)
        operation.cancel()
    }

    private fun alarmIntent(domain: ExactAlarmDomain, key: String, atMillis: Long, revision: Long): Intent =
        Intent().apply {
            component = ComponentName(context, domain.receiverClass)
            action = "${context.packageName}.EXACT_SCHEDULED_OCCURRENCE"
            data = Uri.Builder().scheme("rikka-root-alarm").authority(domain.name.lowercase())
                .appendPath(key).appendPath(atMillis.toString()).appendPath(revision.toString()).build()
            putExtra(ExactAlarmScheduler.KEY, key)
            putExtra(ExactAlarmScheduler.AT_MILLIS, atMillis)
            putExtra(ExactAlarmScheduler.REVISION, revision)
        }
}
