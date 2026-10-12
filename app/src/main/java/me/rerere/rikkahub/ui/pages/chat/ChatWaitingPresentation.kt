package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage

/** Empty assistant messages own their waiting dots; other loading states use the footer. */
internal fun showChatFooterWaiting(lastMessage: UIMessage?): Boolean =
    lastMessage == null || lastMessage.role != MessageRole.ASSISTANT ||
        lastMessage.isContextCheckpoint || !lastMessage.parts.isEmptyUIMessage()
