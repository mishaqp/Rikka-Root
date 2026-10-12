package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

private const val DEFAULT_VISIBLE_COUNT = 12

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(parts: List<UIMessagePart>, assistant: Assistant?) {
    val entries = remember(parts) { presentProducedFiles(parts) }
    val workspaceId = assistant?.workspaceId?.toString()
    val files = entries.filter { it.source !is ProducedFileSource.Workspace || workspaceId != null }
    if (files.isEmpty()) return

    val context = LocalContext.current
    val sheetBodyMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    val scope = rememberCoroutineScope()
    val workspaceRepository: WorkspaceRepository = koinInject()
    var selected by remember { mutableStateOf<ProducedFilePresentation?>(null) }
    var showAll by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = selected ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    when (val source = entry.source) {
                        is ProducedFileSource.Workspace -> {
                            val (area, relativePath) = resolveWorkspacePath(source.path)
                            workspaceRepository.exportFile(workspaceId ?: return@use, area, relativePath, output)
                        }
                        is ProducedFileSource.Document -> {
                            val inputUri = Uri.parse(source.uri)
                            context.contentResolver.openInputStream(inputUri)?.use { it.copyTo(output) }
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.testTag("chat_produced_files_row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.chat_reasoning_files_title, files.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            files.take(DEFAULT_VISIBLE_COUNT).forEach { entry ->
                if (entry.source is ProducedFileSource.Document && entry.source.mime.startsWith("image/")) {
                    Surface(
                        onClick = { selected = entry },
                        modifier = Modifier.size(56.dp).testTag("chat_produced_file_chip"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        AsyncImage(model = entry.source.uri, contentDescription = entry.displayName,
                            contentScale = ContentScale.Crop)
                    }
                } else {
                    ProducedFileChip(entry.displayName, Modifier.testTag("chat_produced_file_chip")) {
                        selected = entry
                    }
                }
            }
            if (files.size > DEFAULT_VISIBLE_COUNT) {
                ProducedFileChip(stringResource(R.string.chat_reasoning_files_more, files.size - DEFAULT_VISIBLE_COUNT),
                    Modifier.testTag("chat_produced_files_more")) { showAll = true }
            }
        }
    }

    if (showAll) {
        ModalBottomSheet(onDismissRequest = { showAll = false }) {
            Text(stringResource(R.string.chat_reasoning_files_all),
                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetBodyMaxHeight)
                    .testTag("chat_produced_files_all_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.identity }) { entry ->
                    Card(onClick = { showAll = false; selected = entry }, modifier = Modifier.fillMaxWidth()) {
                        Text(entry.path, modifier = Modifier.padding(12.dp)
                            .testTag("chat_produced_files_all_item"),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            modifier = Modifier.testTag("chat_produced_file_actions"),
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = sheetBodyMaxHeight)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.path, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.source is ProducedFileSource.Document) {
                    FileActionCard(stringResource(R.string.chat_reasoning_files_open), Modifier.testTag("chat_produced_file_open")) {
                        runCatching { context.startActivity(documentIntent(context, entry.source, Intent.ACTION_VIEW)) }
                    }
                }
                FileActionCard(stringResource(R.string.common_export), Modifier.testTag("chat_produced_file_export"),
                    icon = { Icon(HugeIcons.FileImport, contentDescription = null) }) {
                    exportLauncher.launch(entry.displayName)
                }
                FileActionCard(stringResource(R.string.common_share), Modifier.testTag("chat_produced_file_share"),
                    icon = { Icon(HugeIcons.Share08, contentDescription = null) }) {
                    selected = null
                    scope.launch {
                        runCatching {
                            val intent = when (val source = entry.source) {
                                is ProducedFileSource.Document -> documentIntent(context, source, Intent.ACTION_SEND)
                                is ProducedFileSource.Workspace -> {
                                    val id = workspaceId ?: return@runCatching
                                    val (area, relativePath) = resolveWorkspacePath(source.path)
                                    val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                    val file = File(dir, entry.displayName)
                                    file.outputStream().use { workspaceRepository.exportFile(id, area, relativePath, it) }
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                }
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProducedFileChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Icon(HugeIcons.File02, contentDescription = null, modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FileActionCard(
    label: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            icon?.invoke()
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun documentIntent(context: android.content.Context, source: ProducedFileSource.Document, action: String): Intent {
    val parsed = Uri.parse(source.uri)
    val uri = if (parsed.scheme == "file") {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(parsed.path ?: ""))
    } else parsed
    return Intent(action).apply {
        type = source.mime.ifBlank { "application/octet-stream" }
        if (action == Intent.ACTION_VIEW) setDataAndType(uri, type)
        else putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun resolveWorkspacePath(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}
