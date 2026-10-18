package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = "Читает события календаря Android в интервале begin/end либо range=today/week/month (по умолчанию today). Для создания используйте calendar_create. Требуется ручное разрешение чтения календаря; инструмент не включает доступ сам. Часы без смещения используют текущий часовой пояс устройства ${ZoneId.systemDefault()} (UTC ${OffsetDateTime.now().offset}). При begin без end берётся один день от begin и range игнорируется. JSON возвращает range_start, range_end, count, events и truncated/has_more для дополнительных событий с названием, описанием, местом, временем и календарём. limit 1–100, по умолчанию 20; query ищет буквальную подстроку заголовка.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put("description", "Начало включительно: дата ISO-8601, локальные дата/время, время со смещением либо миллисекунды Unix строкой. При наличии range игнорируется.")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "Конец исключительно в формате begin; без end берётся один день от begin либо конец выбранного периода.")
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                            add("month")
                        }
                    )
                    put("description", "Период без begin: today — сегодня, week — неделя с понедельника, month — текущий месяц; по умолчанию today.")
                    put("default", "today")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательная буквальная подстрока заголовка события; %, _ и обратная косая черта экранируются.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Максимум событий 1–100, по умолчанию 20; значения вне диапазона ограничиваются его границами.")
                    put("minimum", 1)
                    put("maximum", 100)
                    put("default", 20)
                })
            }
        )
    },
    execute = { args -> deviceToolParts {
        if (!hasCalendarReadPermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Нет разрешения на чтение календаря. Включите доступ к календарю в настройках инструментов ассистента."
                )
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else if (beginRaw != null) {
                startTime.plusDays(1)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (_: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", "Укажите begin и end как дату ISO-8601, дату со временем или число миллисекунд Unix.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "Укажите begin раньше end.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
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
        )

        val selection = if (query != null) {
            "${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'"
        } else null
        val selectionArgs = if (query != null) {
            val literalQuery = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            arrayOf("%$literalQuery%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        var hasMore = false
        val events = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (count < limit && cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        val dtStart = cursor.getLong(4)
                        val dtEnd = cursor.getLong(5)
                        val allDay = cursor.getInt(6) == 1
                        if (allDay) {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                } else {
                                    ""
                                }
                            )
                        } else {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                } else {
                                    ""
                                }
                            )
                        }
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7) ?: "")
                    })
                    count++
                }
                hasMore = cursor.moveToNext()
            }
        }

        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("truncated", hasMore)
            put("has_more", hasMore)
            if (hasMore) put("continuation", "Показана только часть событий. Увеличьте limit до 100 или сузьте begin/end/query; автоматического курсора продолжения нет.")
            put("events", events)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    } }
)

internal fun buildCalendarCreateTool(context: Context): Tool = Tool(
    name = "calendar_create",
    description = "Создаёт событие в доступном для записи календаре Android после одобрения. Обязательны title и start; end по умолчанию через один час, all_day=false. Для чтения используйте calendar_query. Требуется ручное разрешение записи календаря; без календаря добавьте учётную запись в Android. Часы без смещения используют текущий пояс устройства ${ZoneId.systemDefault()} (UTC ${OffsetDateTime.now().offset}); событие на весь день сохраняется по датам UTC. JSON сообщает об успехе и ID созданного события; не повторяйте создание автоматически при неясном результате.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Обязательный непустой заголовок создаваемого события.")
                    put("minLength", 1)
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательное описание или заметки события; без параметра пустая строка.")
                    put("default", "")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательное место события; без параметра пустая строка.")
                    put("default", "")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put("description", "Обязательное начало: дата ISO-8601, локальные дата/время, время со смещением либо миллисекунды Unix строкой.")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "Конец исключительно в формате start; по умолчанию через один час после start. Для all_day явно укажите дату end позже даты start.")
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Создать событие на весь день по датам UTC; по умолчанию false.")
                    put("default", false)
                })
            },
            required = listOf("title", "start")
        )
    },
    execute = { args -> deviceToolParts {
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Нет разрешения на запись в календарь. Включите доступ к календарю в настройках инструментов ассистента."
                )
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val allDay = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "Укажите title — название события, и start — время начала.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseCalendarTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (_: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", "Укажите start и end как дату ISO-8601, дату со временем или число миллисекунд Unix.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "Укажите end позже start.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                val payload = buildJsonObject {
                    put("error", "INVALID_RANGE")
                    put("message", "Для события на весь день укажите дату end позже даты start.")
                }
                return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId(context)
        if (calendarId == null) {
            val payload = buildJsonObject {
                put("error", "NO_CALENDAR")
                put("message", "На устройстве нет доступного календаря для записи. Добавьте учётную запись календаря в настройках Android.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.DTEND, eventEndMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        if (uri == null) {
            val payload = buildJsonObject {
                put("error", "INSERT_FAILED")
                put("message", "Android не сохранил событие. Проверьте доступ к календарю и наличие календаря для записи.")
            }
            return@deviceToolParts listOf(UIMessagePart.Text(payload.toString()))
        }

        val eventId = ContentUris.parseId(uri)
        val payload = buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("title", title)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("all_day", allDay)
            put("location", location)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    } }
)

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun getDefaultCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val writableSelection =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
    val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
        writableArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        writableSelection,
        writableArgs,
        "${CalendarContract.Calendars.VISIBLE} DESC"
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
}

private fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Укажите дату ISO-8601, дату со временем или число миллисекунд Unix.")
}
