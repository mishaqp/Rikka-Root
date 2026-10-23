package me.rerere.rikkahub.miniapps

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.scheduling.ExactAlarmAccess
import org.koin.core.context.GlobalContext

/** Native scheduler for Moru-compatible durable reminder definitions. No prompts in receivers. */
class MiniAppAndroidReminders(
    context: Context,
    private val store: MiniAppStore,
    private val alarms: MiniAppAlarmBackend = AndroidMiniAppAlarmBackend(context),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val context = context.applicationContext
    val reminders = MiniAppReminders(store, schedule = ::schedule, cancel = ::cancel)

    init { store.addDeleteHook(reminders::cancelAll) }

    suspend fun restore(): Unit = reminders.serialized {
        store.load()
        for (app in store.apps.value) {
            for (occurrence in occurrences(app.id)) schedule(occurrence)
        }
    }

    private suspend fun occurrences(appId: String): List<MiniAppReminderOccurrence> = buildList {
        for ((key, raw) in store.readReminders(appId)) {
            val reminder = MiniAppReminders.normalize(raw, store.byId(appId)?.name)
            val time = reminder.getValue("time").jsonPrimitive.content.split(':')
            val days = (reminder["days"] as? JsonArray)?.map { it.jsonPrimitive.int }
            MiniAppReminders.notificationIds(appId, key, reminder).forEachIndexed { i, id ->
                add(MiniAppReminderOccurrence(id, appId, reminder.getValue("title").jsonPrimitive.content,
                    reminder.getValue("body").jsonPrimitive.content, time[0].toInt(), time[1].toInt(), days?.get(i)))
            }
        }
    }

    private suspend fun schedule(occurrence: MiniAppReminderOccurrence) {
        val at = nextMiniAppReminder(occurrence.hour, occurrence.minute, occurrence.weekday, clock()).toInstant().toEpochMilli()
        alarms.schedule(occurrence, at)
    }

    private suspend fun cancel(id: Int) { alarms.cancel(id) }

    /** Read current durable definition; an alarm from a deleted/changed app cannot notify. */
    suspend fun fire(appId: String, id: Int, atMillis: Long): Unit = reminders.serialized {
        store.load()
        if (store.byId(appId) == null) { cancel(id); return@serialized }
        val occurrence = occurrences(appId).firstOrNull { it.id == id } ?: run { cancel(id); return@serialized }
        val at = Instant.ofEpochMilli(atMillis).atZone(clock().zone)
        val expected = at.toLocalDate().atTime(occurrence.hour, occurrence.minute).atZone(clock().zone)
        val matches = at.toInstant() == expected.toInstant() &&
            (occurrence.weekday == null || occurrence.weekday == at.dayOfWeek.value)
        // Rearm before posting so denied notifications never break the next occurrence.
        schedule(occurrence)
        if (matches && !clock().toInstant().isBefore(at.toInstant())) {
            try { showMiniAppNotification(context, appId, id, occurrence.title, occurrence.body) }
            catch (e: MiniAppException) { Log.w(TAG, "Reminder notification unavailable: ${e.code}") }
        }
    }

    companion object {
        const val ACTION_REMINDER = "me.rerere.rikkahub.MINI_APP_REMINDER"
        const val EXTRA_APP_ID = "mini_app_id"
        const val EXTRA_ID = "mini_app_notification_id"
        const val EXTRA_AT = "mini_app_at"
        private const val TAG = "MiniAppReminders"
    }
}

/** Platform boundary: the definitions and recovery/delivery coordination remain in the service. */
interface MiniAppAlarmBackend {
    suspend fun schedule(occurrence: MiniAppReminderOccurrence, atMillis: Long)
    suspend fun cancel(id: Int)
}

private class AndroidMiniAppAlarmBackend(context: Context) : MiniAppAlarmBackend {
    private val context = context.applicationContext
    private fun alarmIntent(id: Int) = Intent(context, MiniAppReminderReceiver::class.java).apply {
        action = MiniAppAndroidReminders.ACTION_REMINDER
        data = Uri.Builder().scheme("rikka-miniapp-alarm").authority("reminder").appendPath(id.toString()).build()
    }

    override suspend fun schedule(occurrence: MiniAppReminderOccurrence, atMillis: Long) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: throw MiniAppException("unavailable", "Служба напоминаний Android недоступна.")
        val at = atMillis
        val intent = alarmIntent(occurrence.id).apply {
            putExtra(MiniAppAndroidReminders.EXTRA_APP_ID, occurrence.appId); putExtra(MiniAppAndroidReminders.EXTRA_ID, occurrence.id); putExtra(MiniAppAndroidReminders.EXTRA_AT, at)
        }
        val operation = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (ExactAlarmAccess.canSchedule(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                return@withContext
            } catch (_: SecurityException) { /* Grant revoked: retain the inexact fallback. */ }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    override suspend fun cancel(id: Int) = withContext(Dispatchers.IO) {
        PendingIntent.getBroadcast(context, 0, alarmIntent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it)
            it.cancel()
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)
    }

}

internal fun nextMiniAppReminder(hour: Int, minute: Int, weekday: Int?, now: ZonedDateTime): ZonedDateTime {
    require(hour in 0..23 && minute in 0..59 && (weekday == null || weekday in 1..7))
    var date = now.toLocalDate()
    if (weekday != null) date = date.plusDays(((weekday - date.dayOfWeek.value + 7) % 7).toLong())
    var at = date.atTime(hour, minute).atZone(now.zone)
    if (!at.isAfter(now)) {
        date = date.plusDays(if (weekday == null) 1 else 7)
        at = date.atTime(hour, minute).atZone(now.zone)
    }
    return at
}

private const val CHANNEL_ID = "mini_apps"
private const val NOTIFICATION_TAG = "mini_apps"

internal fun showMiniAppNotification(context: Context, appId: String, id: Int, title: String, body: String) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        throw MiniAppException("permission_denied", "Разрешите уведомления для мини-приложений.")
    }
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) throw MiniAppException("permission_denied", "Уведомления Rikka-Root отключены в Android.")
    manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
        .setName(context.getString(R.string.mini_apps_notifications)).build())
    if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
        throw MiniAppException("permission_denied", "Канал уведомлений мини-приложений отключён в Android.")
    }
    val open = PendingIntent.getActivity(context, 0, MiniAppLauncher.intent(context, appId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(NOTIFICATION_TAG, id, NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle(title).setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build())
}

/** Both durable alarm delivery and reboot/clock recovery work without an open Activity. */
class MiniAppReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recover = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!recover && intent.action != MiniAppAndroidReminders.ACTION_REMINDER) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val scheduler = GlobalContext.get().get<MiniAppAndroidReminders>()
                    if (recover) scheduler.restore()
                    else {
                        val appId = intent.getStringExtra(MiniAppAndroidReminders.EXTRA_APP_ID) ?: return@withTimeout
                        val id = intent.getIntExtra(MiniAppAndroidReminders.EXTRA_ID, -1)
                        val at = intent.getLongExtra(MiniAppAndroidReminders.EXTRA_AT, -1)
                        if (id >= 0 && at > 0) scheduler.fire(appId, id, at)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e("MiniAppReminders", "Не удалось восстановить напоминания", e) }
            finally { pending.finish() }
        }
    }
}
