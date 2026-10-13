package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** Keep the regeneration target and its versions; only the later nodes are optional. */
internal fun conversationForAssistantRegeneration(
    source: Conversation,
    messageId: Uuid,
    deleteTrailingMessages: Boolean,
): Conversation {
    if (!deleteTrailingMessages) return source
    val index = source.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    require(index >= 0) { "Regeneration message not found" }
    return source.copy(messageNodes = source.messageNodes.take(index + 1))
}

/** Projection happens before file copying; unselected versions stay in the source. */
internal fun messageNodesForFork(
    source: List<MessageNode>,
    targetNodeIndex: Int,
    keepMessageVersions: Boolean,
): List<MessageNode> {
    require(targetNodeIndex in source.indices) { "Fork message not found" }
    val selected = source.take(targetNodeIndex + 1)
    return if (keepMessageVersions) selected else selected.map { node ->
        node.copy(messages = listOf(node.currentMessage), selectIndex = 0)
    }
}
