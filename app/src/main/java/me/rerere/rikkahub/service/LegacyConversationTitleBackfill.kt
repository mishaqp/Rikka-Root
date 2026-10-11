package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation

/** Never create a runtime session or migrate legacy config solely for drawer title upkeep. */
internal suspend fun backfillLoadedLegacyConversationTitle(
    persisted: Conversation,
    expectedTitle: String,
    session: ConversationSession?,
    onCommitted: suspend (String) -> Unit = {},
    persist: suspend (String) -> Boolean,
): Boolean {
    if (persisted.title != expectedTitle || !isLegacyConversationTitle(expectedTitle)) return false
    if (session != null) {
        if (!session.isInitialized) return false
        return session.backfillLegacyTitle(expectedTitle, persist)
    }
    val title = localConversationTitle(persisted)
    if (title == expectedTitle || !persist(title)) return false
    onCommitted(title)
    return true
}
