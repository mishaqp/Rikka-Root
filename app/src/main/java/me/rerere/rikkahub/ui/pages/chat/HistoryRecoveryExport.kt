package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/** A raw database/files archive rescues rows which the message cursor could not load. */
@Composable
internal fun HistoryRecoveryExport() {
    val backupManager: BackupManager = koinInject()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val archive = backupManager.createBackup(includeDatabase = true, includeFiles = true)
                    try {
                        checkNotNull(context.contentResolver.openOutputStream(uri)) { "Не удалось открыть файл резервной копии" }
                            .use { output -> archive.inputStream().use { it.copyTo(output) } }
                    } finally { archive.delete() }
                }
                toaster.show(context.getString(R.string.backup_page_backup_success), type = ToastType.Success)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toaster.show(error.message ?: context.getString(R.string.chat_page_history_backup_error), type = ToastType.Error)
            } finally { exporting = false }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.chat_page_history_read_only), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showExport = true }, enabled = !exporting) {
                Text(stringResource(R.string.chat_page_history_export))
            }
        }
    }
    if (showExport) AlertDialog(
        onDismissRequest = { showExport = false },
        title = { Text(stringResource(R.string.chat_page_history_backup_title)) },
        text = { Text(stringResource(R.string.chat_page_history_backup_description)) },
        confirmButton = {
            TextButton(onClick = { showExport = false; launcher.launch("rikka_history_backup.zip") }) {
                Text(stringResource(R.string.chat_page_history_backup_save))
            }
        },
        dismissButton = { TextButton(onClick = { showExport = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
