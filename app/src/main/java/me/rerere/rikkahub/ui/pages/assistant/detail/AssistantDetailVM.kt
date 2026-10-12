package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(beforeIds: List<Uuid>, tagIds: List<Uuid>, beforeTags: List<Tag>, tags: List<Tag>) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val added = tags.filter { tag -> beforeTags.none { it.id == tag.id } }
                val changed = tags.filter { tag -> beforeTags.any { it.id == tag.id && it != tag } }.associateBy { it.id }
                val globalTags = current.assistantTags.map { changed[it.id] ?: it } + added.filter { tag -> current.assistantTags.none { it.id == tag.id } }
                cleanTags(current.copy(
                    assistantTags = globalTags,
                    assistants = current.assistants.map { row ->
                        if (row.id == assistantId) row.copy(tags = (row.tags.filterNot { it in beforeIds && it !in tagIds } + tagIds.filter { it !in beforeIds }).distinct()) else row
                    }
                ))
            }
        }
    }

    private fun cleanTags(current: Settings): Settings {
        val validIds = current.assistantTags.map { it.id }.toSet()
        val assistants = current.assistants.map { it.copy(tags = it.tags.filter { id -> id in validIds }) }
        val used = assistants.flatMap { it.tags }.toSet()
        return current.copy(assistants = assistants, assistantTags = current.assistantTags.filter { it.id in used })
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch { settingsStore.update(::cleanTags) }
    }

    fun update(transform: (Assistant) -> Assistant) = viewModelScope.launch {
        persistAssistantUpdate(transform)
    }

    internal suspend fun persistAssistantUpdate(transform: (Assistant) -> Assistant) {
        var changed: Pair<Assistant, Assistant>? = null
        val committed = settingsStore.update { current ->
            current.copy(assistants = current.assistants.map { row ->
                if (row.id == assistantId) transform(row).also { changed = row to it } else row
            })
        }
        changed?.let { (old, new) ->
            val avatarUrl = (old.avatar as? Avatar.Image)?.url
            val userAvatarUrl = (committed.displaySetting.userAvatar as? Avatar.Image)?.url
            if (avatarUrl != userAvatarUrl && committed.assistants.none { (it.avatar as? Avatar.Image)?.url == avatarUrl || it.background == avatarUrl }) {
                checkAvatarDelete(old, new)
            }
            if (old.background != userAvatarUrl && committed.assistants.none { it.background == old.background || (it.avatar as? Avatar.Image)?.url == old.background }) {
                checkBackgroundDelete(old, new)
            }
        }
    }

    /** Tool UI updates may follow a permission dialog: merge into the latest stored assistant. */
    fun updateToolSettings(before: Assistant, requested: Assistant) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map { current ->
                    if (current.id == assistantId) mergeToolUiChanges(current, before, requested) else current
                })
            }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateContent(id = memory.id, content = memory.content)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
