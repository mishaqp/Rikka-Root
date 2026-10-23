package me.rerere.rikkahub.miniapps

// Adapted from Moru DeviceLocalToolsHandler.kt, revision
// 2101b5e457031d8adb1098dac880bdbb3019623f. Licensed under AGPL-3.0;
// see https://github.com/mishaqp/Moru/blob/2101b5e457031d8adb1098dac880bdbb3019623f/LICENSE.
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

class MiniAppCalendar(private val context: Context) {
    suspend fun execute(method: String, args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            (method != "createCalendarEvent" || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        val result = when {
            method !in setOf("queryCalendar", "createCalendarEvent") -> throw MiniAppException("unknown_method", "Неизвестный метод календаря: $method")
            !permission -> errorPayload("NO_PERMISSION", "Нет разрешения Android на доступ к календарю.")
            method == "queryCalendar" -> queryCalendar(JSONObject(args.toString()))
            else -> createCalendarEvent(JSONObject(args.toString()))
        }
        currentCoroutineContext().ensureActive()
        Json.parseToJsonElement(result).jsonObject
    }

    private fun errorPayload(code: String, message: String): String =
        JSONObject().put("error", code).put("message", message).toString()

    private inline fun <T> providerAttempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun queryCalendar(params: JSONObject): String {
        val limit = params.optString("limit").toIntOrNull()?.coerceIn(1, 100)
            ?: params.optInt("limit", 20).coerceIn(1, 100)
        val query = params.optString("query").takeIf { it.isNotBlank() }
        val calendarFilter = optLong(params, "calendar_id")

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params.optString("begin").takeIf { it.isNotBlank() }
        val endRaw = params.optString("end").takeIf { it.isNotBlank() }
        val rangePreset = params.optString("range").takeIf { it.isNotBlank() } ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (beginRaw != null) {
                // Custom interval: 'range' is ignored per the tool contract, and
                // the end defaults to now (matches iOS).
                now
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format for begin/end.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "begin must be earlier than end.")
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query != null) {
            // Escape LIKE wildcards so the keyword is matched literally as a
            // substring (e.g. searching "100%" must not act as a wildcard).
            clauses.add("${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'")
            val escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            args.add("%$escaped%")
        }
        if (calendarFilter != null) {
            clauses.add("${CalendarContract.Instances.CALENDAR_ID} = ?")
            args.add(calendarFilter.toString())
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = JSONArray()
        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val dtStart = cursor.getLong(4)
                val dtEnd = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1
                val obj = JSONObject()
                    .put("id", cursor.getLong(0))
                    .put("title", cursor.getString(1) ?: "")
                    .put("description", cursor.getString(2) ?: "")
                    .put("location", cursor.getString(3) ?: "")
                if (allDay) {
                    obj.put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    obj.put(
                        "end",
                        if (dtEnd > 0) Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString() else "",
                    )
                } else {
                    obj.put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                    obj.put(
                        "end",
                        if (dtEnd > 0) Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString() else "",
                    )
                }
                obj.put("all_day", allDay)
                obj.put("calendar", cursor.getString(7) ?: "")
                obj.put("calendar_id", cursor.getLong(8))
                events.put(obj)
                count++
            }
        }

