package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingPermissionsAccessPage() {
    val context = LocalContext.current
    val application = remember(context) { context.applicationContext }
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val root = koinInject<RootShellManager>()
    val rootStatus by root.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var revision by remember { mutableIntStateOf(0) }
    var actionBusy by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val snapshot by produceState(PermissionsAccessSnapshot(), application, rootStatus, revision) {
        value = try { loadPermissionsAccess(application, rootStatus) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PermissionsAccessSnapshot(loading = false, failed = true) }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val openAndroid: (List<Intent>) -> Unit = { intents ->
        var opened = false
        for (intent in intents) {
            try { settingsLauncher.launch(intent); opened = true; break }
            catch (_: Exception) { /* Some manufacturers omit a particular settings screen. */ }
        }
        if (!opened) toaster.show("Android не открыл настройки доступа. Откройте сведения о Rikka-Root в настройках телефона.", type = ToastType.Warning)
    }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        revision++
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toaster.show("Часть разрешений не выдана. Их можно выдать отдельно ниже или в настройках Android.", type = ToastType.Warning)
            val activity = context.findPermissionsActivity()
            // A blocked/repeated request can return immediately without showing a dialog.
            if (activity != null && denied.all { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
                openAndroid(listOf(appPermissionSettingsIntent(application.packageName)))
            }
        }
    }
    val performAction: (suspend () -> String?) -> Unit = { action ->
        if (!actionBusy) {
            actionBusy = true
            scope.launch {
                try { action()?.let { toaster.show(it, type = ToastType.Warning) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toaster.show("Не удалось изменить доступ. Вернитесь на экран и повторите действие.", type = ToastType.Warning) }
                finally { actionBusy = false; revision++ }
            }
        }
    }
    PermissionsAccessPageContent(
        snapshot = snapshot,
        allStorageEnabled = settings.fileToolsAllStorage,
        rootStatus = rootStatus,
        busy = actionBusy,
        onRefresh = { revision++ },
        onGrantAll = {
            if (snapshot.runtimeRequest.isNotEmpty()) runtimeLauncher.launch(snapshot.runtimeRequest.toTypedArray())
            else toaster.show("Все доступные обычные разрешения уже выданы.", type = ToastType.Success)
        },
        onGrant = { row ->
            when (row.entry.kind) {
                PermissionAccessKind.Root -> performAction { root.verifyRoot(force = true); null }
                PermissionAccessKind.Runtime -> {
                    val remaining = row.entry.permissions.filter { it in snapshot.runtimeRequest }
                    if (remaining.isEmpty()) openAndroid(permissionSettingsIntents(row.entry, application.packageName))
                    else runtimeLauncher.launch(remaining.toTypedArray())
                }
                PermissionAccessKind.Special -> openAndroid(permissionSettingsIntents(row.entry, application.packageName))
                PermissionAccessKind.Automatic -> Unit
            }
        },
        onGrantRoot = { row -> performAction {
            val commands = permissionRootGrantCommands(row.entry, application.packageName,
                android.os.Process.myUid() / 100_000, Build.VERSION.SDK_INT)
            if (commands.isEmpty()) return@performAction "Этот доступ выдаётся в настройках Android."
            for (command in commands) {
                val result = root.exec(command, 20_000, mayExecute = { RootCommandGuard.check(command) == null })
                if (result.error != null || result.exitCode != 0) {
                    return@performAction "Android не выдал этот доступ через root. Часть разрешений могла быть выдана; проверьте статус и используйте кнопку «Выдать»."
                }
            }
            // The command's exit status is not a substitute for checking the actual Android grant.
            val refreshed = loadPermissionsAccess(application, root.status.value)
            if (refreshed.rows.firstOrNull { it.entry.id == row.entry.id }?.status != PermissionAccessStatus.Granted)
                "Android не подтвердил доступ. Выдайте его кнопкой «Выдать» и проверьте системные ограничения."
            else null
        } },
        onAllStorageChange = { enabled -> performAction { store.update { it.copy(fileToolsAllStorage = enabled) }; null } },
        onAddFolder = { performAction { addPermissionAccessFolder(application) } },
        onRevokeFolder = { folder -> performAction { revokePermissionAccessFolder(application, folder.uri) } },
    )
}

private fun android.content.Context.findPermissionsActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.takeUnless { it === this }?.findPermissionsActivity()
    else -> null
}

