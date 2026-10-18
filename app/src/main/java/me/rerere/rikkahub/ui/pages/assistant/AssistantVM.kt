package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.localFileUrls
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard

data class AssistantRemovalError(
    val messageRes: Int,
    val cronJobs: Int = 0,
    val workflows: Int = 0,
    val cause: Exception? = null,
)

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val ownershipGuard: AssistantOwnershipGuard? = null,
    private val scheduledJobRepository: ScheduledJobRepository? = null,
    private val workflowRepository: WorkflowRepository? = null,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    private val _removalError = MutableStateFlow<AssistantRemovalError?>(null)
    val removalError: StateFlow<AssistantRemovalError?> = _removalError.asStateFlow()

    fun clearRemovalError() {
        _removalError.value = null
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(
                    assistants = (current.assistants + assistant).distinctBy { it.id }
                ) }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            _removalError.value = null
            var cleanupStarted = false
            try {
                var removed: Assistant? = null
                suspend fun removeOwner(): Settings? {
                    val owner = assistant.id.toString()
                    val cronJobs = scheduledJobRepository?.countOfAssistant(owner) ?: 0
                    val workflows = workflowRepository?.countReferencingAssistant(owner) ?: 0
                    check(cronJobs >= 0 && workflows >= 0)
                    if (cronJobs > 0 || workflows > 0) {
                        _removalError.value = AssistantRemovalError(
                            R.string.assistant_page_delete_has_scheduled_work, cronJobs, workflows,
                        )
                        return null
                    }
                    scheduledJobRepository?.purgeDeletedOfAssistant(owner)
                    return settingsStore.update { current -> current.copy(
                        assistants = current.assistants.filter { row ->
                            if (row.id == assistant.id) { removed = row; false } else true
                        }
                    ) }
                }
                // Counts and owner removal share the writer boundary. Cleanup must run after release.
                val committed = (if (ownershipGuard == null) removeOwner()
                    else ownershipGuard.withDeletion { removeOwner() }) ?: return@launch
                cleanupStarted = true
                removed?.let { cleanupAssistantFiles(it, committed) }
                memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
                conversationRepo.deleteConversationOfAssistant(assistant.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // DataStore and Room are separate stores: a cleanup failure does not undo owner removal.
                _removalError.value = AssistantRemovalError(
                    if (cleanupStarted) R.string.assistant_page_delete_cleanup_failed
                    else R.string.assistant_page_delete_failed,
                    cause = error,
                )
            }
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant, retained: Settings) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        val referenced = retained.localFileUrls()
        val unused = uris.filterNot { it.toString() in referenced }
        if (unused.isNotEmpty()) filesManager.deleteChatFiles(unused)
    }

    fun copyAssistant(assistant: Assistant, copyMemories: Boolean = false) {
        val newId = kotlin.uuid.Uuid.random()
        viewModelScope.launch {
            var copiedAssistant: Assistant? = null
            settingsStore.update { current ->
                val source = current.assistants.find { it.id == assistant.id } ?: return@update current
                val copied = source.copy(id = newId, name = "${source.name} (Clone)",
                    avatar = if (source.avatar is Avatar.Image) Avatar.Dummy else source.avatar)
                copiedAssistant = copied
                current.copy(assistants = current.assistants + copied)
            }
            if (copyMemories && copiedAssistant != null) {
                memoryRepository.copyMemories(fromAssistantId = assistant.id.toString(), toAssistantId = newId.toString())
            }
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
