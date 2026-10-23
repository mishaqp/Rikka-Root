package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.getActivity
import org.koin.compose.koinInject

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
    var pendingExport by rememberSaveable(stateSaver = producedFileExportSaver) {
        mutableStateOf<ProducedFileExportRequest?>(null)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (request == null) {
            Toast.makeText(context, R.string.chat_produced_file_export_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                context.exportProducedFile(request, uri, workspaceRepository)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.chat_produced_file_export_failed, Toast.LENGTH_LONG).show()
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
                        scope.launch {
                            try {
                                context.requireReadableProducedDocument(entry.source)
                                context.openLocalFile(entry.source.uri, entry.source.mime)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.chat_attachment_file_unavailable, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                FileActionCard(stringResource(R.string.common_export), Modifier.testTag("chat_produced_file_export"),
                    icon = { Icon(HugeIcons.FileImport, contentDescription = null) }) {
                    pendingExport = ProducedFileExportRequest(entry.source, workspaceId)
                    try {
                        exportLauncher.launch(entry.displayName)
                    } catch (_: Exception) {
                        pendingExport = null
                        Toast.makeText(context, R.string.chat_produced_file_export_failed, Toast.LENGTH_LONG).show()
                    }
                }
                FileActionCard(stringResource(R.string.common_share), Modifier.testTag("chat_produced_file_share"),
                    icon = { Icon(HugeIcons.Share08, contentDescription = null) }) {
                    selected = null
                    scope.launch {
                        try {
                            val intent = context.createProducedFileShareIntent(entry, workspaceId, workspaceRepository)
                            val chooser = Intent.createChooser(intent, null).apply {
                                if (context.getActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(chooser)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.chat_produced_file_share_failed, Toast.LENGTH_LONG).show()
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

// Android may recreate this composition while its document picker remains in the foreground.
private val producedFileExportSaver = listSaver<ProducedFileExportRequest?, String>(
    save = { request ->
        if (request == null) emptyList() else when (val source = request.source) {
            is ProducedFileSource.Workspace -> listOf("workspace", source.path, "", request.workspaceId.orEmpty())
            is ProducedFileSource.Document -> listOf("document", source.uri, source.mime, request.workspaceId.orEmpty())
        }
    },
    restore = { saved ->
        val source = when (saved[0]) {
            "workspace" -> ProducedFileSource.Workspace(saved[1])
            else -> ProducedFileSource.Document(saved[1], saved[2])
        }
        ProducedFileExportRequest(source, saved[3].takeIf(String::isNotEmpty))
    },
)
