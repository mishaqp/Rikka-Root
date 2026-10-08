// Agent AssistantLocalToolPage G rows (AGPL-3.0), adapted to Root permissions/navigation.
package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster

private data class LargeToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val largeToolSettings = listOf(
    LargeToolSetting(LocalToolOption.Browser, R.string.assistant_page_local_tools_browser_title,
        R.string.assistant_page_local_tools_browser_desc),
    LargeToolSetting(LocalToolOption.SkillImport, R.string.assistant_page_local_tools_skill_import_title,
        R.string.assistant_page_local_tools_skill_import_desc),
    LargeToolSetting(LocalToolOption.JsSkills, R.string.assistant_page_local_tools_js_skills_title,
        R.string.assistant_page_local_tools_js_skills_desc),
    LargeToolSetting(LocalToolOption.ScreenAutomation, R.string.assistant_page_local_tools_screen_automation_title,
        R.string.assistant_page_local_tools_screen_automation_desc),
    LargeToolSetting(LocalToolOption.Workflows, R.string.assistant_page_local_tools_workflows_title,
        R.string.assistant_page_local_tools_workflows_desc),
)

/** Original feature switches remain opt-in. Bluetooth is optional for unrelated triggers. */
@Composable
internal fun LargeLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var workflowsDialogShownThisVisit by remember(assistant.id) { mutableStateOf(false) }
    var showWorkflowsHintDialog by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var showBluetoothRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var bluetoothDenied by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var pendingBluetoothOwner by rememberSaveable(assistant.id.toString()) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }

    val bluetoothGranted = remember(context, revision) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val owner = pendingBluetoothOwner
        pendingBluetoothOwner = null
        revision++
        // A permission dialog may return after navigation to another assistant. It must not
        // update a new assistant or enable a feature that the user has already switched off.
        if (owner == latestAssistant.id.toString() && LocalToolOption.Workflows in latestAssistant.localTools) {
            bluetoothDenied = !granted
            if (!granted) toaster.show(resources.getString(R.string.large_tools_bluetooth_denied), type = ToastType.Warning)
        }
    }

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        val current = latestAssistant
        latestUpdate(current.copy(localTools = if (enabled) (current.localTools + option).distinct()
            else current.localTools - option))
        if (enabled && option == LocalToolOption.Workflows && !workflowsDialogShownThisVisit) {
            workflowsDialogShownThisVisit = true
            showWorkflowsHintDialog = true
        }
        if (!enabled && option == LocalToolOption.Workflows) {
            showBluetoothRationale = false
            pendingBluetoothOwner = null
        }
    }

    CardGroup(title = { Text(stringResource(R.string.large_tools_group_title)) }) {
        largeToolSettings.forEach { setting ->
            val enabled = setting.option in assistant.localTools
            item(
                headlineContent = { Text(stringResource(setting.title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(setting.description))
                        if (setting.option == LocalToolOption.Browser) {
                            TextButton(onClick = { nav.navigate(Screen.SettingBrowser) }) {
                                Text(stringResource(R.string.large_tools_open_browser_settings))
                            }
                        }
                        if (setting.option == LocalToolOption.Workflows) {
                            TextButton(onClick = { nav.navigate(Screen.Workflows) }) {
                                Text(stringResource(R.string.large_tools_open_workflows))
                            }
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (bluetoothGranted) Text(stringResource(R.string.large_tools_bluetooth_granted))
                                else TextButton(onClick = { showBluetoothRationale = true },
                                    enabled = pendingBluetoothOwner == null) {
                                    Text(stringResource(R.string.large_tools_bluetooth_request))
                                }
                                if (bluetoothDenied && !bluetoothGranted) Text(stringResource(R.string.large_tools_bluetooth_denied))
                            }
                        }
                    }
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { toggleLocalTool(setting.option, it) })
                },
            )
        }
    }

    if (showWorkflowsHintDialog) AlertDialog(
        onDismissRequest = { showWorkflowsHintDialog = false },
        title = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_title)) },
        text = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_message)) },
        confirmButton = {
            TextButton(onClick = { showWorkflowsHintDialog = false }) {
                Text(stringResource(R.string.large_tools_dismiss))
            }
        },
    )

    if (showBluetoothRationale) AlertDialog(
        onDismissRequest = { showBluetoothRationale = false },
        title = { Text(stringResource(R.string.large_tools_bluetooth_title)) },
        text = { Text(stringResource(R.string.large_tools_bluetooth_rationale)) },
        confirmButton = {
            TextButton(onClick = {
                showBluetoothRationale = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocalToolOption.Workflows in latestAssistant.localTools) {
                    pendingBluetoothOwner = latestAssistant.id.toString()
                    try { bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                    catch (_: Exception) {
                        pendingBluetoothOwner = null
                        bluetoothDenied = true
                        toaster.show(resources.getString(R.string.large_tools_bluetooth_denied), type = ToastType.Warning)
                    }
                }
            }) { Text(stringResource(R.string.large_tools_bluetooth_request)) }
        },
        dismissButton = {
            TextButton(onClick = { showBluetoothRationale = false }) {
                Text(stringResource(R.string.large_tools_cancel))
            }
        },
    )
}
