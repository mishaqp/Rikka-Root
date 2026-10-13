package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.utils.NightlyUpdateState

@Composable
internal fun SettingsStatePanel(
    settings: Settings,
    rootStatus: SettingsDashboardStatus?,
    update: NightlyUpdateState,
    open: (Screen) -> Unit,
    buildLabel: String = "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT_SHORT}",
) {
    val scheme = MaterialTheme.colorScheme
    val modelName = SettingsDashboardPanelPresentation.modelName(settings) ?: "Загрузка…"
    val updateChip = SettingsDashboardPanelPresentation.updateChip(update)
    val rootChip = when (rootStatus) {
        is SettingsDashboardStatus.Ready -> rootStatus.chip ?: SettingsDashboardStatusChip("Не проверен")
        SettingsDashboardStatus.Loading -> SettingsDashboardStatusChip("Проверяю…")
        SettingsDashboardStatus.Error -> SettingsDashboardStatusChip("Недоступно", SettingsDashboardStatusTone.Error)
        null -> SettingsDashboardStatusChip("Не проверен")
    }
    val modelChip = SettingsDashboardStatusChip(
        modelName,
        if (!settings.init && settings.findModelById(settings.chatModelId) != null)
            SettingsDashboardStatusTone.Success else SettingsDashboardStatusTone.Neutral,
    )
    Column {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = .25f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Rikka-Root", style = MaterialTheme.typography.titleLarge)
                    Text(buildLabel,
                        style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsPanelChip(SettingsCatalog.quickTiles.single { it.id == "root" }.icon,
                        rootChip, "Root") { open(Screen.SettingRoot) }
                    SettingsPanelChip(SettingsCatalog.quickTiles.single { it.id == "model" }.icon,
                        modelChip, "Модель по умолчанию") { open(Screen.SettingModels) }
                    SettingsPanelChip(HugeIcons.Refresh03, updateChip, "Обновления") {
                        open(if (update.hasUpdate) Screen.SettingUpdate else Screen.SettingAbout)
                    }
                }
            }
        }
        if (!settings.init && settings.isNotConfigured()) {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Подключите провайдера ИИ", style = MaterialTheme.typography.titleMedium)
                    Text("Добавьте сервис и выберите модель, чтобы начать общение.",
                        style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { open(Screen.SettingProvider) }) { Text("Подключить провайдера") }
                }
            }
        }
    }
}

/** One surface, one icon and one value: no nested status chip or redundant visible label. */
@Composable
private fun SettingsPanelChip(
    icon: ImageVector,
    chip: SettingsDashboardStatusChip,
    description: String,
    onClick: () -> Unit,
) {
    val colors = settingsStatusColors(chip.tone)
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp),
        color = colors.first, contentColor = colors.second,
        modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
            contentDescription = "$description. ${chip.text}"
            role = Role.Button
            onClick(label = "Открыть: $description") { onClick(); true }
        },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(chip.text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun SettingsQuickTiles(
    values: Map<String, String?>,
    onClick: (SettingsQuickTileMetadata) -> Unit,
) {
    val tiles = SettingsCatalog.quickTiles
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp && LocalDensity.current.fontScale <= 1.3f) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile ->
                        val value = values[tile.id]
                        val tone = tile.catalogItemId?.let { id ->
                            SettingsCatalog.items.single { it.id == id }.section.tone
                        } ?: SettingsSectionTone.Tertiary
                        Surface(
                            onClick = { onClick(tile) },
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp).clearAndSetSemantics {
                                contentDescription = listOfNotNull(tile.title, value).joinToString(". ")
                                role = Role.Button
                                onClick(label = "Открыть: ${tile.title}") { onClick(tile); true }
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    SettingsIconCircle(tile.icon, tone, size = 30.dp, iconSize = 18.dp)
                                    Text(tile.title, style = MaterialTheme.typography.labelLarge)
                                }
                                value?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
