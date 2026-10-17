package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared boundary for assistant removal and writes that attach persisted data to an owner. */
class AssistantOwnershipGuard(private val ownerExists: suspend (String) -> Boolean) {
    private val mutex = Mutex()

    suspend fun <T> withOwner(ownerId: String?, block: suspend () -> T): T = mutex.withLock {
        if (ownerId != null) {
            check(ownerExists(ownerId)) { "Ассистент недоступен. Откройте чат заново перед изменением его данных." }
        }
        block()
    }

    suspend fun <T> withDeletion(block: suspend () -> T): T = mutex.withLock { block() }
}

internal suspend fun <T> withAssistantOwnership(
    guard: AssistantOwnershipGuard?,
    ownerId: String?,
    block: suspend () -> T,
): T = if (guard == null) block() else guard.withOwner(ownerId, block)
