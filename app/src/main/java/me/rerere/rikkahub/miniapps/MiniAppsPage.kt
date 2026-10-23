package me.rerere.rikkahub.miniapps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

/** Moru's "My apps" catalog expressed in the fork's existing Material UI. */
@Composable
fun MiniAppsPage(store: MiniAppStore = koinInject()) {
    val apps by store.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = apps.firstOrNull { it.id == selectedId }

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        message = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: resources.getString(R.string.mini_apps_operation_failed) }
            finally { busy = false }
        }
    }

    fun share(app: MiniApp, withData: Boolean) {
        runAction { MiniAppLauncher.share(context, app, MiniAppLauncher.exportUri(context, store, app, withData)) }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            val imported = MiniAppLauncher.importUri(context, uri, store)
            MiniAppLauncher.open(context, imported.app.id)
        }
    }

    LaunchedEffect(store) {
        try { store.load(); loaded = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: resources.getString(R.string.mini_apps_operation_failed); loaded = true }
    }

    MiniAppsPageContent(apps, loaded, busy, error, message,
        onImport = { importer.launch(arrayOf("*/*")) },
        onOpen = { app -> runAction { MiniAppLauncher.open(context, app.id) } },
        onActions = { app -> selectedId = app.id; dialog = "actions" },
    )

    if (selected != null && dialog != null) {
        when (dialog) {
            "actions" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(selected.name) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { dialog = null; runAction { MiniAppLauncher.open(context, selected.id) } }) { Text(stringResource(R.string.mini_apps_open)) }
                        TextButton(onClick = {
                            dialog = null
                            runAction {
                                message = resources.getString(if (MiniAppLauncher.pin(context, selected)) R.string.mini_apps_pin_requested else R.string.mini_apps_pin_unsupported)
                            }
                        }) { Text(stringResource(R.string.mini_apps_pin)) }
                        TextButton(onClick = {
                            dialog = null
                            runAction {
                                if (store.storageKeys(selected.id).isNotEmpty()) dialog = "share"
                                else MiniAppLauncher.share(context, selected, MiniAppLauncher.exportUri(context, store, selected, false))
                            }
                        }) { Text(stringResource(R.string.mini_apps_share)) }
                        TextButton(onClick = { dialog = "delete" }) { Text(stringResource(R.string.mini_apps_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }, confirmButton = {}, dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) }
                })
            "delete" -> AlertDialog(onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.mini_apps_delete_title, selected.name)) },
                text = { Text(stringResource(R.string.mini_apps_delete_detail)) },
                confirmButton = { TextButton(onClick = { dialog = null; runAction { store.delete(selected.id) } }) {
                    Text(stringResource(R.string.mini_apps_delete), color = MaterialTheme.colorScheme.error)
                } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
            "share" -> AlertDialog(onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.mini_apps_share)) },
                text = { Column {
                    Text(stringResource(R.string.mini_apps_share_detail))
                    TextButton(onClick = { dialog = null; share(selected, false) }) { Text(stringResource(R.string.mini_apps_share_code)) }
                    TextButton(onClick = { dialog = null; share(selected, true) }) { Text(stringResource(R.string.mini_apps_share_data)) }
                } }, confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}

@Composable
internal fun MiniAppsPageContent(
    apps: List<MiniApp>, loaded: Boolean, busy: Boolean, error: String?, message: String?,
    onImport: () -> Unit, onOpen: (MiniApp) -> Unit, onActions: (MiniApp) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mini_apps_title)) }, navigationIcon = { BackButton() },
            actions = { IconButton(onClick = onImport, enabled = !busy) { Icon(HugeIcons.Add01, stringResource(R.string.mini_apps_import)) } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!loaded || busy) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item("error") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (message != null) item("message") { Text(message, modifier = Modifier.padding(12.dp)) }
            if (loaded && apps.isEmpty()) item("empty") {
                Text(stringResource(R.string.mini_apps_empty), Modifier.padding(vertical = 32.dp, horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(apps, key = { it.id }) { app ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(headlineContent = { Text(app.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = app.description.takeIf { it.isNotEmpty() }?.let { description -> {
                            Text(description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        } }, leadingContent = { MiniAppIcon(app) }, trailingContent = {
                            IconButton(onClick = { onActions(app) }, enabled = !busy) { Icon(HugeIcons.MoreVertical, stringResource(R.string.mini_apps_actions, app.name)) }
                        }, modifier = Modifier.combinedClickable(enabled = !busy, onClick = { onOpen(app) }, onLongClick = { onActions(app) }),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                }
            }
            item("footer") { Text(stringResource(R.string.mini_apps_footer), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MiniAppIcon(app: MiniApp) {
    var failed by remember(app.id, app.updatedAt) { mutableStateOf(false) }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (app.iconFile == null || failed) Text(miniAppInitial(app.name), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
        else AsyncImage(app.iconFile, contentDescription = null, modifier = Modifier.fillMaxSize(), onError = { failed = true })
    }
}
