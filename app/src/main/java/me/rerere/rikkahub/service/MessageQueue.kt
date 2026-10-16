package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.annotation.WorkerThread
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.files.AttachmentReferenceCoordinator
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue(private val attachmentReferences: AttachmentReferenceCoordinator = AttachmentReferenceCoordinator()) {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()
    private val durableMutation = Mutex()
    private val persistenceLock = Any()
    private var store: MessageQueueStore? = null
    private var conversationId: Uuid? = null
    private var acceptedMessages: List<QueuedMessage> = emptyList()
    private var publishingDurableChange = false

    /** Acquire in UI call order, then perform disk and encryption work off the UI thread. */
    suspend fun <T> withDurableMutation(action: suspend () -> T): T = durableMutation.withLock {
        withContext(Dispatchers.IO) { action() }
    }

    @WorkerThread
    fun restoreFromStore(store: MessageQueueStore, conversationId: Uuid, savedMessageIds: Set<Uuid>) = persistentChange {
        if (this.store != null) {
            check(this.store === store && this.conversationId == conversationId)
            return@persistentChange
        }
        val stored = store.read(conversationId)
        val current = synchronized(this) { state.value.messages }
        val currentById = current.associateBy { it.id }
        val recovered = (stored.map { currentById[it.id] ?: it } + current)
            .distinctBy { it.id }.filterNot { it.id in savedMessageIds }.map { it.copy(isEditing = false) }
        // First prune IDs already committed to Room. A crash before this write simply
        // repeats this same ID check, never a model request or a tool invocation.
        store.write(conversationId, recovered)
        this.store = store
        this.conversationId = conversationId
        acceptedMessages = recovered
        synchronized(this) { publish(MessageQueueState(recovered, paused = recovered.isNotEmpty())) }
    }

    /** Keep the durable input during handoff; remove it only after its ID is saved in history. */
    @WorkerThread
    fun acknowledgeSubmission(id: Uuid) = persistentChange {
        if (acceptedMessages.none { it.id == id }) return@persistentChange
        persistAccepted(acceptedMessages.filterNot { it.id == id })
        synchronized(this) { publish(state.value.copy(messages = state.value.messages.filterNot { it.id == id })) }
    }

    private fun persistAccepted(messages: List<QueuedMessage>) {
        val bound = store ?: return
        bound.write(requireNotNull(conversationId), messages)
        acceptedMessages = messages
    }

    /** The UI's pause/edit monitor is never held during encryption or a disk write. */
    private fun <T> persistentChange(action: () -> T): T = synchronized(persistenceLock) {
        synchronized(this) { publishingDurableChange = true }
        try { action() }
        finally { synchronized(this) { publishingDurableChange = false } }
    }

    private fun publish(next: MessageQueueState) = attachmentReferences.withRuntimePublication {
        mutableState.value = next
    }

    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        persistentChange {
            val message = QueuedMessage(parts = parts.toList(), answer = answer, reply = reply)
            persistAccepted(acceptedMessages + message)
            synchronized(this) { publish(state.value.copy(messages = state.value.messages + message)) }
        }
    }

    @Synchronized
    fun takeNext(): QueuedMessage? = takeNextForSubmission {}

    @Synchronized
    internal fun takeNextForSubmission(onTaken: (QueuedMessage) -> Unit): QueuedMessage? {
        val current = state.value
        if (current.paused || publishingDurableChange) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        return attachmentReferences.withRuntimePublication {
            publish(current.copy(messages = current.messages.drop(1)))
            onTaken(next)
            next
        }
    }

    /** A stopped submission that never reached history still belongs before later input. */
    @Synchronized
    internal fun restoreForRetry(message: QueuedMessage) {
        val current = state.value
        val messages = if (current.messages.any { it.id == message.id }) current.messages else listOf(message) + current.messages
        publish(current.copy(messages = messages, paused = true))
        messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun remove(id: Uuid): QueuedMessage? = persistentChange {
        val removed = synchronized(this) { state.value.messages.find { it.id == id } } ?: return@persistentChange null
        persistAccepted(acceptedMessages.filterNot { it.id == id })
        synchronized(this) { publish(state.value.copy(messages = state.value.messages.filterNot { it.id == id })) }
        removed.reply?.complete(null)
        removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        publish(state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        ))
        return message
    }

    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        return persistentChange {
            val previous = synchronized(this) { state.value.messages.find { it.id == id } } ?: return@persistentChange null
            val snapshot = parts?.toList()
            if (snapshot != null) persistAccepted(acceptedMessages.map { if (it.id == id) it.copy(parts = snapshot) else it })
            synchronized(this) {
                publish(state.value.copy(messages = state.value.messages.map {
                    if (it.id == id) it.copy(parts = snapshot ?: it.parts, isEditing = false) else it
                }))
            }
            // Cancelling an edit only releases its placeholder, preserving original attachments.
            previous.takeIf { parts != null }
        }
    }

    @Synchronized
    fun pause() {
        publish(state.value.copy(paused = true))
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        publish(state.value.copy(paused = false))
    }
}
