package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.ai.RootCommandJournalButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

@Composable
fun SettingToolApprovalsPage() {
    val preferences = koinInject<ToolApprovalPreferences>()
    val yolo by preferences.globalYoloFlow.collectAsStateWithLifecycle(initialValue = false)
    val alwaysAllow by preferences.alwaysAllowFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val askAfterWebContent by preferences.askAfterWebContentFlow.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var showConfirmation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось сохранить разрешения. Проверьте свободное место." }
            finally { saving = false }
        }
    }

    if (showConfirmation) AlertDialog(
        onDismissRequest = { showConfirmation = false },
        title = { Text("Отключить все запросы одобрения?") },
        text = { Text(ToolPermissionPolicy.warningText(), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { showConfirmation = false; save { preferences.setYolo(true) } }) {
                Text("Да, я ГЛУПЫЙ, включить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showConfirmation = false }) { Text("Оставить одобрения") }
        },
    )

    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text("Разрешения инструментов") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (yolo) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (yolo) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Я ГЛУПЫЙ — автоодобрять всё", style = MaterialTheme.typography.titleMedium)
                            Text(if (yolo) "Вкл. Инструменты выполняются автоматически. Запретные root-команды и вопросы пользователю требуют ответа."
                                else "Выкл. Инструменты с побочными эффектами запрашивают одобрение перед запуском.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = yolo, enabled = !saving, onCheckedChange = {
                            if (it) showConfirmation = true else save { preferences.setYolo(false) }
                        })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Спрашивать после веб-контента", style = MaterialTheme.typography.titleMedium)
                        Text("После веб-поиска или чтения страницы запрашивать одобрение в этом чате, включая MCP. По умолчанию выключено.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = askAfterWebContent, enabled = !saving, onCheckedChange = {
                        save { preferences.setAskAfterWebContent(it) }
                    })
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { Text("Инструменты ниже выполняются без запроса на этом устройстве. Отзовите, если больше не доверяете модели эту возможность.") }
            if (alwaysAllow.isEmpty()) item {
                Text("Пока ни одному инструменту не выдано «Всегда разрешать». Бот будет спрашивать перед запуском любого инструмента с побочными эффектами.")
            }
            items(alwaysAllow.sorted(), key = { it }) { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name)
                        Text("Всегда разрешать", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !saving, onClick = { save { preferences.revoke(name) } }) { Text("Отозвать") }
                }
            }
            item { RootCommandJournalButton() }
        }
    }
}
