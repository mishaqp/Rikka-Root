package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.preferences.isWorkspaceToolName

/** Agent's process-local grants, isolated by conversation ID. */
object ToolApprovalAllowList {
    private val perChat: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private fun key(conversationId: Uuid, toolName: String) = "${conversationId}::${toolName}"

    fun isAllowedForChat(conversationId: Uuid, toolName: String): Boolean = perChat.contains(key(conversationId, toolName))
    fun grantForChat(conversationId: Uuid, toolName: String) { perChat.add(key(conversationId, toolName)) }
    fun clearChat(conversationId: Uuid) { perChat.removeIf { it.startsWith("${conversationId}::") } }
    fun revokeForChat(conversationId: Uuid, toolName: String) { perChat.remove(key(conversationId, toolName)) }
    fun listForChat(conversationId: Uuid): List<String> = perChat.asSequence()
        .filter { it.startsWith("${conversationId}::") }.map { it.removePrefix("${conversationId}::") }.sorted().toList()
}

/** Read fresh for every invocation; workspace grants never come from the global list. */
internal suspend fun resolveToolAutoApproval(
    preferences: ToolApprovalPreferences,
    conversationId: Uuid,
    toolName: String,
    webContentSeen: Boolean,
    workspaceNeedsApproval: suspend () -> Boolean = { true },
): Boolean {
    if (toolName == "ask_user") return false
    if (preferences.currentAskAfterWebContent() && webContentSeen) return false
    return preferences.currentYolo() ||
        ToolApprovalAllowList.isAllowedForChat(conversationId, toolName) ||
        if (isWorkspaceToolName(toolName)) !workspaceNeedsApproval() else toolName in preferences.current()
}
