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
    description = "Открыть системное меню отправки текста или URL после одобрения. Пользователь выбирает приложение и подтверждает отправку; открытие меню не означает доставку. Нужен text или url, суммарно до 128000 символов.",
    needsApproval = { true },
    parameters = { InputSchema.Obj(buildJsonObject {
        listOf("text", "url", "subject").forEach { key -> put(key, buildJsonObject {
            put("type", "string"); put("maxLength", if (key == "subject") 512 else 128000)
            put("description", when (key) {
                "text" -> "Текст для отправки; обязателен text или url. Суммарная длина text и url с разделяющим переводом строки — до 128000 символов."
                "url" -> "Ссылка для отправки как текст. Укажите url или text; общий текст отправки — до 128000 символов."
                else -> "Необязательная тема отправки: до 512 символов."
            })
        }) }
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
