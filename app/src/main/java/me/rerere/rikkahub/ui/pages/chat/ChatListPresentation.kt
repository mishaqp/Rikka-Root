package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import kotlin.uuid.Uuid

/** Describes the existing lazy rows; errors and pending tools keep their current surfaces. */
sealed interface ChatListItemPresentation {
    val key: Any

    data class Message(
        val node: MessageNode,
        val loading: Boolean,
        val lastMessage: Boolean,
        val readOnly: Boolean,
        val pendingTools: List<ChatPendingToolPresentation>,
    ) : ChatListItemPresentation {
        override val key: Uuid get() = node.id
    }

    data class SystemPrompt(val customSystemPrompt: String?) : ChatListItemPresentation {
        override val key: String get() = "ConversationSystemPrompt"
    }

    data class Loading(val processingStatus: String?) : ChatListItemPresentation {
        override val key: String get() = "LoadingIndicator"
    }

    data object BottomSpacer : ChatListItemPresentation {
        override val key: String get() = "ScrollBottomKey"
    }
}

enum class ChatPendingToolKind { Approval, Answer }

data class ChatPendingToolKey(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
    val toolCallId: String,
    val partIndex: Int,
)

data class ChatPendingToolPresentation(
    val key: ChatPendingToolKey,
    val kind: ChatPendingToolKind,
    val tool: UIMessagePart.Tool,
) {
    // Keep the current Compose key, including its legacy blank-call-id fallback.
    val renderKey: String get() = tool.toolCallId.ifBlank { ThinkingStep.ToolStep(tool).hashCode().toString() }
}

data class ChatErrorPresentation(val error: ChatError) {
    val key: Uuid get() = error.id
    val conversationId: Uuid? get() = error.conversationId
}

data class ChatListPresentation(
    val items: List<ChatListItemPresentation>,
    val errors: List<ChatErrorPresentation>,
)

fun presentChatList(
    conversation: Conversation,
    loading: Boolean = false,
    processingStatus: String? = null,
    errors: List<ChatError> = emptyList(),
    allowSystemPrompt: Boolean = false,
    canChangeSystemPrompt: Boolean = false,
): ChatListPresentation = ChatListPresentation(
    items = buildList {
        conversation.messageNodes.forEachIndexed { index, node ->
            val message = node.currentMessage
            val pendingTools = if (message.isContextCheckpoint) emptyList() else message.parts.mapIndexedNotNull { partIndex, part ->
                (part as? UIMessagePart.Tool)?.takeIf { it.isPending }?.let { tool ->
                    ChatPendingToolPresentation(
                        key = ChatPendingToolKey(conversation.id, node.id, message.id, tool.toolCallId, partIndex),
                        kind = if (tool.toolName == "ask_user") ChatPendingToolKind.Answer else ChatPendingToolKind.Approval,
                        tool = tool,
                    )
                }
            }
            add(ChatListItemPresentation.Message(
                node = node,
                loading = loading && index == conversation.messageNodes.lastIndex,
                lastMessage = index == conversation.messageNodes.lastIndex,
                readOnly = conversation.historyLoadState != HistoryLoadState.COMPLETE,
                pendingTools = pendingTools,
            ))
        }
        if (conversation.historyLoadState == HistoryLoadState.COMPLETE && !loading && allowSystemPrompt && canChangeSystemPrompt) {
            add(ChatListItemPresentation.SystemPrompt(conversation.customSystemPrompt))
        }
        if (loading) add(ChatListItemPresentation.Loading(processingStatus))
        add(ChatListItemPresentation.BottomSpacer)
    },
    errors = errors.map(::ChatErrorPresentation),
)
