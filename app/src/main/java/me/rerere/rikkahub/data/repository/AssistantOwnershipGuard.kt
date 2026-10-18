package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared boundary for assistant removal and writes that attach persisted data to an owner. */
class AssistantOwnershipGuard(private val ownerExists: suspend (String) -> Boolean) {
    private val mutex = Mutex()

    suspend fun <T> withOwner(ownerId: String?, block: suspend () -> T): T = mutex.withLock {
        if (ownerId != null) requireOwner(ownerId)
        block()
    }

    /** Holds the same ownership lock while a repository verifies an existing binding in its
     * database transaction. The supplied validator must be used for any new or changed binding.
     * It does not reacquire the mutex, so validation inside the transaction cannot self-deadlock.
     */
    internal suspend fun <T> withOwnerValidation(
        block: suspend (requireOwner: suspend (String) -> Unit) -> T,
    ): T = mutex.withLock { block { ownerId -> requireOwner(ownerId) } }

    private suspend fun requireOwner(ownerId: String) {
        check(ownerExists(ownerId)) { "Ассистент недоступен. Откройте чат заново перед изменением его данных." }
    }

    suspend fun <T> withDeletion(block: suspend () -> T): T = mutex.withLock { block() }
}

internal suspend fun <T> withAssistantOwnership(
    guard: AssistantOwnershipGuard?,
    ownerId: String?,
    block: suspend () -> T,
): T = if (guard == null) block() else guard.withOwner(ownerId, block)
