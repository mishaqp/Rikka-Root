package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

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
            var removed: Assistant? = null
            val committed = settingsStore.update { current -> current.copy(
                    assistants = current.assistants.filter { row ->
                        if (row.id == assistant.id) { removed = row; false } else true
                    }
                ) }
            removed?.let { cleanupAssistantFiles(it, committed) }
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant, retained: Settings) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        val referenced = retained.assistants.flatMap { row ->
            listOfNotNull((row.avatar as? Avatar.Image)?.url, row.background)
        } + listOfNotNull((retained.displaySetting.userAvatar as? Avatar.Image)?.url)
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
