package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Keeps destructive cleanup behind the drawer's undo opportunity. */
internal class ChatDrawerDeleteCoordinator {
    private val pending = MutableStateFlow<Set<Uuid>>(emptySet())
    val pendingIds = pending.asStateFlow()

    suspend fun delete(
        id: Uuid,
        offerUndo: suspend () -> Boolean,
        commit: suspend () -> Unit,
    ) {
        if (id in pending.value) return
        pending.value += id
        var undone = false
        try {
            undone = offerUndo()
        } finally {
            try {
                // Closing the drawer must not leave a hidden, never-deleted conversation.
                if (!undone) withContext(NonCancellable) { commit() }
            } finally {
                pending.value -= id
            }
        }
    }
}
