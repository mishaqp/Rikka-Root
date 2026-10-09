package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.service.scheduling.ExactAlarmAccess
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

internal data class ScheduleExecutionSettingsState(
    val exactAlarmsAllowed: Boolean,
    val batteryOptimizationsIgnored: Boolean,
    val rootBusy: Boolean,
    val requestExactAlarms: () -> Unit,
    val requestBatteryOptimizationExemption: () -> Unit,
    val whitelistWithRoot: () -> Unit,
)

/** Queries access freely, but every Android/root access change starts with a user action. */
@Composable
internal fun rememberScheduleExecutionSettings(): ScheduleExecutionSettingsState {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val root = koinInject<RootShellManager>()
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var pendingExactRequest by rememberSaveable { mutableStateOf(false) }
    var rootBusy by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }

    val exactAllowed = remember(context, revision) { ExactAlarmAccess.canSchedule(context) }
    val batteryIgnored = remember(context, revision) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val exactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val wasPending = pendingExactRequest
        pendingExactRequest = false
        revision++
        if (wasPending && !ExactAlarmAccess.canSchedule(context)) {
            toaster.show(resources.getString(R.string.schedule_execution_exact_denied), type = ToastType.Warning)
        }
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        if (context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) != true) {
            toaster.show(resources.getString(R.string.schedule_execution_battery_denied), type = ToastType.Warning)
        }
    }

    return ScheduleExecutionSettingsState(
        exactAlarmsAllowed = exactAllowed,
        batteryOptimizationsIgnored = batteryIgnored,
        rootBusy = rootBusy,
        requestExactAlarms = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !pendingExactRequest && !ExactAlarmAccess.canSchedule(context)) {
                toaster.show(resources.getString(R.string.schedule_execution_exact_rationale), type = ToastType.Warning)
                pendingExactRequest = true
                try {
                    exactLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    pendingExactRequest = false
                    toaster.show(resources.getString(R.string.schedule_execution_exact_settings_unavailable), type = ToastType.Warning)
                }
            }
        },
        requestBatteryOptimizationExemption = {
            try {
                batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                toaster.show(resources.getString(R.string.schedule_execution_battery_settings_unavailable), type = ToastType.Warning)
            }
        },
        whitelistWithRoot = {
            if (!rootBusy) {
                rootBusy = true
                scope.launch {
                    try {
                        val result = addScheduleToDozeWhitelist(context.packageName) { command, timeout ->
                            root.exec(command, timeout,
                                mayExecute = { RootCommandGuard.check(command) == null })
                        }
                        val success = result.error == null && result.exitCode == 0
                        revision++
                        toaster.show(resources.getString(if (success)
                            R.string.schedule_execution_root_granted else R.string.schedule_execution_root_failed),
                            type = if (success) ToastType.Success else ToastType.Warning)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toaster.show(resources.getString(R.string.schedule_execution_root_failed), type = ToastType.Warning)
                    } finally {
                        rootBusy = false
                    }
                }
            }
        },
    )
}

@Composable
internal fun ScheduleExecutionSettings(
    state: ScheduleExecutionSettingsState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(if (state.exactAlarmsAllowed)
            R.string.schedule_execution_exact_ready else R.string.schedule_execution_exact_denied))
        if (!state.exactAlarmsAllowed) TextButton(onClick = state.requestExactAlarms) {
            Text(stringResource(R.string.schedule_execution_request_exact))
        }
        Text(stringResource(R.string.schedule_execution_battery_rationale))
        if (state.batteryOptimizationsIgnored) {
            Text(stringResource(R.string.schedule_execution_battery_ready))
        }
        TextButton(onClick = state.requestBatteryOptimizationExemption,
            enabled = !state.batteryOptimizationsIgnored) {
            Text(stringResource(R.string.schedule_execution_disable_battery_optimization))
        }
        TextButton(onClick = state.whitelistWithRoot, enabled = !state.rootBusy) {
            Text(stringResource(R.string.schedule_execution_whitelist_root))
        }
    }
}
