package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    private data class UndoDeletion(val snapshot: Conversation, val target: Assistant)
    private val undoDeletions = ConcurrentHashMap<Uuid, UndoDeletion>()
    private val undoMutex = Mutex()

    /** Display this destination before the user accepts an orphan-chat restoration. */
    fun undoDestinationName(conversationId: Uuid): String? = undoDeletions[conversationId]?.let {
        if (it.snapshot.assistantId == it.target.id) null else it.target.name.ifBlank { it.target.id.toString() }
    }

    fun discardUndo(conversationId: Uuid) { undoDeletions.remove(conversationId) }

    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun deleteConversation(conversation: Conversation): Boolean = try {
        undoMutex.withLock {
            val snapshot = checkNotNull(conversationRepo.getConversationById(conversation.id)) {
                "Чат уже удалён. Обновите историю."
            }
            val settings = settingsStore.settingsFlowRaw.first()
            check(!settings.init) { "Настройки ещё загружаются. Повторите удаление после загрузки." }
            val target = settings.getAssistantById(snapshot.assistantId) ?: settings.getCurrentAssistant()
            conversationRepo.deleteConversation(snapshot)
            undoDeletions[conversation.id] = UndoDeletion(snapshot, target)
        }
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        chatService.addError(error, conversation.id)
        false
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            try { conversationRepo.deleteConversationOfAssistant(assistant.id) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                chatService.addError(error)
            }
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            try { chatService.toggleConversationPinned(conversationId) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                chatService.addError(error, conversationId)
            }
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                undoMutex.withLock {
                    val deleted = checkNotNull(undoDeletions[conversation.id]) {
                        "Отменить можно только недавнее удаление чата на этом экране."
                    }
                    // The destination shown in the undo action is fixed. Do not silently adopt
                    // a different selected assistant, trust a caller snapshot, or bypass the live guard.
                    conversationRepo.insertConversation(deleted.snapshot.copy(assistantId = deleted.target.id))
                    undoDeletions.remove(conversation.id, deleted)
                }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                chatService.addError(error, conversation.id)
            }
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}
