package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.components.ai.RootCommandJournalButton
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiRegistry
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.compose.koinInject

/** Keeps the existing per-tool configuration and access actions inside its detail sheet. */
@Composable
internal fun LocalToolDetailSettings(
    option: LocalToolOption,
    assistant: Assistant,
    onEnabledChange: ((LocalToolOption, Boolean) -> Unit)? = null,
    onUpdate: (Assistant) -> Unit,
    controlsEnabled: Boolean = true,
) {
    key(assistant.id, option) {
        val visibleOptions = setOf(option)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (option) {
                LocalToolOption.Root -> RootLocalToolDetailSettings(assistant, onUpdate, onEnabledChange, controlsEnabled)
                LocalToolOption.JavascriptEngine, LocalToolOption.TimeInfo,
                LocalToolOption.Clipboard, LocalToolOption.Tts, LocalToolOption.AskUser,
                LocalToolOption.ScreenTime, LocalToolOption.Calendar,
                LocalToolOption.ChartDisplay -> BasicLocalToolDetailSettings(option, assistant, onUpdate, onEnabledChange, controlsEnabled)

                LocalToolOption.Battery, LocalToolOption.AudioInfo, LocalToolOption.TelephonyInfo,
                LocalToolOption.WifiInfo, LocalToolOption.Sensors, LocalToolOption.StorageInfo,
                LocalToolOption.Toast, LocalToolOption.Notification, LocalToolOption.Share ->
                    DeviceLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.Torch, LocalToolOption.Vibrate, LocalToolOption.Brightness,
                LocalToolOption.Volume, LocalToolOption.Wallpaper, LocalToolOption.Nfc ->
                    HardwareLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.Location, LocalToolOption.Contacts, LocalToolOption.CallLog,
                LocalToolOption.SmsInbox, LocalToolOption.SmsSend, LocalToolOption.CameraPhoto,
                LocalToolOption.MicRecorder, LocalToolOption.SpeechToText,
                LocalToolOption.Fingerprint, LocalToolOption.Keystore ->
                    PersonalLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.Files, LocalToolOption.ExternalStorage, LocalToolOption.Archive,
                LocalToolOption.MediaPlayer, LocalToolOption.MediaScanner, LocalToolOption.Download,
                LocalToolOption.SystemIntents, LocalToolOption.AppLauncher ->
                    FileLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.CostGuards, LocalToolOption.Reliability,
                LocalToolOption.SubAgents, LocalToolOption.CronJobs,
                LocalToolOption.ManageAssistants, LocalToolOption.ManageAppSettings ->
                    AgentLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.Termux, LocalToolOption.Ssh, LocalToolOption.McpControl,
                LocalToolOption.ExternalAutomation ->
                    ShellLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)

                LocalToolOption.Browser, LocalToolOption.SkillImport, LocalToolOption.JsSkills,
                LocalToolOption.ScreenAutomation, LocalToolOption.Workflows ->
                    LargeLocalToolSettings(assistant, onUpdate, visibleOptions, onEnabledChange, controlsEnabled)
            }

            val nav = LocalNavController.current
            when (option) {
                LocalToolOption.ManageAssistants -> {
                    TextButton(onClick = { nav.navigate(Screen.Assistant) }) {
                        Text(stringResource(R.string.assistant_page_local_tools_manage_assistants_open))
                    }
                }
                LocalToolOption.ManageAppSettings -> {
                    TextButton(onClick = { nav.navigate(Screen.Setting) }) {
                        Text(stringResource(R.string.assistant_page_local_tools_manage_app_settings_open))
                    }
                }
                LocalToolOption.SkillImport, LocalToolOption.JsSkills -> {
                    TextButton(onClick = { nav.navigate(Screen.Skills) }) {
                        Text("Управление навыками")
                    }
                }
                LocalToolOption.McpControl -> {
                    TextButton(onClick = { nav.navigate(Screen.AssistantMcp(assistant.id.toString())) }) {
                        Text("MCP-серверы ассистента")
                    }
                    TextButton(onClick = { nav.navigate(Screen.SettingMcp) }) {
                        Text("Настройки MCP-серверов")
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun BasicLocalToolDetailSettings(
    option: LocalToolOption,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onEnabledChange: ((LocalToolOption, Boolean) -> Unit)?,
    controlsEnabled: Boolean,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    val entry = LocalToolUiRegistry.entry(option)
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)
    val calendarPermissionState = if (option == LocalToolOption.Calendar) {
        rememberPermissionState(
            permissions = setOf(
                PermissionInfo(
                    permission = Manifest.permission.READ_CALENDAR,
                    displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                    usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                    required = true,
                ),
                PermissionInfo(
                    permission = Manifest.permission.WRITE_CALENDAR,
                    displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                    usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                    required = true,
                ),
            ),
        )
    } else null
    if (calendarPermissionState != null) PermissionManager(calendarPermissionState)

    CardGroup {
        item(
            headlineContent = { Text(stringResource(entry.titleRes)) },
            supportingContent = { Text(stringResource(entry.descriptionRes)) },
            trailingContent = {
                Switch(
                    checked = option in assistant.localTools,
                    enabled = controlsEnabled,
                    onCheckedChange = { enabled ->
                        if (onEnabledChange != null) {
                            onEnabledChange(option, enabled)
                        } else {
                            if (enabled && option == LocalToolOption.ScreenTime &&
                                !context.hasUsageStatsPermission()) {
                                toaster.show(message = permissionRequiredText, type = ToastType.Warning)
                                context.openUsageAccessSettings()
                            }
                            if (enabled && calendarPermissionState != null &&
                                !calendarPermissionState.allPermissionsGranted) {
                                calendarPermissionState.requestPermissions()
                            } else {
                                val current = latestAssistant
                                latestUpdate(current.copy(localTools = if (enabled)
                                    (current.localTools + option).distinct() else current.localTools - option))
                            }
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun RootLocalToolDetailSettings(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onEnabledChange: ((LocalToolOption, Boolean) -> Unit)?,
    controlsEnabled: Boolean,
) {
    val context = LocalContext.current
    val rootShellManager = koinInject<RootShellManager>()
    val rootStatus by rootShellManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_root_title)) },
            supportingContent = {
                Text("Root-команды через su. Разрешения: Настройки → Разрешения инструментов. Доступ также контролирует root-менеджер устройства.")
            },
            trailingContent = {
                Switch(checked = LocalToolOption.Root in assistant.localTools, enabled = controlsEnabled, onCheckedChange = { enabled ->
                    if (onEnabledChange != null) onEnabledChange(LocalToolOption.Root, enabled)
                    else {
                        val current = latestAssistant
                        latestUpdate(current.copy(localTools = if (enabled)
                            (current.localTools + LocalToolOption.Root).distinct()
                            else current.localTools - LocalToolOption.Root))
                    }
                })
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.root_command_log_title)) },
            supportingContent = { Text(stringResource(R.string.root_command_log_description)) },
            trailingContent = { RootCommandJournalButton(assistant.id.toString()) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_root_status_title)) },
            supportingContent = {
                Text(stringResource(when (rootStatus) {
                    RootStatus.UNCHECKED -> R.string.assistant_page_local_tools_root_unchecked
                    RootStatus.CHECKING -> R.string.assistant_page_local_tools_root_checking
                    RootStatus.READY -> R.string.assistant_page_local_tools_root_ready
                    RootStatus.UNAVAILABLE -> R.string.assistant_page_local_tools_root_unavailable
                    RootStatus.DENIED_OR_FAILED -> R.string.assistant_page_local_tools_root_denied
                    RootStatus.NOT_ROOT -> R.string.assistant_page_local_tools_root_not_root
                    RootStatus.TIMED_OUT -> R.string.assistant_page_local_tools_root_timeout
                }))
            },
            trailingContent = {
                TextButton(
                    enabled = rootStatus != RootStatus.CHECKING,
                    onClick = { scope.launch { rootShellManager.verifyRoot(force = true) } },
                ) {
                    Text(stringResource(R.string.assistant_page_local_tools_root_verify))
                }
            },
        )
        if (LocalToolOption.Root in assistant.localTools &&
            assistant.localTools.any { it in setOf(LocalToolOption.SubAgents, LocalToolOption.CronJobs,
                LocalToolOption.Workflows, LocalToolOption.ScreenAutomation, LocalToolOption.JsSkills) }) {
            item(
                headlineContent = { Text(stringResource(R.string.agent_tools_root_headless_title)) },
                supportingContent = { Text(stringResource(R.string.agent_tools_root_headless_desc)) },
                trailingContent = {
                    TextButton(
                        enabled = rootStatus != RootStatus.CHECKING,
                        onClick = {
                            scope.launch {
                                rootShellManager.verifyRoot(force = true, prepareHeadless = true)
                                Toast.makeText(context,
                                    if (rootShellManager.isHeadlessReady()) R.string.agent_tools_root_headless_opened
                                    else R.string.agent_tools_root_headless_failed, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.agent_tools_root_headless_prepare))
                    }
                },
            )
        }
    }
}
