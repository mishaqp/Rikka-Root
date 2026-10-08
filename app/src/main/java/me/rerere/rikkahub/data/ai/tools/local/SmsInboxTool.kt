// Adapted from ExTV/rikkahub-agent, local/SmsInboxTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.Telephony
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private data class InboxArguments(val limit: Int, val since: Long?, val query: String?)
private fun smsInboxTool(context: Context, search: Boolean): Tool = personalJsonTool(context,
    if (search) "search_sms" else "list_sms_inbox", "Read SMS inbox, most recent first, limit 1-200 (default 20). since_ms filters by epoch timestamp; query searches for a literal substring.", LocalToolOption.SmsInbox,
    InputSchema.Obj(buildJsonObject {
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 200) })
        put("since_ms", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        if (search) put("query", buildJsonObject { put("type", "string"); put("maxLength", 256) })
    }, if (search) listOf("query") else null), validate = { InboxArguments(personalLimit(it,20,200), personalSince(it), if (search) personalQuery(it) else null) }, read = { args ->
        val filters = mutableListOf<String>(); val values = mutableListOf<String>()
        args.since?.let { filters += "date >= ?"; values += it.toString() }
        args.query?.let { filters += "body LIKE ? ESCAPE '\\'"; values += smsSubstringSelection(it) }
        val cursor = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body", "date", "read"),
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND "), values.takeIf { it.isNotEmpty() }?.toTypedArray(), "date DESC") ?: error("SMS provider unavailable")
        buildJsonObject { put("messages", buildJsonArray {
            cursor.use { c ->
                val columns = listOf("_id", "address", "body", "date", "read").map(c::getColumnIndexOrThrow)
                var count = 0
                while (count++ < args.limit && c.moveToNext()) addJsonObject {
                    val body = c.getString(columns[2]).orEmpty()
                    put("id", c.getLong(columns[0])); put("address", c.getString(columns[1]).orEmpty().take(256))
                    put("body", body.take(16384)); if (body.length > 16384) put("body_truncated", true)
                    put("date_ms", c.getLong(columns[3])); put("read", c.getInt(columns[4]) != 0)
                }
            }
        }) }
    })

fun listSmsInboxTool(context: Context): Tool = smsInboxTool(context, false)
fun searchSmsTool(context: Context): Tool = smsInboxTool(context, true)
