// Adapted from ExTV/rikkahub-agent, local/ShareTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun shareTool(context: Context): Tool = Tool(
    name = "share",
    description = "Open the system chooser to share text or a URL. Requires approval. The user selects the receiving app and confirms sending; opening the chooser does not mean delivery.",
    needsApproval = { true },
    parameters = { InputSchema.Obj(buildJsonObject {
        listOf("text", "url", "subject").forEach { put(it, buildJsonObject { put("type", "string") }) }
    }) },
    execute = { input -> deviceToolResult {
        val params = input as? JsonObject ?: return@deviceToolResult deviceToolError("Ожидался объект параметров.")
        val combined = listOfNotNull(params.textArgument("text"), params.textArgument("url"))
            .filter { it.isNotBlank() }.joinToString("\n")
        val subject = params.textArgument("subject")
        if (combined.isBlank() || combined.length > 128_000 || (subject?.length ?: 0) > 512) {
            return@deviceToolResult deviceToolError("Укажите text или url до 128000 символов; subject — до 512 символов.")
        }
        withContext(Dispatchers.Main.immediate) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, combined)
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            context.startActivity(Intent.createChooser(send, "Отправить через").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        buildJsonObject { put("success", true); put("status", "chooser_opened") }
    } },
)