        val payload = JSONObject()
            .put("range_start", startTime.withNano(0).toString())
            .put("range_end", endTime.withNano(0).toString())
            .put("count", events.length())
            .put("events", events)
        if (params.optBoolean("include_calendars", false)) {
            payload.put("calendars", listCalendars())
        }
        return payload.toString()
    }

    private fun listCalendars(): JSONArray {
        val calendars = JSONArray()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.VISIBLE,
            ),
            null,
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars.put(
                    JSONObject()
                        .put("id", cursor.getLong(0))
                        .put("name", cursor.getString(1) ?: "")
                        .put("account", cursor.getString(2) ?: "")
                        .put("writable", cursor.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        .put("primary", cursor.getInt(4) == 1)
                        .put("visible", cursor.getInt(5) == 1),
                )
            }
        }
        return calendars
    }

    /** A writable calendar with this id, or null. */
    private fun writableCalendarExists(calendarId: Long): Boolean {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null,
            null,
            null,
        )?.use { cursor ->
            return cursor.moveToFirst() &&
                cursor.getInt(0) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        }
        return false
    }

    /** Reads an integer id that the model may send as a number or a string. */
    private fun optLong(params: JSONObject, key: String): Long? {
        val raw = params.opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Calendar create
    // ---------------------------------------------------------------------

    private fun createCalendarEvent(params: JSONObject): String {
        val title = params.optString("title").takeIf { it.isNotBlank() }
        val startRaw = params.optString("start").takeIf { it.isNotBlank() }
        val endRaw = params.optString("end").takeIf { it.isNotBlank() }
        val allDay = params.optBoolean("all_day", false)

        if (title == null || startRaw == null) {
            return errorPayload("MISSING_REQUIRED", "Both 'title' and 'start' are required.")
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format.")
        }
        val times = eventTimes(startTime, endTime, allDay, zone)
        if (times is EventTimes.Invalid) return errorPayload("INVALID_RANGE", times.message)
        times as EventTimes.Valid

        val description = params.optString("description")
        val location = params.optString("location")
        val reminderMinutes = parseReminderMinutes(params.opt("reminders"))

        val requestedCalendar = optLong(params, "calendar_id")
        val calendarId = if (requestedCalendar != null) {
            if (!writableCalendarExists(requestedCalendar)) {
                return errorPayload(
                    "INVALID_CALENDAR",
                    "Calendar $requestedCalendar does not exist or is read-only. " +
                        "Call calendar_query with include_calendars=true to see writable calendars.",
                )
            }
            requestedCalendar
        } else {
            getDefaultCalendarId()
                ?: return errorPayload(
                    "NO_CALENDAR",
                    "No calendar account found on this device. Please add a calendar account first.",
                )
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, times.startMillis)
            put(CalendarContract.Events.DTEND, times.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, times.timeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return errorPayload("INSERT_FAILED", "Failed to insert calendar event.")

        val eventId = ContentUris.parseId(uri)
        val savedReminders = insertReminders(eventId, reminderMinutes)
        if (savedReminders.isNotEmpty()) {
            // 只有提醒真的写进去了才置 HAS_ALARM, 否则事件行会谎称有闹钟.
            setHasAlarm(eventId, true)
        }

        val payload = JSONObject()
            .put("success", true)
            .put("event_id", eventId)
            .put("calendar_id", calendarId)
            .put("title", title)
            .put("start", startTime.withNano(0).toString())
            .put("end", endTime.withNano(0).toString())
            .put("all_day", allDay)
            .put("location", location)
            .put("reminders", JSONArray(savedReminders))
        putReminderWarning(payload, reminderMinutes, savedReminders)
        return payload.toString()
    }

    private sealed class EventTimes {
        class Valid(val startMillis: Long, val endMillis: Long, val timeZone: String) : EventTimes()
        class Invalid(val message: String) : EventTimes()
    }

    /** Timed events keep the device zone; all-day events are UTC midnights. */
    private fun eventTimes(
        startTime: ZonedDateTime,
        endTime: ZonedDateTime,
        allDay: Boolean,
        zone: ZoneId,
    ): EventTimes {
        if (!startTime.isBefore(endTime)) {
            return EventTimes.Invalid("end must be later than start.")
        }
        if (!allDay) {
            return EventTimes.Valid(
                startTime.toInstant().toEpochMilli(),
                endTime.toInstant().toEpochMilli(),
                zone.id,
            )
        }
        val startDate = startTime.toLocalDate()
        val endDate = endTime.toLocalDate()
        if (!startDate.isBefore(endDate)) {
            return EventTimes.Invalid("all-day event end date must be later than start date.")
        }
        return EventTimes.Valid(
            startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            "UTC",
        )
    }

    private fun setHasAlarm(eventId: Long, hasAlarm: Boolean) {
        providerAttempt {
            context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, if (hasAlarm) 1 else 0) },
                null,
                null,
            )
        }
    }

    private fun putReminderWarning(payload: JSONObject, requested: List<Int>, saved: List<Int>) {
        if (saved.size >= requested.size) return
        // 事件已经保存了, 但部分/全部提醒被日历账户拒绝; 必须让模型看见,
        // 否则它会告诉用户提醒已设置.
        payload
            .put("reminders_requested", JSONArray(requested))
            .put(
                "warning",
                "The event was saved, but the calendar account rejected some reminders. " +
                    "Tell the user which reminders were actually saved.",
            )
    }

    /**
     * 提醒偏移(事件开始前多少分钟). 兼容数组、单个数字/字符串; 负值按绝对值处理,
     * 去重后最多保留 5 条.
     */
    private fun parseReminderMinutes(raw: Any?): List<Int> {
        if (raw == null || raw == JSONObject.NULL) return emptyList()
        val items: List<Any?> = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
            else -> listOf(raw)
        }
        val minutes = LinkedHashSet<Int>()
        for (item in items) {
            val value = when (item) {
                is Number -> item.toDouble()
                is String -> item.trim().toDoubleOrNull()
                else -> null
            } ?: continue
            if (value.isNaN() || value.isInfinite()) continue
            // 用 Double 中转: Math.abs(Int.MIN_VALUE) 仍是负数, 会被当成"事件开始之后"提醒.
            minutes.add(Math.abs(value).coerceAtMost(40320.0).toInt()) // 上限 4 周
            if (minutes.size == 5) break
        }
        return minutes.toList()
    }

    /** 写入提醒, 返回实际成功写入的偏移分钟数. */
    private fun insertReminders(eventId: Long, minutes: List<Int>): List<Int> {
        if (minutes.isEmpty()) return emptyList()
        val saved = mutableListOf<Int>()
        for (minute in minutes) {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            val inserted = providerAttempt {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            }
            if (inserted != null) saved.add(minute)
        }
        return saved
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val writableSelection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
            writableArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            writableSelection,
            writableArgs,
            "${CalendarContract.Calendars.VISIBLE} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Time parsing
    // ---------------------------------------------------------------------

    /**
     * 依次尝试: epoch 毫秒 -> 带偏移日期时间 -> Instant -> 本地日期时间 -> 本地日期(当天 0 点).
     */
    private fun parseTime(raw: String, zone: ZoneId): ZonedDateTime {
        val text = raw.trim()
        text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
        runCatching { return Instant.parse(text).atZone(zone) }
        runCatching { return LocalDateTime.parse(text).atZone(zone) }
        runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
        error("Invalid time format: '$text'. Use ISO-8601 date/date-time or epoch milliseconds.")
    }
}
