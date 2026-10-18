// Adapted from ExTV/rikkahub-agent, local/CallLogTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.CallLog
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private data class CallLogArguments(val limit: Int, val since: Long?, val type: Int?)
fun callLogTool(context: Context): Tool = personalJsonTool(context, "list_call_log", "Читает журнал звонков телефона от новых к старым после разрешения Android. limit 1–200, по умолчанию 20; type фильтрует incoming/outgoing/missed, since_ms задаёт включительную нижнюю границу Unix в миллисекундах. Для адресной книги используйте list_contacts; не совершает звонки. Результат JSON calls/count и truncated/has_more для дополнительных строк, с типом, датой и длительностью; номер до 256, имя до 4096 символов с *_truncated; полные данные доступны в журнале звонков Android.", LocalToolOption.CallLog,
    InputSchema.Obj(buildJsonObject {
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 200); put("description", "Максимум возвращаемых записей: 1–200, по умолчанию 20. Для большого результата увеличьте limit в этих пределах или сузьте фильтр."); put("default", 20) })
        put("since_ms", buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "Включительная нижняя граница времени Unix в миллисекундах, неотрицательное целое; без параметра ограничения снизу нет.") })
        put("type", buildJsonObject { put("type", "string"); put("description", "Необязательный тип: incoming — входящие, outgoing — исходящие, missed — пропущенные; без параметра все типы."); put("enum", buildJsonArray { add("incoming"); add("outgoing"); add("missed") }) })
    }), validate = { input ->
        val type = if (input.containsKey("type")) when (input.textArgument("type")) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE; "outgoing" -> CallLog.Calls.OUTGOING_TYPE; "missed" -> CallLog.Calls.MISSED_TYPE
            else -> throw IllegalArgumentException("type должен быть incoming, outgoing или missed.")
        } else null
        CallLogArguments(personalLimit(input,20,200), personalSince(input), type)
    }, read = { args ->
        val filters = mutableListOf<String>(); val values = mutableListOf<String>()
        args.type?.let { filters += "${CallLog.Calls.TYPE} = ?"; values += it.toString() }
        args.since?.let { filters += "${CallLog.Calls.DATE} >= ?"; values += it.toString() }
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", (args.limit + 1).toString()).build()
        val cursor = context.contentResolver.query(uri, arrayOf("_id", "number", "name", "type", "date", "duration"),
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND "), values.takeIf { it.isNotEmpty() }?.toTypedArray(), "${CallLog.Calls.DATE} DESC")
            ?: error("Журнал звонков Android недоступен. Проверьте разрешение на чтение журнала звонков в настройках функции.")
        var hasMore = false
        val calls = buildJsonArray {
            cursor.use { c ->
                val columns = listOf("_id", "number", "name", "type", "date", "duration").map(c::getColumnIndexOrThrow)
                var count = 0
                while (count < args.limit && c.moveToNext()) {
                    addJsonObject {
                        val number = c.getString(columns[1]).orEmpty()
                        val name = c.getString(columns[2]).orEmpty()
                        put("id", c.getLong(columns[0])); put("number", number.take(256))
                        put("name", name.take(4096))
                        if (number.length > 256) put("number_truncated", true)
                        if (name.length > 4096) put("name_truncated", true)
                        if (number.length > 256 || name.length > 4096) put("truncation_note", "Номер ограничен 256, имя 4096 символами; полные данные доступны в журнале звонков Android.")
                        put("type", when (c.getInt(columns[3])) { 1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"; 4 -> "voicemail"; 5 -> "rejected"; 6 -> "blocked"; else -> "unknown" })
                        put("date_ms", c.getLong(columns[4])); put("duration_s", c.getLong(columns[5]))
                    }
                    count++
                }
                hasMore = c.moveToNext()
            }
        }
        buildJsonObject {
            put("calls", calls); put("count", calls.size)
            put("truncated", hasMore); put("has_more", hasMore)
            if (hasMore) put("continuation", "Показана только часть звонков. Увеличьте limit до 200 или сузьте since_ms/type; автоматического курсора продолжения нет.")
        }
    })
