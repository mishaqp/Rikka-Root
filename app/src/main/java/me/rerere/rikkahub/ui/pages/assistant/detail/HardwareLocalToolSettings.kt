package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalResources
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

private data class HardwareToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val hardwareToolSettings = listOf(
    HardwareToolSetting(LocalToolOption.Torch, R.string.assistant_page_local_tools_torch_title, R.string.assistant_page_local_tools_torch_desc),
    HardwareToolSetting(LocalToolOption.Vibrate, R.string.assistant_page_local_tools_vibrate_title, R.string.assistant_page_local_tools_vibrate_desc),
    HardwareToolSetting(LocalToolOption.Brightness, R.string.assistant_page_local_tools_brightness_title, R.string.assistant_page_local_tools_brightness_desc),
    HardwareToolSetting(LocalToolOption.Volume, R.string.assistant_page_local_tools_volume_title, R.string.assistant_page_local_tools_volume_desc),
    HardwareToolSetting(LocalToolOption.Wallpaper, R.string.assistant_page_local_tools_wallpaper_title, R.string.assistant_page_local_tools_wallpaper_desc),
    HardwareToolSetting(LocalToolOption.Nfc, R.string.assistant_page_local_tools_nfc_title, R.string.assistant_page_local_tools_nfc_desc),
)

