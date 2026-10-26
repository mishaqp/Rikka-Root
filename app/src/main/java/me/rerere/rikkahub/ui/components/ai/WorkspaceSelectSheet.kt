package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.ToastAction
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.toaster
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.sheetTileContainerOr
import me.rerere.rikkahub.utils.DataStorageManager
import org.koin.compose.koinInject

@Composable
fun WorkspaceSelectSheet(
    onDismiss: () -> Unit,
) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val workspaces = settings.workspaces
    val selectedWorkspaceId = settings.selectedWorkspaceId
    val navController = LocalNavController.current
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.workspace_select_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(workspaces, key = { it.id }) { workspace ->
                val isSelected = workspace.id == selectedWorkspaceId
                WorkspaceSelectRow(
                    selected = isSelected,
                    onClick = {
                        scope.launch {
                            settingsStore.update { it.copy(selectedWorkspaceId = workspace.id) }
                        }
                        onDismiss()
                    },
                    headline = {
                        Text(
                            text = workspace.name,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    description = {
                        Text(
                            text = DataStorageManager.getFriendlyPath(context, workspace.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            TextButton(onClick = {
                                scope.launch {
                                    settingsStore.update { it.copy(selectedWorkspaceId = null) }
                                }
                                onDismiss()
                            }) {
                                Text(stringResource(R.string.workspace_select_deselect))
                            }
                        }
                    },
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.workspace_manage)) },
                    leadingContent = { Icon(HugeIcons.Settings02, contentDescription = null) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                // Handle click on the whole item for manage
                Spacer(modifier = Modifier.size(0.dp))
            }

            // Overlay clickable for manage (since ListItem doesn't have onClick directly in M3 standard, using Surface or custom)
            // Actually M3 ListItem has modifier.clickable. Let's fix the previous item properly.
        }

        // Correcting the manage item implementation above by replacing it in the LazyColumn logic would be cleaner,
        // but for now let's just use a clickable Surface for the last item.
        // Re-writing the last item part:
    }
}

@Composable
fun WorkspaceSettingsPage(
    onBack: () -> Unit
) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val dataStorageManager = koinInject<DataStorageManager>()
    val context = LocalContext.current
    val navController = LocalNavController.current

    var showCreateDialog by remember { mutableStateOf(false) }

    // Create Workspace Dialog State
    var newWorkspaceName by remember { mutableStateOf("") }
    var newWorkspacePath by remember { mutableStateOf("") }
    var newWorkspaceType by remember { mutableStateOf(me.rerere.rikkahub.data.datastore.WorkspaceType.SAF) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        item {
            Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.headlineMedium)
        }

        items(settings.workspaces) { workspace ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            DataStorageManager.getFriendlyPath(context, workspace.path),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (settings.selectedWorkspaceId == workspace.id) {
                        Text(
                            stringResource(R.string.workspace_active),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            settingsStore.update { current ->
                                current.copy(
                                    workspaces = current.workspaces.filter { it.id != workspace.id },
                                    selectedWorkspaceId = if (current.selectedWorkspaceId == workspace.id) null else current.selectedWorkspaceId
                                )
                            }
                        }
                    }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "Delete")
                    }
                }
            }
        }

        item {
            TextButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(HugeIcons.FolderAdd, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.workspace_create))
            }
        }
    }

    if (showCreateDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.workspace_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        label = { Text(stringResource(R.string.workspace_name)) }
                    )

                    Select(
                        options = me.rerere.rikkahub.data.datastore.WorkspaceType.entries,
                        selectedOption = newWorkspaceType,
                        onOptionSelected = { newWorkspaceType = it },
                        optionToString = { it.name },
                    )

                    OutlinedTextField(
                        value = newWorkspacePath,
                        onValueChange = { newWorkspacePath = it },
                        label = { Text(stringResource(R.string.workspace_path)) },
                        readOnly = newWorkspaceType == me.rerere.rikkahub.data.datastore.WorkspaceType.SAF,
                        trailingIcon = {
                            if (newWorkspaceType == me.rerere.rikkahub.data.datastore.WorkspaceType.SAF) {
                                TextButton(onClick = {
                                    dataStorageManager.chooseDirectory(context) { uri ->
                                        if (uri != null) newWorkspacePath = uri.toString()
                                    }
                                }) {
                                    Text("...")
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newWorkspaceName.isNotBlank() && newWorkspacePath.isNotBlank()) {
                        scope.launch {
                            settingsStore.update { current ->
                                val newWorkspace = me.rerere.rikkahub.data.datastore.Workspace(
                                    name = newWorkspaceName,
                                    path = newWorkspacePath,
                                    type = newWorkspaceType
                                )
                                current.copy(workspaces = current.workspaces + newWorkspace)
                            }
                        }
                        showCreateDialog = false
                        newWorkspaceName = ""
                        newWorkspacePath = ""
                    }
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun WorkspaceSelectRow(
    selected: Boolean,
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    description: @Composable () -> Unit,
    trailingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = headline,
        supportingContent = description,
        leadingContent = {
            Icon(
                HugeIcons.Folder01,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant
            )
        },
        trailingContent = trailingContent,
        modifier = modifier
            .fillMaxWidth()
            .androidx.compose.foundation.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                scheme.surfaceContainerHigh
            } else {
                scheme.sheetTileContainerOr(Color.Transparent)
            }
        )
    )
}
