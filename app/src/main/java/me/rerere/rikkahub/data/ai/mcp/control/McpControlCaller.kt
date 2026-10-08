// Necessary ConversationConfig adaptation of ExTV/rikkahub-agent MCP control (AGPL v3).
package me.rerere.rikkahub.data.ai.mcp.control

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant

/** Bound by trusted generation state; never resolve the globally selected assistant. */
class McpControlCaller(
    val assistantMcpServers: () -> Set<Uuid>,
    val addServer: suspend (Uuid) -> Unit,
    val removeServer: suspend (Uuid) -> Unit,
)

/** Root's ConversationConfig adaptation of Agent's selected-assistant allowlist update.
 * A headless run has an ephemeral side-context, so it must never write its creator chat. */
internal fun createMcpControlCaller(
    callerAssistant: Assistant,
    conversationId: Uuid?,
    headless: Boolean,
    updateChatAssistant: suspend (Uuid, (Assistant) -> Assistant) -> Unit,
): McpControlCaller {
    var callerMcpServers = callerAssistant.mcpServers
    suspend fun update(serverId: Uuid, add: Boolean) {
        if (headless) {
            callerMcpServers = if (add) callerMcpServers + serverId else callerMcpServers - serverId
            return
        }
        val id = conversationId ?: error("Для изменения MCP нужен контекст текущего чата.")
        var updatedServers: Set<Uuid>? = null
        updateChatAssistant(id) { live ->
            check(live.id == callerAssistant.id) { "Владелец текущего чата изменился; повторите запрос MCP." }
            val updated = if (add) live.mcpServers + serverId else live.mcpServers - serverId
            updatedServers = updated
            live.copy(mcpServers = updated)
        }
        callerMcpServers = checkNotNull(updatedServers) { "Не удалось обновить конфигурацию MCP текущего чата." }
    }
    return McpControlCaller(
        assistantMcpServers = { callerMcpServers },
        addServer = { update(it, true) },
        removeServer = { update(it, false) },
    )
}