@Composable
internal fun HardwareLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val root = koinInject<RootShellManager>()
    val scope = rememberCoroutineScope()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var pendingIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var enableAfterGrant by rememberSaveable(assistant.id.toString()) { mutableStateOf(true) }
    var showRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var deniedIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }

    fun needsAccess(option: LocalToolOption): Boolean = when (option) {
        LocalToolOption.Brightness -> !Settings.System.canWrite(context)
        LocalToolOption.Volume -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted != true
        else -> missingLocalToolPermissions(context, option).isNotEmpty()
    }

    val missing = remember(context, revision) { hardwareToolSettings.map { needsAccess(it.option) } }
    val supported = remember(context) { hardwareToolSettings.map {
        when (it.option) {
            LocalToolOption.Torch -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            LocalToolOption.Nfc -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
            else -> true
        }
    } }

    fun setEnabled(option: LocalToolOption, enabled: Boolean) {
        val current = latestAssistant
        latestUpdate(current.copy(localTools = if (enabled) (current.localTools + option).distinct() else current.localTools - option))
    }

    fun finishPermissionRequest() {
        val index = pendingIndex ?: return
        pendingIndex = null
        revision++
        val setting = hardwareToolSettings[index]
        if (!needsAccess(setting.option)) {
            deniedIndex = null
            if (enableAfterGrant) setEnabled(setting.option, true)
            if (setting.option == LocalToolOption.Volume) toaster.show(message = resources.getString(R.string.hardware_tools_dnd_granted), type = ToastType.Success)
        } else {
            deniedIndex = index
            if (enableAfterGrant) setEnabled(setting.option, false)
            val message = if (setting.option == LocalToolOption.Volume) resources.getString(R.string.hardware_tools_dnd_denied)
                else resources.getString(R.string.hardware_tools_permission_denied, resources.getString(setting.title))
            toaster.show(message = message, type = ToastType.Warning)
        }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finishPermissionRequest() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishPermissionRequest() }

    fun requestAccess(option: LocalToolOption) {
        when (option) {
            LocalToolOption.Brightness, LocalToolOption.Volume -> {
                val intent = if (option == LocalToolOption.Brightness) {
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                } else Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                try {
                    settingsLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        val fallback = if (option == LocalToolOption.Brightness) Settings.ACTION_MANAGE_WRITE_SETTINGS else Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                        settingsLauncher.launch(Intent(fallback))
                    } catch (_: Exception) { finishPermissionRequest() }
                } catch (_: Exception) { finishPermissionRequest() }
            }
            else -> runtimeLauncher.launch(localToolRuntimePermissions(option, Build.VERSION.SDK_INT).toTypedArray())
        }
    }

    CardGroup {
        hardwareToolSettings.forEachIndexed { index, setting ->
            val enabled = setting.option in assistant.localTools
            item(
                headlineContent = { Text(stringResource(setting.title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(setting.description))
                        if (!supported[index]) Text(stringResource(R.string.hardware_tools_unavailable))
                        if (deniedIndex == index && missing[index]) {
                            Text(if (setting.option == LocalToolOption.Volume) stringResource(R.string.hardware_tools_dnd_denied)
                                else stringResource(R.string.hardware_tools_permission_denied, stringResource(setting.title)))
                        }
                        if (supported[index] && missing[index] && setting.option in listOf(LocalToolOption.Torch, LocalToolOption.Brightness)) {
                            TextButton(enabled = !rootBusy && pendingIndex == null, onClick = {
                                // Explicit user action only; never grant while rendering or enabling a switch.
                                rootBusy = true
                                scope.launch {
                                    try {
                                        val command = localPermissionGrantCommand(context.packageName, setting.option, Build.VERSION.SDK_INT, Process.myUid() / 100_000)
                                        val result = if (command != null && RootCommandGuard.check(command) == null) root.exec(command, 20_000) else null
                                        revision++
                                        if (result?.error == null && result?.exitCode == 0 && !needsAccess(setting.option)) {
                                            deniedIndex = null
                                            toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_granted), type = ToastType.Success)
                                        } else toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning)
                                    } finally { rootBusy = false }
                                }
                            }) { Text(stringResource(R.string.assistant_page_local_tools_grant_root)) }
                        }
                        if (setting.option == LocalToolOption.Volume && enabled && missing[index]) {
                            TextButton(enabled = pendingIndex == null && !rootBusy, onClick = {
                                pendingIndex = index; enableAfterGrant = false; showRationale = true
                            }) { Text(stringResource(R.string.hardware_tools_dnd_request)) }
                        }
                        if (setting.option == LocalToolOption.Nfc && enabled && supported[index]) {
                            TextButton(onClick = {
                                try { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                                catch (_: Exception) { toaster.show(message = resources.getString(R.string.nfc_tool_unavailable), type = ToastType.Warning) }
                            }) { Text(stringResource(R.string.hardware_tools_open_nfc)) }
                        }
                    }
                },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        enabled = !rootBusy && pendingIndex == null && (supported[index] || enabled),
                        onCheckedChange = { checked ->
                            if (!checked) setEnabled(setting.option, false)
                            else if (!needsAccess(setting.option)) { deniedIndex = null; setEnabled(setting.option, true) }
                            else { pendingIndex = index; enableAfterGrant = true; showRationale = true }
                        },
                    )
                },
            )
        }
    }

    val pending = pendingIndex
    if (showRationale && pending != null) {
        val setting = hardwareToolSettings[pending]
        AlertDialog(
            title = { Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
            text = { Text(stringResource(setting.description) + "\n\n" + stringResource(R.string.assistant_page_local_tools_permission_explanation)) },
            onDismissRequest = { pendingIndex = null; showRationale = false },
            confirmButton = {
                TextButton(onClick = { showRationale = false; requestAccess(setting.option) }) {
                    Text(stringResource(if (setting.option == LocalToolOption.Torch) R.string.assistant_page_local_tools_permission_request else R.string.hardware_tools_open_settings))
                }
            },
            dismissButton = {
                Column {
                    if (setting.option == LocalToolOption.Volume && enableAfterGrant) {
                        TextButton(onClick = { pendingIndex = null; showRationale = false; deniedIndex = null; setEnabled(setting.option, true) }) {
                            Text(stringResource(R.string.hardware_tools_without_dnd))
                        }
                    }
                    TextButton(onClick = { pendingIndex = null; showRationale = false }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) }
                }
            },
        )
    }
}
