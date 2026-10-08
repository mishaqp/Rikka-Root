package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RelativeTimeStrings
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowsScreen(vm: WorkflowsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHowItWorks by remember { mutableStateOf(false) }

    if (showHowItWorks) {
        AlertDialog(
            onDismissRequest = { showHowItWorks = false },
            title = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_title)) },
            text = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showHowItWorks = false }) {
                    Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_dismiss))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_workflows)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (workflows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setting_page_workflows_empty),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(workflows, key = { it.entity.id }) { loaded ->
                    WorkflowRow(
                        loaded = loaded,
                        onToggle = { enabled -> vm.setEnabled(loaded.entity.id, enabled) },
                        onTap = { nav.navigate(Screen.WorkflowDetail(loaded.entity.id)) },
                    )
                }
                item {
                    TextButton(
                        onClick = { showHowItWorks = true },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_workflows_how_it_works))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    loaded: Loaded,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    val triggerSummary = remember(loaded.definition) {
        oneLineTriggerSummary(loaded.definition)
    }
    val unavailable = me.rerere.rikkahub.workflow.execution.WorkflowAvailability.triggerReason(loaded.definition.trigger)
        ?: me.rerere.rikkahub.workflow.execution.WorkflowAvailability.conditionReason(loaded.definition.conditions)
    val statusLine: String = when {
        loaded.entity.lastRunAtMs == null -> stringResource(R.string.setting_page_workflows_subtitle_never_run)
        else -> {
            val ago = formatRelativeAgo(loaded.entity.lastRunAtMs, nowMs, rel)
            when (loaded.entity.lastRunStatus) {
                WorkflowRunStatus.SUCCESS.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_success, ago)
                WorkflowRunStatus.FAILED.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_failed, ago)
                else ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_skipped, ago)
            }
        }
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(loaded.entity.name) },
        supportingContent = {
            Text(
                text = "${stringResource(R.string.setting_page_workflows_subtitle_when, triggerSummary)}\n${unavailable ?: statusLine}",
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(checked = loaded.entity.enabled, onCheckedChange = onToggle, enabled = unavailable == null || loaded.entity.enabled)
        },
    )
    HorizontalDivider()
}

internal fun oneLineTriggerSummary(def: WorkflowDefinition): String = when (val t = def.trigger) {
    is TriggerSpec.TimeCron ->
        if (!t.timeOfDay.isNullOrBlank()) "в ${t.timeOfDay}" else "расписание"
    is TriggerSpec.WifiConnected -> "WiFi подключён" + (t.ssid?.let { " к $it" }.orEmpty())
    is TriggerSpec.WifiDisconnected -> "WiFi отключён" + (t.ssid?.let { " от $it" }.orEmpty())
    is TriggerSpec.BluetoothDeviceConnected -> "Bluetooth подключён"
    is TriggerSpec.BluetoothDeviceDisconnected -> "Bluetooth отключён"
    is TriggerSpec.HeadphonesPlugged -> "наушники подключены"
    is TriggerSpec.HeadphonesUnplugged -> "наушники отключены"
    is TriggerSpec.PowerConnected -> "зарядное устройство подключено"
    is TriggerSpec.PowerDisconnected -> "зарядное устройство отключено"
    is TriggerSpec.BatteryBelow -> "батарея < ${t.thresholdPercent}%"
    is TriggerSpec.BatteryAbove -> "батарея > ${t.thresholdPercent}%"
    is TriggerSpec.GeofenceEnter -> "вход в геозону ${t.label ?: "место"}"
    is TriggerSpec.GeofenceExit -> "выход из геозоны ${t.label ?: "место"}"
    is TriggerSpec.AppLaunched -> "${t.packageName} запущено"
    is TriggerSpec.AppClosed -> "${t.packageName} закрыто"
    is TriggerSpec.NotificationReceived -> "уведомление${t.packageName?.let { " от $it" } ?: ""}"
    is TriggerSpec.BootCompleted -> "загрузка устройства"
    is TriggerSpec.ScreenOn -> "экран включён"
    is TriggerSpec.ScreenOff -> "экран выключен"
    is TriggerSpec.Manual -> "только ручной запуск"
}

@Composable
internal fun relativeStrings(): RelativeTimeStrings = RelativeTimeStrings(
    justNow = stringResource(R.string.relative_time_just_now),
    secondsAgo = stringResource(R.string.relative_time_seconds_ago),
    minutesAgo = stringResource(R.string.relative_time_minutes_ago),
    hoursAgo = stringResource(R.string.relative_time_hours_ago),
    daysAgo = stringResource(R.string.relative_time_days_ago),
)

/**
 * A [State<Long>] of the current wall-clock millis, refreshed every 30s while the calling
 * Composable is in the composition. Used to keep "ran 2m ago" subtitles fresh without
 * fully re-deriving every recomposition. The audit found the old `remember { now }` pattern
 * silently froze the relative-time at the moment the row first composed.
 */
@Composable
internal fun rememberTickingNowMs(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return state
}

internal fun workflowStatusLabel(status: String): String = when (status) {
    "SUCCESS" -> "успешно"
    "FAILED" -> "ошибка"
    "SKIPPED_CONDITIONS" -> "условия не выполнены"
    "SKIPPED_COOLDOWN" -> "пауза между запусками"
    "SKIPPED_DAILY_CAP" -> "дневной лимит"
    "SKIPPED_DISABLED" -> "сценарий выключен"
    else -> status
}
