// Mandatory AndroidKeyStore/history adaptation for ExTV/rikkahub-agent MCP control (AGPL v3).
package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.reliability.SecretRedactor
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Raw streamed credentials may exist in the live generation, never in Room or backups. */
class McpToolSecretSanitizer(private val store: McpControlSecretStore) {
    private val references = ConcurrentHashMap<String, String>()

    fun sanitizeForPersistence(name: String, input: String): String {
        if (!isMcpTool(name)) return input
        return try {
            val args = Json.parseToJsonElement(input) as? JsonObject ?: return PLACEHOLDER
            val protected = args.toMutableMap()
            args["headers"]?.let { raw ->
                require(raw is JsonArray)
                protected["headers"] = JsonArray(raw.map { entry ->
                    require(entry is JsonObject)
                    val fields = entry.toMutableMap()
                    entry["value"]?.let { value ->
                        require(value is JsonPrimitive && value.isString)
                        val bytes = value.content
                        if (bytes.isNotEmpty() && !bytes.startsWith(McpControlSecretStore.REFERENCE_PREFIX)) {
                            val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                            fields["value"] = JsonPrimitive(references.computeIfAbsent(digest) { store.put(bytes) })
                        }
                    }
                    JsonObject(fields)
                })
            }
            safeJson(JsonObject(protected.mapValues { (key, value) ->
                when {
                    key == "headers" -> value
                    key == "url" && value is JsonPrimitive && value.isString -> JsonPrimitive(safeUrl(value.content))
                    value is JsonPrimitive && value.isString -> JsonPrimitive(SecretRedactor.redact(value.content))
                    else -> value
                }
            }))
        } catch (_: Exception) { PLACEHOLDER }
    }

    companion object {
        /** No vault access during sharing or an unconfigured persistence fallback. */
        fun sanitizeForExport(name: String, input: String): String {
            if (!isMcpTool(name)) return input
            return try {
                val args = Json.parseToJsonElement(input) as? JsonObject ?: return PLACEHOLDER
                safeJson(JsonObject(args.filterKeys { it != "headers" }.mapValues { (key, value) ->
                    when {
                        key == "url" && value is JsonPrimitive && value.isString -> JsonPrimitive(safeUrl(value.content))
                        value is JsonPrimitive && value.isString -> JsonPrimitive(SecretRedactor.redact(value.content))
                        else -> value
                    }
                }))
            } catch (_: Exception) { PLACEHOLDER }
        }

        private fun safeUrl(value: String): String {
            val parsed = runCatching { URI(value) }.getOrNull()
            if (parsed == null || parsed.host == null || parsed.scheme !in setOf("http", "https")) {
                return "[некорректный URL удалён из истории MCP]"
            }
            if (parsed?.rawUserInfo != null || parsed?.rawQuery != null || parsed?.rawFragment != null) {
                return "[удалено: секрет в URL; используйте защищённый заголовок MCP]"
            }
            return SecretRedactor.redact(value)
        }

        /** Covers unexpected secret-shaped extra fields too; never persist malformed masking. */
        private fun safeJson(value: JsonObject): String = try {
            Json.parseToJsonElement(SecretRedactor.redact(value.toString())).toString()
        } catch (_: Exception) { PLACEHOLDER }

        private fun isMcpTool(name: String): Boolean = TOOLS.any { it.startsWith(name) }
        private val TOOLS = setOf("mcp_add", "mcp_update")
        private val PLACEHOLDER = buildJsonObject {
            put("_credentials_removed", "Неполные аргументы MCP удалены из истории для защиты секретных заголовков.")
        }.toString()
    }
}
