package me.rerere.rikkahub.miniapps

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

// Behavioral reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_reminders.dart.
data class MiniAppReminderOccurrence(val id: Int, val appId: String, val title: String, val body: String, val hour: Int, val minute: Int, val weekday: Int?)

class MiniAppReminders(
    private val store: MiniAppStore,
    private val schedule: suspend (MiniAppReminderOccurrence) -> Unit,
    private val cancel: suspend (Int) -> Unit,
) {
    private val mutex = Mutex()
    internal suspend fun <T> serialized(block: suspend () -> T): T = store.withAppLifecycle {
        mutex.withLock { block() }
    }
    suspend fun list(appId: String): JsonObject = store.readReminders(appId)
    suspend fun set(appId: String, id: String, raw: JsonElement) = serialized {
        validateId(id)
        val reminder = normalize(raw, store.byId(appId)?.name)
        val all = store.readReminders(appId).toMutableMap()
        if (id !in all && all.size >= MAX_REMINDERS) throw MiniAppException("too_many_reminders", "An app may keep at most 20 reminders.")
        cancelPrevious(appId, id, all[id])
        all[id] = reminder
        store.writeReminders(appId, JsonObject(all))
        val time = reminder.getValue("time").jsonPrimitive.content.split(':')
        val days = (reminder["days"] as? JsonArray)?.map { it.jsonPrimitive.int }
        notificationIds(appId, id, reminder).forEachIndexed { index, notification ->
            schedule(MiniAppReminderOccurrence(notification, appId, reminder.getValue("title").jsonPrimitive.content,
                reminder.getValue("body").jsonPrimitive.content, time[0].toInt(), time[1].toInt(), days?.get(index)))
        }
    }
    suspend fun remove(appId: String, id: String) = serialized {
        val all = store.readReminders(appId).toMutableMap()
        val old = all.remove(id) ?: return@serialized
        cancelPrevious(appId, id, old)
        store.writeReminders(appId, JsonObject(all))
    }
    // Store.delete already owns the lifecycle lock when invoking this hook.
    suspend fun cancelAll(appId: String) = mutex.withLock {
        store.readReminders(appId).forEach { (id, value) -> cancelPrevious(appId, id, value) }
    }
    private suspend fun cancelPrevious(appId: String, id: String, value: JsonElement?) {
        if (value == null || value == JsonNull) return
        notificationIds(appId, id, value).forEach { cancel(it) }
    }
    companion object {
        const val MAX_REMINDERS = 20
        internal fun validateId(id: String) {
            if (!Regex("^[A-Za-z0-9_-]{1,40}$").matches(id)) invalid("Reminder ids are 1-40 letters, digits, \"-\" or \"_\".")
        }
        fun normalize(raw: JsonElement, fallbackTitle: String? = null): JsonObject {
            val obj = raw as? JsonObject ?: invalid("A reminder is an object with \"time\".")
            val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(obj["time"].wireText().trim())
            val hour = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val minute = match?.groupValues?.get(2)?.toIntOrNull() ?: -1
            if (hour !in 0..23 || minute !in 0..59) invalid("\"time\" must be \"HH:mm\".")
            var days: List<Int>? = null
            obj["days"]?.takeUnless { it == JsonNull }?.let { value ->
                val array = value as? JsonArray
                if (array == null || array.isEmpty() || array.any { it !is JsonPrimitive || it.isString || it.intOrNull !in 1..7 })
                    invalid("\"days\" must list weekdays from 1 (Monday) to 7 (Sunday).")
                days = array.map { it.jsonPrimitive.int }.distinct().sorted().takeUnless { it.size == 7 }
            }
            fun text(key: String, max: Int) = obj[key]?.takeUnless { it == JsonNull }?.wireText()?.trim()?.take(max) ?: ""
            return buildJsonObject {
                put("time", "%02d:%02d".format(java.util.Locale.ROOT, hour, minute))
                days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) }
                put("title", text("title", 80).ifEmpty { fallbackTitle ?: "Moru" })
                put("body", text("body", 300))
            }
        }
        fun notificationIds(appId: String, id: String, reminder: JsonElement): List<Int> {
            val days = ((reminder as? JsonObject)?.get("days") as? JsonArray)?.map { it.jsonPrimitive.int } ?: listOf(0)
            return days.map { day ->
                var hash = 0x811c9dc5L
                "$appId/$id/$day".toByteArray(Charsets.UTF_8).forEach { hash = ((hash xor (it.toLong() and 255)) * 0x01000193) and 0x3fffffff }
                (0x40000000 + hash).toInt()
            }
        }
        private fun invalid(message: String): Nothing = throw MiniAppException("invalid_reminder", message)
    }
}