/** Actual screen content with external requests supplied by the Activity host. */
@Composable
internal fun PermissionsAccessPageContent(
    snapshot: PermissionsAccessSnapshot,
    allStorageEnabled: Boolean,
    rootStatus: RootStatus,
    busy: Boolean,
    onRefresh: () -> Unit,
    onGrantAll: () -> Unit,
    onGrant: (PermissionAccessRow) -> Unit,
    onGrantRoot: (PermissionAccessRow) -> Unit,
    onAllStorageChange: (Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onRevokeFolder: (SafAccessFolder) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val allFiles = snapshot.rows.firstOrNull { it.entry.id == "all_files" }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(title = { Text("Разрешения и доступ", Modifier.fillMaxWidth()) }, navigationIcon = { BackButton() },
                scrollBehavior = scroll, colors = CustomColors.topBarColors)
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("permissions_access_list"),
            contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выдайте доступ заранее. Одобрения вызовов инструментов в чате сохраняются.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onGrantAll, enabled = !busy && !snapshot.loading && !snapshot.failed,
                        modifier = Modifier.fillMaxWidth().testTag("permissions_grant_all")) {
                        Text("Выдать все обычные разрешения", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
            if (snapshot.loading) item("loading") { Text("Проверяю разрешения…") }
            if (snapshot.failed) item("failed") {
                Column {
                    Text("Не удалось прочитать доступы Android. Повторите проверку.")
                    TextButton(onClick = onRefresh) { Text("Обновить") }
                }
            }
            item("files_access") {
                PermissionAccessCard {
                    Text("Файлы телефона", style = MaterialTheme.typography.titleMedium)
                    allFiles?.let { PermissionAccessRowContent(it, rootStatus, busy, onGrant, onGrantRoot) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Файловые инструменты: вся память телефона", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                            Text(if (allStorageEnabled && allFiles?.status == PermissionAccessStatus.Granted)
                                "Включено: общая память доступна напрямую. Защищённые папки закрыты."
                            else if (allStorageEnabled) "Переключатель включён, но Android ещё не выдал доступ ко всем файлам."
                            else "Выключено: доступны только выбранные папки.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = allStorageEnabled, onCheckedChange = onAllStorageChange,
                            enabled = !busy && allFiles != null && allFiles.status != PermissionAccessStatus.Unavailable && !snapshot.loading,
                            modifier = Modifier.testTag("permissions_all_storage_switch").semantics {
                                contentDescription = "Файловые инструменты: вся память телефона"
                            })
                    }
                }
            }
            item("folders_header") {
                PermissionAccessCard(Modifier.testTag("permissions_folders")) {
                    Text("Папки с доступом", style = MaterialTheme.typography.titleMedium)
                    Text("Android не даёт выбрать саму Download или корень памяти — создайте подпапку, например Download/Rikka.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onAddFolder, enabled = !busy, modifier = Modifier.testTag("permissions_add_folder")) {
                        Text("Добавить папку")
                    }
                    if (!snapshot.loading && snapshot.folders.isEmpty()) Text("Выданных папок пока нет.")
                    snapshot.folders.forEach { folder ->
                        Column(Modifier.fillMaxWidth().testTag("permissions_folder_${folder.uri}")) {
                            Text(folder.path, style = MaterialTheme.typography.bodyMedium)
                            Text(if (folder.writable) "Чтение и запись" else "Только чтение", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onRevokeFolder(folder) }, enabled = !busy) { Text("Отозвать") }
                        }
                    }
                }
            }
            listOf(PermissionAccessKind.Root to "Root-доступ",
                PermissionAccessKind.Runtime to "Обычные разрешения",
                PermissionAccessKind.Special to "Особый доступ",
                PermissionAccessKind.Automatic to "Выдаются при установке").forEach { (kind, title) ->
                item("section_$kind") { Text(title, Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(snapshot.rows.filter { it.entry.kind == kind && it.entry.id != "all_files" }, key = { it.entry.id }) { row ->
                    PermissionAccessCard(Modifier.testTag("permissions_${row.entry.id}")) {
                        PermissionAccessRowContent(row, rootStatus, busy, onGrant, onGrantRoot)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionAccessCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun PermissionAccessRowContent(row: PermissionAccessRow, rootStatus: RootStatus, busy: Boolean,
    onGrant: (PermissionAccessRow) -> Unit, onGrantRoot: (PermissionAccessRow) -> Unit) {
    Text(row.entry.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    Text(row.entry.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if (row.status == PermissionAccessStatus.Unavailable && row.entry.id == "termux") "Недоступно: Termux не установлен" else row.status.text,
        style = MaterialTheme.typography.labelLarge,
        color = if (row.status == PermissionAccessStatus.Granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if ((row.status == PermissionAccessStatus.Denied && row.entry.kind != PermissionAccessKind.Automatic) ||
        row.entry.kind == PermissionAccessKind.Root) {
        TextButton(onClick = { onGrant(row) }, enabled = !busy && rootStatus != RootStatus.CHECKING,
            modifier = Modifier.testTag("permissions_grant_${row.entry.id}")) {
            Text(if (row.entry.kind == PermissionAccessKind.Root) {
                if (row.status == PermissionAccessStatus.Granted) "Проверить root повторно" else "Проверить и разрешить root"
            } else "Выдать")
        }
        if (rootStatus == RootStatus.READY && permissionRootGrantCommands(row.entry, "me.mishaqp.rikkaroot", 0, Build.VERSION.SDK_INT).isNotEmpty()) {
            TextButton(onClick = { onGrantRoot(row) }, enabled = !busy,
                modifier = Modifier.testTag("permissions_root_${row.entry.id}")) { Text("Выдать через root") }
        }
    }
}
