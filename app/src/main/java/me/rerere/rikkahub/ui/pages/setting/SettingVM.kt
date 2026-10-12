package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.applyProxyDraft
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.applyProviderDraft
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

class SettingVM(
    private val settingsStore: SettingsStore,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsStore.update(transform)
    }

    fun updateProviderDraft(before: ProviderSetting, requested: ProviderSetting) =
        updateSettings { current -> current.copy(providers = current.providers.map { row ->
            if (row.id == before.id) applyProviderDraft(row, before, requested) else row
        }) }

    fun updateProxyDraft(before: NetworkSetting, requested: NetworkSetting, reset: Boolean = false) =
        updateSettings { current -> current.copy(networkSetting = applyProxyDraft(current.networkSetting, before, requested, reset)) }

    fun updateDisplaySetting(transform: (DisplaySetting) -> DisplaySetting) =
        updateSettings { current -> current.copy(displaySetting = transform(current.displaySetting)) }
}
