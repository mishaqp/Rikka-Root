// Adapted from ExTV/rikkahub-agent, local/ToastTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun toastTool(context: Context): Tool = Tool(
    name = "show_toast",
    description = "Показать короткое всплывающее сообщение Android после одобрения. Используйте для краткого отклика до 512 символов; это не постоянное уведомление.",
    needsApproval = { true },
    parameters = { InputSchema.Obj(buildJsonObject {
        put("text", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 512); put("description", "Непустой текст всплывающего сообщения: 1–512 символов.") })
        put("long", buildJsonObject { put("type", "boolean"); put("default", false); put("description", "Показывать сообщение дольше стандартного короткого времени; по умолчанию false.") })
    }, required = listOf("text")) },
    execute = { input -> deviceToolResult {
        val params = input as? JsonObject ?: return@deviceToolResult deviceToolError("Ожидался объект параметров.")
        val text = params.textArgument("text")?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return@deviceToolResult deviceToolError("Нужен непустой text длиной до 512 символов.")
        val long = (params["long"] as? JsonPrimitive)?.booleanOrNull ?: false
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
        buildJsonObject { put("success", true) }
    } },
)
