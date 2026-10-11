package me.rerere.rikkahub.service

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation

internal data class FastModelTarget(val model: Model, val provider: ProviderSetting)

internal fun Settings.findFastModelTarget(): FastModelTarget? {
    val model = findModelById(fastModelId) ?: return null
    val provider = model.findProvider(providers) ?: return null
    return FastModelTarget(model, provider)
}

/** The drawer uses the same availability check as background title generation. */
fun Settings.hasFastModelTarget(): Boolean = findFastModelTarget() != null

/** Only the legacy blank/default drawer names may be replaced without an explicit rename. */
fun isLegacyConversationTitle(title: String): Boolean = title.isBlank() || title.trim() == "Новый чат"

/** Assign the local title with the first saved user message, even if its reply later fails. */
internal fun applyLocalConversationTitle(settings: Settings, conversation: Conversation): Conversation {
    if (conversation.title.isNotBlank()) return conversation
    if (settings.displaySetting.enableAutoChatTitles && settings.hasFastModelTarget()) return conversation
    if (conversation.currentMessages.none { it.role == MessageRole.USER }) return conversation
    return conversation.copy(title = localConversationTitle(conversation))
}

/** A missing background model is an ordinary local-title case, not a chat error. */
internal suspend fun generateConversationTitle(
    settings: Settings,
    conversation: Conversation,
    force: Boolean = false,
    generateAi: suspend (FastModelTarget) -> String,
): String? {
    if (!force && conversation.title.isNotBlank()) return null
    val target = settings.findFastModelTarget()
    // The explicit action keeps the current title; the UI offers fast-model settings instead.
    if (force && target == null) return null
    if (!settings.displaySetting.enableAutoChatTitles || target == null) {
        return localConversationTitle(conversation)
    }
    return generateAi(target)
}

private val titleWhitespace = Regex("[\\s\\p{Z}]+")
private val titleDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun localConversationTitle(
    conversation: Conversation,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val text = conversation.currentMessages.firstOrNull { it.role == MessageRole.USER }
        ?.toText().orEmpty().replace(titleWhitespace, " ").trim()
    if (text.isEmpty()) {
        return "Чат от ${conversation.createAt.atZone(zoneId).toLocalDate().format(titleDateFormat)}"
    }
    if (text.codePointCount(0, text.length) <= 40) return text
    val end = text.offsetByCodePoints(0, 40)
    val prefix = text.substring(0, end)
    // Keep a whole final word when the limit falls inside it, including for Cyrillic text.
    if (text[end] == ' ') return prefix.trimEnd()
    val wordBoundary = prefix.lastIndexOf(' ')
    return if (wordBoundary > 0) prefix.substring(0, wordBoundary) else prefix
}
