package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.components.ai.RootCommandJournalButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingRootPage() {
    val root = koinInject<RootShellManager>()
    val status by root.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val toaster = LocalToaster.current
    val headlessReadyText = stringResource(R.string.agent_tools_root_headless_opened)
    val headlessFailedText = stringResource(R.string.agent_tools_root_headless_failed)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Root") },
                navigationIcon = { BackButton() },
                scrollBehavior = scroll,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Состояние Root", style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (status) {
                            RootStatus.UNCHECKED -> R.string.assistant_page_local_tools_root_unchecked
                            RootStatus.CHECKING -> R.string.assistant_page_local_tools_root_checking
                            RootStatus.READY -> R.string.assistant_page_local_tools_root_ready
                            RootStatus.UNAVAILABLE -> R.string.assistant_page_local_tools_root_unavailable
                            RootStatus.DENIED_OR_FAILED -> R.string.assistant_page_local_tools_root_denied
                            RootStatus.NOT_ROOT -> R.string.assistant_page_local_tools_root_not_root
                            RootStatus.TIMED_OUT -> R.string.assistant_page_local_tools_root_timeout
                        }))
                        Text("Проверка запускается по нажатию кнопки. Доступ подтверждает root-менеджер устройства.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = status != RootStatus.CHECKING, onClick = {
                            scope.launch { root.verifyRoot(force = true) }
                        }) { Text(stringResource(R.string.assistant_page_local_tools_root_verify)) }
                    }
                }
            }
            item {
                CardGroup {
                    item(
                        onClick = { nav.navigate(Screen.AssistantLocalTool(assistant.id.toString())) },
                        headlineContent = { Text("Настроить инструмент Root") },
                        supportingContent = {
                            Text("Ассистент: ${assistant.name.ifBlank { "По умолчанию" }}. Откройте Root в категории «Root и терминал».")
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingTermux) },
                        headlineContent = { Text("Общие лимиты агента и команд") },
                        supportingContent = { Text("Число шагов, время ответа и ожидание команд находятся в разделе Termux и действуют также для Root.") },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingToolApprovals) },
                        headlineContent = { Text("Разрешения инструментов") },
                        supportingContent = { Text("Одобрения Root-команд и других инструментов") },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.root_command_log_title)) },
                        supportingContent = { Text(stringResource(R.string.root_command_log_description)) },
                        trailingContent = { RootCommandJournalButton() },
                    )
                }
            }
            if (LocalToolOption.Root in assistant.localTools && assistant.localTools.any {
                it in setOf(LocalToolOption.SubAgents, LocalToolOption.CronJobs, LocalToolOption.Workflows,
                    LocalToolOption.ScreenAutomation, LocalToolOption.JsSkills)
            }) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.agent_tools_root_headless_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.agent_tools_root_headless_desc))
                            TextButton(enabled = status != RootStatus.CHECKING, onClick = {
                                scope.launch {
                                    root.verifyRoot(force = true, prepareHeadless = true)
                                    val ready = root.isHeadlessReady()
                                    toaster.show(
                                        if (ready) headlessReadyText else headlessFailedText,
                                        type = if (ready) ToastType.Success else ToastType.Warning,
                                    )
                                }
                            }) { Text(stringResource(R.string.agent_tools_root_headless_prepare)) }
                        }
                    }
                }
            }
        }
    }
}
