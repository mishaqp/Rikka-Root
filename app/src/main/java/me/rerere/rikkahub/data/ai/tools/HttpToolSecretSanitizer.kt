package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale

/** Display/persistence copies only. Live execution arguments never pass through this mask. */
internal object HttpToolSecretSanitizer {
    const val MASK = "[скрыто]"

    fun isSecretHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in setOf("authorization", "proxy-authorization", "cookie", "set-cookie") ||
            lower.contains("token") || lower.contains("key")
    }

    fun sanitize(name: String, input: String): String {
        if (name != "http_request") return input
        return try {
            val arguments = Json.parseToJsonElement(input) as? JsonObject ?: return incomplete()
            val headers = arguments["headers"] ?: return input
            if (headers !is JsonObject || headers.values.any { it !is JsonPrimitive || !it.isString }) return incomplete()
            JsonObject(arguments.toMutableMap().apply {
                put("headers", JsonObject(headers.mapValues { (key, value) ->
                    if (isSecretHeader(key)) JsonPrimitive(MASK) else value
                }))
            }).toString()
        } catch (_: Exception) {
            // Streaming calls can reach Room before their closing JSON braces arrive.
            incomplete()
        }
    }

    fun presentation(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (tool.toolName == "http_request") return tool.copy(input = sanitize(tool.toolName, tool.input))
        if (tool.toolName in setOf("workflow_create", "workflow_update") &&
            runCatching { Json.parseToJsonElement(tool.input) is JsonObject }.getOrDefault(false).not()) {
            // A partial JSON name can itself contain escapes, so raw-string detection is unsafe.
            // Hold malformed workflow previews closed until their complete arguments arrive.
            return tool.copy(input = incomplete())
        }
        // A workflow can display its nested tool JSON in the same chat detail sheet.
        // Leave other workflows/renderers untouched unless an HTTP action is present.
        fun containsHttpAction(name: String, input: String, depth: Int): Boolean {
            if (name == "http_request") return true
            if (name !in setOf("workflow_create", "workflow_update")) return false
            if (depth > 16) return true // Let the existing nested sanitizer enforce its depth boundary.
            val args = runCatching { Json.parseToJsonElement(input) as? JsonObject }.getOrNull() ?: return false
            val definition = args["definition"] as? JsonObject ?: return false
            val actions = definition["actions"] as? JsonArray ?: return false
            return actions.any { item ->
                val action = item as? JsonObject ?: return@any false
                val child = (action["tool"] as? JsonPrimitive)?.content ?: return@any false
                containsHttpAction(child, action["args"].toString(), depth + 1)
            }
        }
        return if (containsHttpAction(tool.toolName, tool.input, 0))
            tool.copy(input = sanitizeNestedToolArguments(tool.toolName, tool.input, ::sanitize)) else tool
    }

    private fun incomplete(): String = buildJsonObject {
        put("_credentials_removed", "Неполные аргументы HTTP удалены из истории для защиты секретных заголовков.")
    }.toString()
}
