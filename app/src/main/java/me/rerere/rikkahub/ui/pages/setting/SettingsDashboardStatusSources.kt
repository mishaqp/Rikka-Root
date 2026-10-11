package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.runtime.SettingsDashboardRuntimeStatusStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.Koin
import org.koin.java.KoinJavaComponent.getKoin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI-only observations: never verify root, launch Termux, read cookies, or request permissions. */
@Composable
fun rememberSettingsDashboardStatuses(settings: Settings): Map<SettingsDashboardStatusKey, SettingsDashboardStatus> {
    val context = LocalContext.current.applicationContext
    val koin = remember { getKoin() }
    val currentSettings = rememberUpdatedState(settings)
    val settingsFlow = remember { snapshotFlow { currentSettings.value }.filter { !it.init } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshes = remember(lifecycleOwner) { MutableStateFlow(0L) }

    DisposableEffect(lifecycleOwner, refreshes) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshes.update { it + 1 }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sources = remember(context, koin, settingsFlow, refreshes) {
        SettingsDashboardStatusSources(context, koin, settingsFlow, refreshes).flows()
    }
    return rememberSettingsDashboardStatuses(sources)
}

/** Shared collector also allows the UI's placeholder behavior to be checked with suspended sources. */
@Composable
internal fun rememberSettingsDashboardStatuses(
    sources: Map<SettingsDashboardStatusKey, Flow<SettingsDashboardStatus>>,
): Map<SettingsDashboardStatusKey, SettingsDashboardStatus> {
    val statusesFlow = remember(sources) { observeSettingsDashboardStatuses(sources) }
    val statuses by statusesFlow.collectAsStateWithLifecycle(initialValue = settingsDashboardInitialStatuses())
    return statuses
}

private class SettingsDashboardStatusSources(
    private val context: Context,
    private val koin: Koin,
    private val settings: Flow<Settings>,
    private val refreshes: Flow<Long>,
) {
    fun flows(): Map<SettingsDashboardStatusKey, Flow<SettingsDashboardStatus>> = mapOf(
        SettingsDashboardStatusKey.Root to ioFlow {
            // RootAccessStore is audit/legacy permissions; the last explicit probe lives here.
            emitAll(koin.get<RootShellManager>().status.map { status ->
                when (status) {
                    RootStatus.UNCHECKED -> ready("Не проверен")
                    RootStatus.CHECKING -> SettingsDashboardStatus.Loading
                    RootStatus.READY -> ready("Активен", SettingsDashboardStatusTone.Success)
                    else -> ready("Нет доступа", SettingsDashboardStatusTone.Warning)
                }
            })
        },
        SettingsDashboardStatusKey.Termux to ioFlow {
            emitAll(refreshes.map {
                when (TermuxIntegration.state(context)) {
                    TermuxIntegration.State.NOT_INSTALLED -> ready("Не установлен")
                    TermuxIntegration.State.NO_PERMISSION -> ready("Нет разрешения", SettingsDashboardStatusTone.Warning)
                    TermuxIntegration.State.READY -> ready("Готов", SettingsDashboardStatusTone.Success)
                }
            })
        },
        SettingsDashboardStatusKey.Ssh to ioFlow {
            val database = koin.get<AppDatabase>()
            val repository = koin.get<SshHostRepository>()
            emitAll(databaseChanges(database, "ssh_hosts").map {
                ready("${repository.getAll().size} хостов")
            })
        },
        SettingsDashboardStatusKey.Mcp to ioFlow {
            emitAll(combine(
                settings.map { value -> value.mcpServers.filter { it.commonOptions.enable }.map { it.id }.toSet() }
                    .distinctUntilChanged(),
                SettingsDashboardRuntimeStatusStore.shared.observeMcpStatus(),
            ) { enabledIds, statuses ->
                if (statuses == null) return@combine SettingsDashboardStatus.Ready()
                val enabledStatuses = enabledIds.mapNotNull(statuses::get)
                val connected = enabledStatuses.count { it == McpStatus.Connected }
                val hasError = enabledStatuses.any { it is McpStatus.Error }
                val needsAuthorization = enabledStatuses.any { it == McpStatus.NeedsAuthorization }
                val suffix = when {
                    hasError -> " · ошибка"
                    needsAuthorization -> " · нужен вход"
                    else -> ""
                }
                val tone = when {
                    hasError -> SettingsDashboardStatusTone.Error
                    needsAuthorization -> SettingsDashboardStatusTone.Warning
                    connected > 0 -> SettingsDashboardStatusTone.Success
                    else -> SettingsDashboardStatusTone.Neutral
                }
                ready("$connected подключено$suffix", tone)
            })
        },
        SettingsDashboardStatusKey.Workflows to ioFlow {
            emitAll(koin.get<WorkflowRepository>().observeAll().map { workflows ->
                ready("${workflows.count { it.definition.enabled }} активных")
            })
        },
        SettingsDashboardStatusKey.Cron to ioFlow {
            val database = koin.get<AppDatabase>()
            val repository = koin.get<ScheduledJobRepository>()
            emitAll(databaseChanges(database, "scheduled_jobs").map {
                // The scheduler persists nextRunAtMs; do not invent or rearm schedules for a badge.
                ready("${repository.active().count { it.nextRunAtMs != null }} в расписании")
            })
        },
        // A visited domain or cookie presence does not reliably establish a signed-in session.
        SettingsDashboardStatusKey.Browser to flowOf(SettingsDashboardStatus.Ready()),
        SettingsDashboardStatusKey.Web to ioFlow {
            emitAll(SettingsDashboardRuntimeStatusStore.shared.observeWebStatus().map { state ->
                when {
                    state == null -> SettingsDashboardStatus.Ready()
                    state.isLoading -> SettingsDashboardStatus.Loading
                    state.error != null -> SettingsDashboardStatus.Error
                    state.isRunning -> ready("Включён", SettingsDashboardStatusTone.Success)
                    else -> SettingsDashboardStatus.Ready()
                }
            })
        },
        SettingsDashboardStatusKey.Approvals to ioFlow {
            emitAll(koin.get<ToolApprovalPreferences>().globalYoloFlow.map { enabled ->
                if (enabled) ready("Я ГЛУПЫЙ", SettingsDashboardStatusTone.Warning)
                else SettingsDashboardStatus.Ready()
            })
        },
        SettingsDashboardStatusKey.Storage to ioFlow {
            val database = koin.get<AppDatabase>()
            val manager = koin.get<FilesManager>()
            emitAll(databaseChanges(database, "managed_files").map {
                val (count, size) = manager.countChatFiles()
                ready("Файлов: $count · ${size.fileSizeToString()}")
            })
        },
        SettingsDashboardStatusKey.Backup to ioFlow {
            emitAll(settings.map { it.backupReminderConfig.lastBackupTime }.distinctUntilChanged().map { timestamp ->
                // Existing metadata records a prepared local archive, or a completed cloud upload.
                if (timestamp > 0L) ready(SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp)))
                else ready("Нет данных")
            })
        },
    )

    private fun databaseChanges(database: AppDatabase, table: String): Flow<Unit> = merge(
        database.invalidationTracker.createFlow(table).map { Unit },
        refreshes.map { Unit },
    ).conflate()

    /** Defer dependency resolution too: some existing singleton constructors read from disk. */
    private fun ioFlow(block: suspend kotlinx.coroutines.flow.FlowCollector<SettingsDashboardStatus>.() -> Unit):
        Flow<SettingsDashboardStatus> = flow(block).flowOn(Dispatchers.IO).distinctUntilChanged()

    private fun ready(text: String, tone: SettingsDashboardStatusTone = SettingsDashboardStatusTone.Neutral) =
        SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(text, tone))
}
