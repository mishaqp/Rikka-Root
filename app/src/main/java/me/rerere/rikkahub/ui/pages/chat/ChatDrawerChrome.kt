package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusChip
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import me.rerere.rikkahub.ui.pages.setting.SettingsIconCircle
import me.rerere.rikkahub.ui.pages.setting.settingsStatusColors
import me.rerere.rikkahub.utils.UpdateChecker
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Reading the cached result never launches su or asks the root manager for access. */
internal fun chatDrawerRootStatus(status: RootStatus): SettingsDashboardStatusChip? = when (status) {
    RootStatus.UNCHECKED, RootStatus.CHECKING -> null
    RootStatus.READY -> SettingsDashboardStatusChip("Активен", SettingsDashboardStatusTone.Success)
    RootStatus.UNAVAILABLE -> SettingsDashboardStatusChip("Недоступен", SettingsDashboardStatusTone.Error)
    RootStatus.DENIED_OR_FAILED, RootStatus.NOT_ROOT ->
        SettingsDashboardStatusChip("Нет доступа", SettingsDashboardStatusTone.Error)
    RootStatus.TIMED_OUT -> SettingsDashboardStatusChip("Таймаут", SettingsDashboardStatusTone.Warning)
}

internal fun chatDrawerModelName(settings: Settings, current: Conversation): String? =
    settings.getChatModelOf(current)?.let { model ->
        model.displayName.ifBlank { model.modelId }.takeIf(String::isNotBlank)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatDrawerHeader(
    settings: Settings,
    current: Conversation,
    onEditNickname: () -> Unit,
    onUpdateAvatar: (Avatar) -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    showUpdateStatus: Boolean = true,
) {
    val manager: RootShellManager = koinInject()
    val root by manager.status.collectAsStateWithLifecycle()
    val checker: UpdateChecker = koinInject()
    val update by checker.nightlyState.collectAsStateWithLifecycle()
    val nickname = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) }
    val modelName = chatDrawerModelName(settings, current)
    val rootChip = chatDrawerRootStatus(root)
    val colors = MaterialTheme.colorScheme
    fun navigate(id: String) { ChatDrawerNavigation.route(id, current.assistantId.toString())?.let(onNavigate) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .25f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UIAvatar(
                    name = nickname,
                    value = settings.displaySetting.userAvatar,
                    onUpdate = onUpdateAvatar,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Изменить аватар" },
                )
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Изменить имя", onClick = onEditNickname),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(nickname, style = MaterialTheme.typography.titleMedium)
                    Greeting(style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { navigate("header_settings") }) {
                    Icon(ChatDrawerNavigation.item("header_settings").icon, "Настройки", modifier = Modifier.size(20.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                modelName?.let { name ->
                    ChatDrawerStateChip(ChatDrawerNavigation.item("model").icon, name,
                        SettingsDashboardStatusTone.Neutral, "Текущая модель: $name. Выбрать модель по умолчанию") {
                        navigate("model")
                    }
                }
                rootChip?.let { chip ->
                    ChatDrawerStateChip(ChatDrawerNavigation.item("root_status").icon, chip.text, chip.tone,
                        "Root: ${chip.text}") { navigate("root_status") }
                }
                AnimatedVisibility(visible = showUpdateStatus && update.hasUpdate) {
                    ChatDrawerStateChip(ChatDrawerNavigation.item("update").icon, "Есть обновление",
                        SettingsDashboardStatusTone.Success, "Есть обновление. Открыть сведения") {
                        navigate("update")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDrawerStateChip(
    icon: ImageVector,
    text: String,
    tone: SettingsDashboardStatusTone,
    description: String,
    onClick: () -> Unit,
) {
    val colors = settingsStatusColors(tone)
    Surface(
        onClick = onClick,
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun ChatDrawerNewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(HugeIcons.PlusSign, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Новый чат")
    }
}

@Composable
internal fun ChatDrawerSearchBar(onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = { ChatDrawerNavigation.route("search")?.let(onNavigate) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(ChatDrawerNavigation.item("search").icon, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Поиск по сообщениям", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { ChatDrawerNavigation.route("history")?.let(onNavigate) }) {
            Icon(ChatDrawerNavigation.item("history").icon, "История чатов")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatDrawerAssistantCard(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var expanded by remember { mutableStateOf(false) }
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val selected = state.currentAssistant
    val name = selected.name.ifBlank { defaultAssistantName }
    val filtered = settings.assistants.filter { assistant ->
        selectedTagIds.isEmpty() || assistant.tags.any { it in selectedTagIds }
    }

    Box(modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UIAvatar(name, selected.avatar, Modifier.size(32.dp), onClick = { expanded = !expanded })
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(HugeIcons.ArrowDown01, "Выбрать ассистента", Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 440.dp)) {
            if (settings.assistantTags.isNotEmpty()) {
                Text("Метки ассистентов", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    settings.assistantTags.forEach { tag ->
                        FilterChip(selected = tag.id in selectedTagIds, onClick = {
                            selectedTagIds = if (tag.id in selectedTagIds) selectedTagIds - tag.id else selectedTagIds + tag.id
                        }, label = { Text(tag.name) })
                    }
                }
            }
            filtered.forEach { assistant ->
                val assistantName = assistant.name.ifBlank { defaultAssistantName }
                DropdownMenuItem(
                    text = { Text(assistantName) },
                    leadingIcon = {
                        UIAvatar(assistantName, assistant.avatar, Modifier.size(30.dp),
                            onClick = { expanded = false; state.setSelectAssistant(assistant) })
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (assistant.id == selected.id) Icon(HugeIcons.Tick01, null, Modifier.size(16.dp))
                            IconButton(onClick = {
                                expanded = false
                                ChatDrawerNavigation.route("assistant_detail", assistant.id.toString())?.let(onNavigate)
                            }) { Icon(HugeIcons.Edit03, "Настройки ассистента: $assistantName", Modifier.size(18.dp)) }
                        }
                    },
                    onClick = { expanded = false; state.setSelectAssistant(assistant) },
                )
            }
            if (filtered.isEmpty()) Text("Нет ассистентов с выбранными метками", Modifier.padding(12.dp))
            HorizontalDivider()
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Assistant).forEach { item ->
                DropdownMenuItem(text = { Text(item.title) }, leadingIcon = { Icon(item.icon, null) }, onClick = {
                    expanded = false
                    item.destination(selected.id.toString())?.let(onNavigate)
                })
            }
        }
    }
}

@Composable
internal fun ChatDrawerBottomBar(
    currentAssistantId: String,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMore by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Bottom).forEach { item ->
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp)
                        .clickable(role = Role.Button) {
                            if (item.id == "more") showMore = true
                            else item.destination(currentAssistantId)?.let(onNavigate)
                        }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsIconCircle(item.icon, item.tone, size = 32.dp, iconSize = 19.dp)
                    Text(item.title, style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Text("Ещё", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge) }
                items(ChatDrawerNavigation.section(ChatDrawerNavigationSection.More), key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) {
                                showMore = false
                                item.destination(currentAssistantId)?.let(onNavigate)
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconCircle(item.icon, item.tone, size = 36.dp, iconSize = 20.dp)
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item { Spacer(Modifier.padding(bottom = 20.dp)) }
            }
        }
    }
}
