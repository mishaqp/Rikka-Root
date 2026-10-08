package me.rerere.rikkahub.data.ssh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import java.util.UUID

/** Room/tool-history stores opaque references; raw credentials only reach SSH in memory. */
class SshToolSecretSanitizer(private val store: SshCredentialStore) {
    private val references = mutableMapOf<SshCredentials, String>()

    fun sanitizeForPersistence(toolName: String, argsString: String): String {
        if (!isSshTool(toolName)) return argsString
        return try {
            val args = Json.parseToJsonElement(argsString) as? JsonObject ?: return PLACEHOLDER
            require(FIELDS.all { field -> args[field] == null || args[field] is JsonPrimitive })
            val secrets = FIELDS.map { field -> (args[field] as? JsonPrimitive)?.contentOrNull }
            if (secrets.none { it != null && !it.startsWith(PREFIX) }) return args.toString()
            val resolved = resolveArgs(toolName, args) as JsonObject
            val credentials = SshCredentials(
                (resolved["password"] as? JsonPrimitive)?.contentOrNull,
                (resolved["private_key"] as? JsonPrimitive)?.contentOrNull,
                (resolved["passphrase"] as? JsonPrimitive)?.contentOrNull,
            )
            val id = synchronized(references) {
                references[credentials] ?: UUID.randomUUID().toString().also {
                    store.write("tool:$it", credentials)
                    references[credentials] = it
                }
            }
            JsonObject(args.toMutableMap().apply {
                FIELDS.forEach { field ->
                    val value = (get(field) as? JsonPrimitive)?.contentOrNull
                    if (value != null) put(field, JsonPrimitive("$PREFIX$id:$field"))
                }
            }).toString()
        } catch (_: Exception) { PLACEHOLDER }
    }

    fun resolveArgs(toolName: String, args: JsonElement): JsonElement {
        if (toolName !in TOOLS) return args
        require(args is JsonObject) { "Аргументы SSH недоступны." }
        return JsonObject(args.toMutableMap().apply {
            FIELDS.forEachIndexed { index, field ->
                val value = (get(field) as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                if (!value.startsWith(PREFIX)) return@forEachIndexed
                val parts = value.removePrefix(PREFIX).split(':')
                require(parts.size == 2 && parts[1] == field &&
                    runCatching { UUID.fromString(parts[0]).toString() }.getOrNull() == parts[0]) {
                    "Ссылка на учётные данные SSH недействительна."
                }
                val credentials = try { store.read("tool:${parts[0]}") } catch (_: Exception) { null }
                val secret = when (index) { 0 -> credentials?.password; 1 -> credentials?.privateKey; else -> credentials?.passphrase }
                require(secret != null) { "Учётные данные SSH недоступны после восстановления. Введите их заново." }
                put(field, JsonPrimitive(secret))
            }
        })
    }

    companion object {
        /** Export/default-without-vault is fail-closed and never includes credential refs. */
        fun sanitizeForExport(toolName: String, argsString: String): String {
            if (!isSshTool(toolName)) return argsString
            return try {
                val args = Json.parseToJsonElement(argsString) as? JsonObject ?: return PLACEHOLDER
                JsonObject(args.filterKeys { it !in FIELDS }).toString()
            } catch (_: Exception) { PLACEHOLDER }
        }
        private fun isSshTool(toolName: String): Boolean = TOOLS.any { it.startsWith(toolName) }
        private val TOOLS = setOf("ssh_exec", "save_ssh_host")
        private val FIELDS = listOf("password", "private_key", "passphrase")
        private const val PREFIX = "rikka-ssh-secret:"
        private val PLACEHOLDER = buildJsonObject {
            put("_credentials_removed", "Неполные аргументы SSH удалены из истории для защиты учётных данных.")
        }.toString()
    }
}

/** Applies to current and legacy tool parts, without changing the caller's live messages. */
@Suppress("DEPRECATION")
fun sanitizeSshToolMessages(messages: List<UIMessage>,
    sanitizeArgs: (String, String) -> String = ::sanitizeToolArgsForExport,
): List<UIMessage> {
    fun sanitizePart(part: UIMessagePart): UIMessagePart = when (part) {
        is UIMessagePart.Tool -> part.copy(input = sanitizeArgs(part.toolName, part.input),
            output = part.output.map(::sanitizePart))
        is UIMessagePart.ToolCall -> part.copy(arguments = sanitizeArgs(part.toolName, part.arguments))
        is UIMessagePart.ToolResult -> part.copy(arguments = Json.parseToJsonElement(
            sanitizeArgs(part.toolName, part.arguments.toString())))
        else -> part
    }
    return messages.map { message -> message.copy(parts = message.parts.map(::sanitizePart)) }
}

fun sanitizeToolArgsForExport(toolName: String, args: String): String =
    McpToolSecretSanitizer.sanitizeForExport(toolName, SshToolSecretSanitizer.sanitizeForExport(toolName, args))
