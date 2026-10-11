package me.rerere.rikkahub.ui.pages.setting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

enum class SettingsDashboardStatusKey {
    Root, Termux, Ssh, Mcp, Workflows, Cron, Browser, Web, Approvals, Storage, Backup,
}

enum class SettingsDashboardStatusTone { Neutral, Success, Warning, Error }

data class SettingsDashboardStatusChip(
    val text: String,
    val tone: SettingsDashboardStatusTone = SettingsDashboardStatusTone.Neutral,
)

sealed interface SettingsDashboardStatus {
    data object Loading : SettingsDashboardStatus
    data class Ready(val chip: SettingsDashboardStatusChip? = null) : SettingsDashboardStatus
    data object Error : SettingsDashboardStatus
}

internal fun settingsDashboardInitialStatuses(): Map<SettingsDashboardStatusKey, SettingsDashboardStatus> =
    SettingsDashboardStatusKey.entries.associateWith { SettingsDashboardStatus.Loading }

/** Each source publishes immediately; a slow or failed source affects only its own cell. */
internal fun observeSettingsDashboardStatuses(
    sources: Map<SettingsDashboardStatusKey, Flow<SettingsDashboardStatus>>,
): Flow<Map<SettingsDashboardStatusKey, SettingsDashboardStatus>> = flow {
    val statuses = MutableStateFlow(settingsDashboardInitialStatuses())
    // Publish before starting any source, including hot flows that already have a value.
    emit(statuses.value)
    supervisorScope {
        sources.forEach { (key, source) ->
            launch {
                try {
                    source.collect { status -> statuses.update { it + (key to status) } }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Exception text may contain endpoints or credentials; never put it in the UI.
                    statuses.update { it + (key to SettingsDashboardStatus.Error) }
                }
            }
        }
        statuses.collect { emit(it) }
    }
}.distinctUntilChanged()
