package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI-only metadata. Conversation and backup formats stay unchanged. */
data class ChatDrawerRowMetadata(
    val preview: String,
    val hasTools: Boolean = false,
    val hasWorkflows: Boolean = false,
) {
    companion object {
        fun fromConversation(conversation: Conversation): ChatDrawerRowMetadata {
            val messages = conversation.messageNodes.mapNotNull { it.messages.getOrNull(it.selectIndex) }
            val visibleMessages = messages.filter { it.role != MessageRole.SYSTEM }
            val preview = visibleMessages.asReversed().firstNotNullOfOrNull { message ->
                if (message.role == MessageRole.TOOL) null else {
                    message.parts.asReversed().filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull { it.text.isNotBlank() }?.text
                }
            } ?: "Работа с инструментами"
            val executedToolNames = visibleMessages.flatMap { message ->
                message.parts.mapNotNull { part ->
                    when (part) {
                        is UIMessagePart.Tool -> part.toolName.takeIf { part.isExecuted }
                        is UIMessagePart.ToolResult -> part.toolName
                        is UIMessagePart.ServerTool -> part.toolName.takeIf {
                            part.status == ServerToolStatus.COMPLETED || part.status == ServerToolStatus.FAILED
                        }
                        else -> null
                    }
                }
            }
            return ChatDrawerRowMetadata(
                preview = chatDrawerCompactPreview(preview),
                hasTools = executedToolNames.isNotEmpty(),
                hasWorkflows = executedToolNames.any { it.startsWith("workflow_") },
            )
        }
    }
}

internal fun chatDrawerCompactPreview(text: String): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    return if (compact.codePointCount(0, compact.length) > 160) {
        compact.substring(0, compact.offsetByCodePoints(0, 160)) + "…"
    } else compact
}

internal fun chatDrawerRowTime(
    timestamp: Instant,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val local = timestamp.atZone(zone)
    return local.format(DateTimeFormatter.ofPattern(
        if (local.toLocalDate() == today) "HH:mm" else "dd.MM", Locale.forLanguageTag("ru"),
    ))
}

data class ChatDrawerEmptyState(val title: String, val description: String, val actionLabel: String)

fun chatDrawerEmptyState(folderSelected: Boolean): ChatDrawerEmptyState = ChatDrawerEmptyState(
    title = if (folderSelected) "В этой папке пусто" else "Пока нет чатов",
    description = "Начните новый чат",
    actionLabel = "Новый чат",
)
