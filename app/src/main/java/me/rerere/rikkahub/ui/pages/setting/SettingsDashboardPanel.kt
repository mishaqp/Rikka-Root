package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.utils.NightlyUpdateState

@Composable
internal fun SettingsStatePanel(settings: Settings, rootStatus: SettingsDashboardStatus?, update: NightlyUpdateState, open: (Screen) -> Unit) {
    val model = settings.findModelById(settings.chatModelId)
    val modelName = if (settings.init) "Загрузка…" else model?.displayName?.ifBlank { model.modelId } ?: "Модель не выбрана"
    val updateText = when {
        update.hasUpdate -> "Доступно обновление"
        update.isChecking -> "Проверка обновлений…"
        update.checkedAt != null && update.error == null -> "Обновлений нет"
        else -> "Обновления"
    }
    Column {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rikka-Root", style = MaterialTheme.typography.headlineMedium)
                Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT_SHORT}", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsPanelChip("Root", rootStatus, "Открыть настройки Root") { open(Screen.SettingRoot) }
                    SettingsPanelChip("Модель", SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(modelName)),
                        "Модель по умолчанию") { open(Screen.SettingModels) }
                    SettingsPanelChip(null, SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(updateText,
                        if (update.hasUpdate) SettingsDashboardStatusTone.Success else SettingsDashboardStatusTone.Neutral)),
                        updateText) { open(if (update.hasUpdate) Screen.SettingUpdate else Screen.SettingAbout) }
                }
            }
        }
        if (!settings.init && settings.isNotConfigured()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Подключите провайдера ИИ", style = MaterialTheme.typography.titleMedium)
                    Text("Добавьте сервис и выберите модель, чтобы начать общение.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { open(Screen.SettingProvider) }) { Text("Подключить провайдера") }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanelChip(label: String?, status: SettingsDashboardStatus?, description: String, onClick: () -> Unit) {
    val statusText = when (status) {
        SettingsDashboardStatus.Loading, null -> "Загрузка"
        SettingsDashboardStatus.Error -> "Недоступно"
        is SettingsDashboardStatus.Ready -> status.chip?.text
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
            contentDescription = listOfNotNull(description, statusText).joinToString(". ")
            role = Role.Button
            onClick(label = description) { onClick(); true }
        }) {
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            label?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 5.dp)) }
            SettingsStatusChip(status)
        }
    }
}

@Composable
internal fun SettingsQuickTiles(onClick: (String) -> Unit) {
    val tiles = listOf(
        Triple("providers", "Провайдеры", HugeIcons.ServerStack01),
        Triple("model", "Модель по умолчанию", HugeIcons.Brain02),
        Triple("tools", "Инструменты ассистента", HugeIcons.AiMagic),
        Triple("root", "Root", HugeIcons.ComputerTerminal01),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp && LocalDensity.current.fontScale <= 1.3f) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, title, icon) ->
                        Surface(onClick = { onClick(id) }, modifier = Modifier.weight(1f).heightIn(min = 92.dp)
                            .clearAndSetSemantics { contentDescription = title; role = Role.Button; onClick(label = "Открыть: $title") { onClick(id); true } },
                            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Text(title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
