package me.rerere.rikkahub.data.files

import android.net.Uri
import kotlin.uuid.Uuid

/** Short runtime publication lock; Room calls always run outside this monitor. */
class AttachmentReferenceCoordinator {
    private val lock = Any()
    private val reservations = mutableMapOf<Any, Set<String>?>()
    private val deletedHistories = mutableSetOf<Uuid>()
    private var runtimeReferences: (Set<Uuid>) -> Set<String> = { emptySet() }

    internal fun setRuntimeReferences(provider: (Set<Uuid>) -> Set<String>) = synchronized(lock) {
        runtimeReferences = provider
    }

    internal fun <T> withRuntimePublication(block: () -> T): T = synchronized(lock, block)

    internal fun publishConversation(id: Uuid, restoreHistory: Boolean, block: () -> Unit) = synchronized(lock) {
        // An initialization read that began before deletion must not revive its stale history.
        if (restoreHistory) deletedHistories.remove(id)
        block()
    }

    internal fun conversationDeleted(id: Uuid) = synchronized(lock) { deletedHistories.add(id) }
    internal fun conversationWritten(id: Uuid) = synchronized(lock) { deletedHistories.remove(id) }

    /** Reserve bytes before waiting for Room, including a fork's source while copying. */
    internal suspend fun <T> withFileReservation(urls: Collection<String>, block: suspend () -> T): T =
        withReservation(urls.toSet()) { block() }

    /** A fork may suspend before its loaded source URLs are available. */
    internal suspend fun <T> withPendingFileReservation(block: suspend ((Collection<String>) -> Unit) -> T): T =
        withReservation(null) { token ->
            block { urls -> synchronized(lock) { reservations[token] = urls.toSet() } }
        }

    private suspend fun <T> withReservation(urls: Set<String>?, block: suspend (Any) -> T): T {
        val token = Any()
        synchronized(lock) { reservations[token] = urls }
        try {
            return block(token)
        } finally {
            synchronized(lock) { reservations.remove(token) }
        }
    }

    /** Called inside the final Room transaction, after all persisted-reference queries. */
    internal fun deleteUnreferenced(candidates: List<Uri>, delete: (List<Uri>) -> Unit): Set<String> = synchronized(lock) {
        // Unknown in-flight source references fail closed until loading finishes.
        if (reservations.values.any { it == null }) return@synchronized emptySet()
        val retained = runtimeReferences(deletedHistories.toSet()) + reservations.values.filterNotNull().flatten()
        val unused = candidates.filter { it.toString() !in retained }
        if (unused.isNotEmpty()) delete(unused)
        unused.mapTo(mutableSetOf()) { it.toString() }
    }
}
