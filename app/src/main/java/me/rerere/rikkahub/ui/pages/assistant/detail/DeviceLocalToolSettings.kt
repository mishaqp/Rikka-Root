package me.rerere.rikkahub.ui.pages.assistant.detail

import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.localPermissionGrantCommand
import me.rerere.rikkahub.data.ai.tools.local.localToolRuntimePermissions
import me.rerere.rikkahub.data.ai.tools.local.missingLocalToolPermissions
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

private data class DeviceToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val deviceToolSettings = listOf(
    DeviceToolSetting(LocalToolOption.Battery, R.string.assistant_page_local_tools_battery_title, R.string.assistant_page_local_tools_battery_desc),
    DeviceToolSetting(LocalToolOption.AudioInfo, R.string.assistant_page_local_tools_audio_info_title, R.string.assistant_page_local_tools_audio_info_desc),
    DeviceToolSetting(LocalToolOption.TelephonyInfo, R.string.assistant_page_local_tools_telephony_info_title, R.string.assistant_page_local_tools_telephony_info_desc),
    DeviceToolSetting(LocalToolOption.WifiInfo, R.string.assistant_page_local_tools_wifi_info_title, R.string.assistant_page_local_tools_wifi_info_desc),
    DeviceToolSetting(LocalToolOption.Sensors, R.string.assistant_page_local_tools_sensors_title, R.string.assistant_page_local_tools_sensors_desc),
    DeviceToolSetting(LocalToolOption.StorageInfo, R.string.assistant_page_local_tools_storage_info_title, R.string.assistant_page_local_tools_storage_info_desc),
    DeviceToolSetting(LocalToolOption.Toast, R.string.assistant_page_local_tools_toast_title, R.string.assistant_page_local_tools_toast_desc),
    DeviceToolSetting(LocalToolOption.Notification, R.string.assistant_page_local_tools_notification_title, R.string.assistant_page_local_tools_notification_desc),
    DeviceToolSetting(LocalToolOption.Share, R.string.assistant_page_local_tools_share_title, R.string.assistant_page_local_tools_share_desc),
)

@Composable
internal fun DeviceLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val root = koinInject<RootShellManager>()
    val scope = rememberCoroutineScope()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var pendingIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var showRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var deniedIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRevision++ }
    val missingPermissions = remember(context, permissionRevision) {
        deviceToolSettings.map { missingLocalToolPermissions(context, it.option) }
    }

    fun setEnabled(option: LocalToolOption, enabled: Boolean) {
        val current = latestAssistant
        val options = if (enabled) (current.localTools + option).distinct() else current.localTools - option
        latestUpdate(current.copy(localTools = options))
    }

    fun explainDenial(index: Int) {
        deniedIndex = index
        toaster.show(
            message = context.getString(R.string.assistant_page_local_tools_permission_denied, context.getString(deviceToolSettings[index].title)),
            type = ToastType.Warning,
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val index = pendingIndex
        pendingIndex = null
        permissionRevision++
        if (index != null) {
            val option = deviceToolSettings[index].option
            // Recheck Android instead of trusting a partial permission result or stale assistant.
            if (missingLocalToolPermissions(context, option).isEmpty()) {
                deniedIndex = null
                setEnabled(option, true)
            } else {
                setEnabled(option, false)
                explainDenial(index)
            }
        }
    }

    CardGroup {
        deviceToolSettings.forEachIndexed { index, setting ->
            item(
                headlineContent = { Text(stringResource(setting.title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(setting.description))
                        if (deniedIndex == index && missingPermissions[index].isNotEmpty()) {
                            Text(stringResource(R.string.assistant_page_local_tools_permission_denied, stringResource(setting.title)))
                        }
                        if (missingPermissions[index].isNotEmpty()) {
                            TextButton(
                                enabled = !rootBusy && pendingIndex == null,
                                onClick = {
                                    // This is the only entry into root granting: a direct user tap.
                                    rootBusy = true
                                    scope.launch {
                                        try {
                                            val command = localPermissionGrantCommand(context.packageName, setting.option, Build.VERSION.SDK_INT, Process.myUid() / 100_000)
                                            val result = if (command != null && RootCommandGuard.check(command) == null) root.exec(command, 20_000) else null
                                            permissionRevision++
                                            if (result?.error == null && result?.exitCode == 0 && missingLocalToolPermissions(context, setting.option).isEmpty()) {
                                                deniedIndex = null
                                                // Issuing permission does not implicitly toggle the feature on.
                                                toaster.show(message = context.getString(R.string.assistant_page_local_tools_root_granted), type = ToastType.Success)
                                            } else {
                                                toaster.show(message = context.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning)
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            toaster.show(message = context.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning)
                                        } finally {
                                            rootBusy = false
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.assistant_page_local_tools_grant_root)) }
                        }
                    }
                },
                trailingContent = {
                    Switch(
                        checked = setting.option in assistant.localTools,
                        enabled = !rootBusy && pendingIndex == null,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                setEnabled(setting.option, false)
                            } else if (missingLocalToolPermissions(context, setting.option).isEmpty()) {
                                deniedIndex = null
                                setEnabled(setting.option, true)
                            } else {
                                pendingIndex = index
                                showRationale = true
                            }
                        },
                    )
                },
            )
        }
    }

    val pending = pendingIndex
    if (showRationale && pending != null) {
        val setting = deviceToolSettings[pending]
        AlertDialog(
            title = { Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
            text = { Text(stringResource(setting.description) + "\n\n" + stringResource(R.string.assistant_page_local_tools_permission_explanation)) },
            onDismissRequest = { showRationale = false; pendingIndex = null },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    // Coarse + fine must stay together; request only this feature's permissions.
                    launcher.launch(localToolRuntimePermissions(setting.option, Build.VERSION.SDK_INT).toTypedArray())
                }) { Text(stringResource(R.string.assistant_page_local_tools_permission_request)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pendingIndex = null }) {
                    Text(stringResource(R.string.assistant_page_local_tools_permission_cancel))
                }
            },
        )
    }
}
