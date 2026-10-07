package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun RootAutomaticSwitch(
    assistantId: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val store = koinInject<RootAccessStore>()
    val enabledAssistants by store.enabledAssistants.collectAsStateWithLifecycle()
    Switch(
        checked = assistantId in enabledAssistants,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
}

/** Reads live access state, independently of the conversation's frozen configuration. */
@Composable
fun RootAutomaticIndicator(assistantId: String) {
    val store = koinInject<RootAccessStore>()
    val enabledAssistants by store.enabledAssistants.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val errorText by rememberUpdatedState(stringResource(R.string.root_access_save_failed))
    var pending by remember(assistantId) { mutableStateOf(false) }
    if (assistantId !in enabledAssistants) return

    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.root_automatic_active),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !pending,
                    onClick = {
                        pending = true
                        scope.launch {
                            try {
                                store.setAutomatic(assistantId, false)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                toaster.show(errorText, type = ToastType.Error)
                            } finally {
                                pending = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.root_automatic_disable)) }
            }
            Text(
                stringResource(R.string.root_automatic_disable_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RootCommandJournalButton(assistantId: String) {
    var open by remember(assistantId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.root_command_log_open))
    }
    if (!open) return

    val store = koinInject<RootAccessStore>()
    val entries by store.entries.collectAsStateWithLifecycle()
    val assistantEntries = remember(entries, assistantId) {
        entries.filter { it.assistantId == assistantId }.sortedByDescending { it.timestampMs }.take(200)
    }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.root_command_log_title)) },
        text = {
            if (assistantEntries.isEmpty()) {
                Text(stringResource(R.string.root_command_log_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(assistantEntries, key = { it.id }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(dateFormat.format(Date(entry.timestampMs)), style = MaterialTheme.typography.labelMedium)
                            Text(entry.command, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.root_command_log_status, entry.status),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            entry.exitCode?.let { exitCode ->
                                Text(
                                    stringResource(R.string.root_command_log_exit_code, exitCode),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.root_command_log_close)) }
        },
    )
}
