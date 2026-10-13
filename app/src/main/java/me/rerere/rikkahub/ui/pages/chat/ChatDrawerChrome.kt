package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.rememberAppHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusChip
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode
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

@Composable
internal fun ChatDrawerHeader(
    settings: Settings,
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
    val rootChip = chatDrawerRootStatus(root)
    ChatDrawerHeaderContent(
        nickname = nickname,
        rootChip = rootChip,
        hasUpdate = showUpdateStatus && update.hasUpdate,
        showAppUpdates = settings.displaySetting.showAppUpdates,
        onEditNickname = onEditNickname,
        onNavigate = onNavigate,
        modifier = modifier,
        avatar = { avatarModifier ->
            UIAvatar(
                name = nickname,
                value = settings.displaySetting.userAvatar,
                onUpdate = onUpdateAvatar,
                modifier = avatarModifier,
            )
        },
    )
}

@Composable
internal fun ChatDrawerHeaderContent(
    nickname: String,
    rootChip: SettingsDashboardStatusChip?,
    hasUpdate: Boolean,
    onEditNickname: () -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    showAppUpdates: Boolean = true,
    greeting: @Composable () -> Unit = { Greeting(style = MaterialTheme.typography.bodySmall) },
    avatar: @Composable (Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val updateVisible = hasUpdate && showAppUpdates
    fun navigate(id: String) { ChatDrawerNavigation.route(id)?.let(onNavigate) }

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_drawer_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        avatar(Modifier.size(40.dp).semantics { contentDescription = "Изменить аватар" })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .clickable(role = Role.Button, onClickLabel = "Изменить имя", onClick = onEditNickname),
            ) {
                Text(nickname, style = MaterialTheme.typography.titleMedium, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    greeting()
                }
            }
            if (rootChip != null || updateVisible) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rootChip?.let { chip ->
                        ChatDrawerStatusAction(ChatDrawerNavigation.item("root_status").icon, chip.tone,
                            "Root: ${chip.text}") { navigate("root_status") }
                    }
                    if (updateVisible) {
                        ChatDrawerStatusAction(ChatDrawerNavigation.item("update").icon,
                            SettingsDashboardStatusTone.Success, "Есть обновление. Открыть сведения") {
                            navigate("update")
                        }
                    }
                }
            }
        }
        IconButton(onClick = { ChatDrawerNavigation.route("translator")?.let(onNavigate) }, modifier = Modifier.size(40.dp)) {
            Icon(ChatDrawerNavigation.item("translator").icon, stringResource(R.string.moru_drawer_translator),
                Modifier.size(22.dp), tint = colors.onSurface)
        }
        IconButton(onClick = { navigate("header_settings") }, modifier = Modifier.size(40.dp)) {
            Icon(ChatDrawerNavigation.item("header_settings").icon, "Настройки", Modifier.size(24.dp),
                tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatDrawerStatusAction(
    icon: ImageVector,
    tone: SettingsDashboardStatusTone,
    description: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (tone) {
        SettingsDashboardStatusTone.Success -> colors.tertiaryContainer
        SettingsDashboardStatusTone.Warning -> colors.secondaryContainer
        SettingsDashboardStatusTone.Error -> colors.errorContainer
        SettingsDashboardStatusTone.Neutral -> colors.surfaceContainerHigh
    }
    Box(
        Modifier.size(28.dp).clip(MaterialTheme.shapes.small).background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

/** Moru's fixed topic search; the leading icon retains the full message-search destination. */
@Composable
internal fun ChatDrawerSearchBar(
    onNavigate: (Screen) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val haptic = rememberAppHapticFeedback(HapticAction.DRAWER)
    val topicSearchLabel = stringResource(R.string.moru_drawer_search_topics)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_drawer_search_row"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("chat_drawer_topic_search")
                .semantics { contentDescription = topicSearchLabel },
            decorationBox = { field ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceContainerHigh.copy(alpha = .8f)),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        ChatDrawerNavigation.route("search")?.let(onNavigate)
                    }, modifier = Modifier.size(40.dp)) {
                        Icon(ChatDrawerNavigation.item("search").icon,
                            stringResource(R.string.moru_drawer_search_messages), Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (query.isEmpty()) Text(topicSearchLabel,
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.moru_drawer_clear_search), Modifier.size(16.dp))
                    }
                }
            },
        )
        IconButton(onClick = { ChatDrawerNavigation.route("history")?.let(onNavigate) }, modifier = Modifier.size(44.dp)) {
            Icon(ChatDrawerNavigation.item("history").icon, "История чатов", Modifier.size(20.dp), tint = colors.onSurface)
        }
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Новый чат") } }, state = rememberTooltipState()) {
            IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp)) {
                Icon(HugeIcons.MessageAdd01, "Новый чат", Modifier.size(22.dp), tint = colors.onSurface)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatDrawerAssistantCard(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    isDrawerOpen: Boolean = true,
    initiallyExpanded: Boolean = false,
    onExpansionChange: (Boolean) -> Unit = {},
    avatarContent: @Composable (String, Avatar, Modifier, () -> Unit) -> Unit = { name, avatar, avatarModifier, onClick ->
        UIAvatar(name, avatar, avatarModifier, onClick = onClick)
    },
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    androidx.compose.runtime.LaunchedEffect(expanded) { onExpansionChange(expanded) }
    val haptic = rememberAppHapticFeedback(HapticAction.DRAWER)
    androidx.compose.runtime.LaunchedEffect(isDrawerOpen) {
        if (!isDrawerOpen && !settings.displaySetting.keepAssistantListExpandedOnSidebarClose) expanded = false
    }
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val selected = state.currentAssistant
    val name = selected.name.ifBlank { defaultAssistantName }
    val filtered = settings.assistants.filter { assistant ->
        selectedTagIds.isEmpty() || assistant.tags.any { it in selectedTagIds }
    }

    Box(modifier.fillMaxWidth().testTag("chat_drawer_assistant")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                .combinedClickable(role = Role.Button,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); expanded = !expanded },
                    onLongClickLabel = stringResource(R.string.moru_drawer_assistant_settings),
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        ChatDrawerNavigation.route("assistant_detail", selected.id.toString())?.let(onNavigate)
                    })
                .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            avatarContent(name, selected.avatar, Modifier.size(32.dp)) { expanded = !expanded }
            Text(name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), modifier = Modifier.weight(1f),
                maxLines = if (LocalDensity.current.fontScale > 1.15f) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Icon(HugeIcons.ArrowDown01, "Выбрать ассистента", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        avatarContent(assistantName, assistant.avatar, Modifier.size(30.dp)) {
                            if (!settings.displaySetting.keepSidebarOpenOnAssistantTap) expanded = false
                            state.setSelectAssistant(assistant)
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (assistant.id == selected.id) Icon(HugeIcons.Tick01, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = {
                                expanded = false
                                ChatDrawerNavigation.route("assistant_detail", assistant.id.toString())?.let(onNavigate)
                            }) { Icon(HugeIcons.Edit03, "Настройки ассистента: $assistantName", Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    onClick = {
                        if (!settings.displaySetting.keepSidebarOpenOnAssistantTap) expanded = false
                        state.setSelectAssistant(assistant)
                    },
                )
            }
            if (filtered.isEmpty()) Text("Нет ассистентов с выбранными метками", Modifier.padding(12.dp))
            HorizontalDivider()
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Assistant).forEach { item ->
                DropdownMenuItem(text = { Text(item.title) }, leadingIcon = {
                    Icon(item.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }, onClick = {
                    expanded = false
                    item.destination(selected.id.toString())?.let(onNavigate)
                })
            }
        }
    }
}

