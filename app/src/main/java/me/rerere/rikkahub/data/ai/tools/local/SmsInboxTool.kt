// Adapted from ExTV/rikkahub-agent, local/SmsInboxTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.Telephony
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private data class InboxArguments(val limit: Int, val since: Long?, val query: String?)
private fun smsInboxTool(context: Context, search: Boolean): Tool = personalJsonTool(context,
    if (search) "search_sms" else "list_sms_inbox", if (search) "Ищет буквальную подстроку query (1–256 символов) во входящих SMS от новых к старым. limit 1–200, по умолчанию 20; since_ms задаёт включительную нижнюю границу Unix в миллисекундах. Для чтения без поиска используйте list_sms_inbox; отправка — send_sms. Не меняет сообщения. Результат JSON messages/count и truncated/has_more для дополнительных строк; адрес ограничен 256, body 16384 символами с address_truncated/body_truncated; полное сообщение в приложении SMS Android." else "Читает входящие SMS от новых к старым после разрешения Android. limit 1–200, по умолчанию 20; since_ms — включительная нижняя граница Unix в миллисекундах. Для поиска используйте search_sms, для отправки — send_sms. Не меняет прочитанность и не удаляет сообщения. Результат JSON messages/count и truncated/has_more для дополнительных строк; адрес до 256 с address_truncated, body до 16384 символов с body_truncated при усечении; полное сообщение в приложении SMS Android.", LocalToolOption.SmsInbox,
    InputSchema.Obj(buildJsonObject {
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 200); put("description", "Максимум возвращаемых записей: 1–200, по умолчанию 20. Для большого результата увеличьте limit в этих пределах или сузьте фильтр."); put("default", 20) })
        put("since_ms", buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "Включительная нижняя граница времени Unix в миллисекундах, неотрицательное целое; без параметра ограничения снизу нет.") })
        if (search) put("query", buildJsonObject { put("type", "string"); put("maxLength", 256); put("description", "Буквальная строка поиска, от 1 до 256 символов; %, _ и обратная косая черта не расширяют поиск SMS."); put("minLength", 1) })
    }, if (search) listOf("query") else null), validate = { InboxArguments(personalLimit(it,20,200), personalSince(it), if (search) personalQuery(it) else null) }, read = { args ->
        val filters = mutableListOf<String>(); val values = mutableListOf<String>()
        args.since?.let { filters += "date >= ?"; values += it.toString() }
        args.query?.let { filters += "body LIKE ? ESCAPE '\\'"; values += smsSubstringSelection(it) }
        val cursor = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body", "date", "read"),
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND "), values.takeIf { it.isNotEmpty() }?.toTypedArray(), "date DESC") ?: error("Хранилище SMS Android недоступно. Проверьте разрешение на чтение SMS и включите приложение сообщений.")
        var hasMore = false
        val messages = buildJsonArray {
            cursor.use { c ->
                val columns = listOf("_id", "address", "body", "date", "read").map(c::getColumnIndexOrThrow)
                var count = 0
                while (count < args.limit && c.moveToNext()) {
                    addJsonObject {
                        val body = c.getString(columns[2]).orEmpty()
                        val address = c.getString(columns[1]).orEmpty()
                        put("id", c.getLong(columns[0])); put("address", address.take(256))
                        if (address.length > 256) put("address_truncated", true)
                        if (address.length > 256 || body.length > 16384) put("truncation_note", "Адрес ограничен 256, текст 16384 символами; полное сообщение доступно в приложении SMS Android.")
                        put("body", body.take(16384)); if (body.length > 16384) put("body_truncated", true)
                        put("date_ms", c.getLong(columns[3])); put("read", c.getInt(columns[4]) != 0)
                    }
                    count++
                }
                hasMore = c.moveToNext()
            }
        }
        buildJsonObject {
            put("messages", messages); put("count", messages.size)
            put("truncated", hasMore); put("has_more", hasMore)
            if (hasMore) put("continuation", "Показана только часть SMS. Увеличьте limit до 200 или уточните since_ms/query через search_sms; автоматического курсора продолжения нет.")
        }
    })

fun listSmsInboxTool(context: Context): Tool = smsInboxTool(context, false)
fun searchSmsTool(context: Context): Tool = smsInboxTool(context, true)
