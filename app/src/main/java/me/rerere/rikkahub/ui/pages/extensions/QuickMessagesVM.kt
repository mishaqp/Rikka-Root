package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.QuickMessage
import kotlin.uuid.Uuid

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun addQuickMessage(title: String, content: String) {
        val message = QuickMessage(title = title, content = content)
        updateQuickMessages { current -> current + message }
    }

    fun updateQuickMessage(before: QuickMessage, updated: QuickMessage) {
        updateQuickMessages { current -> current.map { row ->
            if (row.id == updated.id) row.copy(
                title = if (before.title != updated.title) updated.title else row.title,
                content = if (before.content != updated.content) updated.content else row.content,
            ) else row
        } }
    }

    fun deleteQuickMessage(id: Uuid) {
        updateQuickMessages { current -> current.filterNot { it.id == id } }
    }

    private fun updateQuickMessages(transform: (List<QuickMessage>) -> List<QuickMessage>) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val messages = transform(settings.quickMessages)
                val validIds = messages.map { it.id }.toSet()
                settings.copy(
                    quickMessages = messages,
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(quickMessageIds = assistant.quickMessageIds.filter { it in validIds }.toSet())
                    }
                )
            }
        }
    }
}
