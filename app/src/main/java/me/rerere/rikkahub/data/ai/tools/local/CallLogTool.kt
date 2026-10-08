// Adapted from ExTV/rikkahub-agent, local/CallLogTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.CallLog
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private data class CallLogArguments(val limit: Int, val since: Long?, val type: Int?)
fun callLogTool(context: Context): Tool = personalJsonTool(context, "list_call_log", "List recent calls, most recent first. Optional type: incoming, outgoing, missed; since_ms epoch lower bound; limit 1-200 (default 20).", LocalToolOption.CallLog,
    InputSchema.Obj(buildJsonObject {
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 200) })
        put("since_ms", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("type", buildJsonObject { put("type", "string"); put("description", "incoming, outgoing, or missed") })
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
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", args.limit.toString()).build()
        val cursor = context.contentResolver.query(uri, arrayOf("_id", "number", "name", "type", "date", "duration"),
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND "), values.takeIf { it.isNotEmpty() }?.toTypedArray(), "${CallLog.Calls.DATE} DESC")
            ?: error("Call log provider unavailable")
        buildJsonObject { put("calls", buildJsonArray {
            cursor.use { c ->
                val columns = listOf("_id", "number", "name", "type", "date", "duration").map(c::getColumnIndexOrThrow)
                var count = 0
                while (count++ < args.limit && c.moveToNext()) addJsonObject {
                    put("id", c.getLong(columns[0])); put("number", c.getString(columns[1]).orEmpty().take(256))
                    put("name", c.getString(columns[2]).orEmpty().take(4096))
                    put("type", when (c.getInt(columns[3])) { 1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"; 4 -> "voicemail"; 5 -> "rejected"; 6 -> "blocked"; else -> "unknown" })
                    put("date_ms", c.getLong(columns[4])); put("duration_s", c.getLong(columns[5]))
                }
            }
        }) }
    })
