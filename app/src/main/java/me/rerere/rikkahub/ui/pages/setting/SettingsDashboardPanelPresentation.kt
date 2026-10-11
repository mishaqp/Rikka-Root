package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.utils.NightlyUpdateState

/** Pure presentation values shared by the dashboard header and its quick tiles. */
internal object SettingsDashboardPanelPresentation {
    fun updateChip(state: NightlyUpdateState): SettingsDashboardStatusChip = when {
        state.hasUpdate -> SettingsDashboardStatusChip("Есть обновление", SettingsDashboardStatusTone.Warning)
        state.isChecking -> SettingsDashboardStatusChip("Проверяю…")
        state.update != null && !state.update.isNewer && state.error == null ->
            SettingsDashboardStatusChip("Актуальная версия", SettingsDashboardStatusTone.Success)
        // checkedAt records failed attempts too; it alone cannot establish an up-to-date version.
        else -> SettingsDashboardStatusChip("Не проверялось")
    }

    fun modelName(settings: Settings): String? {
        if (settings.init) return null
        val model = settings.findModelById(settings.chatModelId)
        return model?.displayName?.takeIf { it.isNotBlank() }
            ?: model?.modelId?.takeIf { it.isNotBlank() }
            ?: "Не выбрана"
    }

    fun quickTileValues(settings: Settings, rootStatus: SettingsDashboardStatus?): Map<String, String?> {
        val providers = if (settings.init) null else settings.providers
            .count { it.enabled && it.models.isNotEmpty() }
            .takeIf { it > 0 }
            ?.let { "$it подключено" }
        val tools = if (settings.init) null else settings.assistants
            .find { it.id == settings.assistantId }
            ?.takeIf { it.localTools.isNotEmpty() }
            ?.let { "Включено ${it.localTools.size}" }
        val root = when (rootStatus) {
            is SettingsDashboardStatus.Ready -> rootStatus.chip?.text
            SettingsDashboardStatus.Error -> "Недоступно"
            SettingsDashboardStatus.Loading, null -> null
        }
        return mapOf(
            "providers" to providers,
            "model" to modelName(settings),
            "tools" to tools,
            "root" to root,
        )
    }
}
