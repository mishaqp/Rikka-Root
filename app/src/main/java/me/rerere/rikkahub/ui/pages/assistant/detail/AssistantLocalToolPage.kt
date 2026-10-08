package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.components.ai.RootCommandJournalButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val rootShellManager = koinInject<RootShellManager>()
    val rootStatus by rootShellManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rootToolEnabled = assistant.localTools.contains(LocalToolOption.Root)
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_root_title)) },
                supportingContent = { Text("Root-команды через su. Разрешения: Настройки → Разрешения инструментов. Доступ также контролирует root-менеджер устройства.") },
                trailingContent = {
                    Switch(
                        checked = rootToolEnabled,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Root, it) },
                    )
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
                (LocalToolOption.SubAgents in assistant.localTools || LocalToolOption.CronJobs in assistant.localTools)) {
                item(headlineContent = { Text(stringResource(R.string.agent_tools_root_headless_title)) },
                    supportingContent = { Text(stringResource(R.string.agent_tools_root_headless_desc)) },
                    trailingContent = {
                        TextButton(enabled = rootStatus != RootStatus.CHECKING,
                            onClick = { scope.launch {
                                rootShellManager.verifyRoot(force = true, prepareHeadless = true)
                                android.widget.Toast.makeText(context,
                                    if (rootShellManager.isHeadlessReady()) R.string.agent_tools_root_headless_opened
                                    else R.string.agent_tools_root_headless_failed, android.widget.Toast.LENGTH_LONG).show()
                            } }) {
                            Text(stringResource(R.string.agent_tools_root_headless_prepare))
                        }
                    })
            }
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_chart_display_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_chart_display_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ChartDisplay),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ChartDisplay, it) }
                    )
                }
            )
        }
        DeviceLocalToolSettings(assistant, onUpdate)
        HardwareLocalToolSettings(assistant, onUpdate)
        PersonalLocalToolSettings(assistant, onUpdate)
        FileLocalToolSettings(assistant, onUpdate)
        AgentLocalToolSettings(assistant, onUpdate)
    }
}
