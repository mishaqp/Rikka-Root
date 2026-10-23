package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal fun Settings.withRefreshedKimiModels(providerId: Uuid, catalog: List<Model>, afterLogin: Boolean = false): Settings {
    val provider = providers.find { it.id == providerId } as? ProviderSetting.KimiCode ?: return this
    val models = mergeCodexModels(provider.models, catalog)
    val first = provider.models.isEmpty() && models.isNotEmpty()
    val updated = copy(providers = providers.map {
        if (it.id == providerId) provider.copy(models = models,
            enabled = provider.enabled || (first && afterLogin)) else it
    })
    if (!first || !afterLogin) return updated
    return updated.copy(chatModelId = models.first().id, assistants = assistants.map {
        if (it.id == assistantId) it.copy(chatModelId = null) else it
    })
}
