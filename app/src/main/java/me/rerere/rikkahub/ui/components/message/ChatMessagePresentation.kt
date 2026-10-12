package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import kotlin.uuid.Uuid

/** Existing message conditions, independent of Compose and the visual layout. */
data class ChatMessagePresentation(
    val messageId: Uuid,
    val role: MessageRole,
    val isCheckpoint: Boolean,
    val showAvatarRow: Boolean,
    val showActions: Boolean,
    val canEditFromBubble: Boolean,
    val canInteractWithTools: Boolean,
)

fun presentChatMessage(
    message: UIMessage,
    loading: Boolean = false,
    lastMessage: Boolean = false,
    readOnly: Boolean = false,
): ChatMessagePresentation {
    val regular = !message.isContextCheckpoint
    val hasContent = !message.parts.isEmptyUIMessage()
    return ChatMessagePresentation(
        messageId = message.id,
        role = message.role,
        isCheckpoint = message.isContextCheckpoint,
        showAvatarRow = regular && hasContent,
        showActions = regular && if (lastMessage) !loading else hasContent,
        canEditFromBubble = regular && !readOnly && message.role == MessageRole.USER,
        canInteractWithTools = regular && !readOnly,
    )
}

enum class ChatMessageSheetAction { SelectAndCopy, WebPreview, Edit, Share, Fork, Favorite, Delete }

data class ChatMessageActionsPresentation(
    val mutationsEnabled: Boolean,
    val confirmRegeneration: Boolean,
    val showTts: Boolean,
    val showTranslation: Boolean,
    val sheetActions: List<ChatMessageSheetAction>,
) {
    fun canSpeak(ttsAvailable: Boolean): Boolean = showTts && ttsAvailable
}

fun presentChatMessageActions(
    message: UIMessage,
    readOnly: Boolean = false,
    canTranslate: Boolean = false,
    canToggleFavorite: Boolean = false,
): ChatMessageActionsPresentation = ChatMessageActionsPresentation(
    mutationsEnabled = !readOnly,
    confirmRegeneration = message.role == MessageRole.USER,
    showTts = message.role == MessageRole.ASSISTANT,
    showTranslation = message.role == MessageRole.ASSISTANT && !readOnly && canTranslate,
    sheetActions = buildList {
        add(ChatMessageSheetAction.SelectAndCopy)
        if (message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.isNotBlank() }) {
            add(ChatMessageSheetAction.WebPreview)
        }
        if (!readOnly) add(ChatMessageSheetAction.Edit)
        add(ChatMessageSheetAction.Share)
        if (!readOnly) add(ChatMessageSheetAction.Fork)
        if (!readOnly && canToggleFavorite) add(ChatMessageSheetAction.Favorite)
        if (!readOnly) add(ChatMessageSheetAction.Delete)
    },
)