internal fun chatDrawerBottomSelection(
    activeDestination: Screen?,
    currentAssistantId: String,
    moreExpanded: Boolean,
): String? {
    if (moreExpanded) return "more"
    if (activeDestination == null) return null
    ChatDrawerNavigation.section(ChatDrawerNavigationSection.Bottom)
        .firstOrNull { it.id != "more" && it.destination(currentAssistantId) == activeDestination }
        ?.let { return it.id }
    return "more".takeIf {
        ChatDrawerNavigation.section(ChatDrawerNavigationSection.More)
            .any { it.destination(currentAssistantId) == activeDestination }
    }
}

@Composable
internal fun ChatDrawerBottomBar(
    currentAssistantId: String,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    activeDestination: Screen? = null,
) {
    var showMore by remember { mutableStateOf(false) }
    val selectedItem = chatDrawerBottomSelection(activeDestination, currentAssistantId, showMore)
    val colors = MaterialTheme.colorScheme
    val containerColor = if (LocalAmoledDarkMode.current) colors.background else colors.surface
    Surface(modifier.fillMaxWidth().testTag("chat_drawer_bottom_bar"), color = containerColor) {
        Row(verticalAlignment = Alignment.Top) {
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Bottom).forEach { item ->
                val isSelected = item.id == selectedItem
                val contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { selected = isSelected }
                        .clickable(role = Role.Button) {
                            if (item.id == "more") showMore = true
                            else item.destination(currentAssistantId)?.let(onNavigate)
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(width = 48.dp, height = 24.dp)
                            .then(if (isSelected) Modifier.background(colors.secondaryContainer, RoundedCornerShape(50)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, null, Modifier.size(20.dp), tint = contentColor)
                    }
                    Text(item.title, style = MaterialTheme.typography.labelSmall.copy(
                        lineBreak = LineBreak.Heading,
                        hyphens = Hyphens.Auto,
                        localeList = LocaleList("ru"),
                    ),
                        color = contentColor,
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
                        Icon(item.icon, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item { Spacer(Modifier.padding(bottom = 20.dp)) }
            }
        }
    }
}
