package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/** Every candidate comes from this account's catalog; the response order is the fallback order. */
internal fun preferredCodexModel(catalog: List<Model>): Model? {
    val candidates = catalog.filter { it.type == ModelType.CHAT && it.modelId.isNotBlank() }
    val preferredName = Regex(
        "(?:^|[^a-z0-9])gpt[-_. ]*6[._-]?1[-_. ]*sol(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    fun String.isPreferredName(): Boolean = preferredName.containsMatchIn(this)
    return candidates.firstOrNull {
        it.modelId.isPreferredName() || it.displayName.isPreferredName()
    } ?: candidates.firstOrNull()
}

/** Applies catalog refresh and defaults together; persisted conversations keep their snapshots. */
internal fun Settings.withRefreshedCodexModels(
    providerId: Uuid,
    catalog: List<Model>,
    afterLogin: Boolean = false,
): Settings {
    val provider = providers.find { it.id == providerId } as? ProviderSetting.Codex ?: return this
    val merged = mergeCodexModels(provider.models, catalog)
    val preferred = preferredCodexModel(catalog)
    val firstCatalog = provider.models.isEmpty()
    val updated = copy(providers = providers.map {
        if (it.id == providerId) provider.copy(
            models = merged,
            enabled = provider.enabled || (afterLogin && firstCatalog && preferred != null),
        ) else it
    })
    // Defaults change only for the first usable catalog, never on later refresh or reauthentication.
    if (!firstCatalog || preferred == null) return updated
    val retained = merged.firstOrNull { it.modelId == preferred.modelId } ?: return updated
    return updated.copy(
        chatModelId = retained.id,
        assistants = assistants.map {
            if (it.id == assistantId && it.chatModelId != null && it.chatModelId != retained.id) {
                it.copy(chatModelId = null)
            } else it
        },
    )
}
