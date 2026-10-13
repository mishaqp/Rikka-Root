package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import java.time.LocalDate
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.rememberAppHapticFeedback
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.pages.setting.SettingsIconCircle
import me.rerere.rikkahub.ui.pages.setting.SettingsSectionTone
import me.rerere.rikkahub.utils.mirrorForRtl

/** UI projection only: no changes to persisted conversations or backups. */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String,
        val sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation,
        val previewText: String = "",
        val hasTools: Boolean = false,
        val hasWorkflows: Boolean = false,
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onRename: (Conversation) -> Unit = {},
    onNewChat: () -> Unit = {},
    folderSelected: Boolean = false,
    pendingRemovalIds: Set<Uuid> = emptySet(),
    rowMetadata: Map<Uuid, ChatDrawerRowMetadata> = emptyMap(),
    headerContent: @Composable () -> Unit = {},
    selectionMode: Boolean = false,
    selectedConversationIds: Set<Uuid> = emptySet(),
    onToggleSelection: (Conversation) -> Unit = {},
    onStartSelection: (Conversation) -> Unit = {},
    searchQuery: String = "",
    showDates: Boolean = true,
) {
    val visibleItems = conversations.itemSnapshotList.items.filterIsInstance<ConversationListItem.Item>()
        .filterNot { it.conversation.id in pendingRemovalIds }
    val refresh = conversations.loadState.refresh
    val isEmpty = refresh is LoadState.NotLoading && visibleItems.isEmpty() &&
        (conversations.itemCount == 0 || conversations.loadState.append.endOfPaginationReached)
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        item(key = "drawer_header") { headerContent() }
        if (isEmpty) {
            item(key = "drawer_empty") { ChatDrawerEmptyContent(folderSelected, onNewChat, searchQuery.isNotBlank()) }
        } else if (refresh is LoadState.Loading && conversations.itemCount == 0) {
            item(key = "drawer_loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        } else if (refresh is LoadState.Error && conversations.itemCount == 0) {
            item(key = "drawer_error") {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Не удалось загрузить чаты", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { conversations.retry() }) { Text("Повторить") }
                }
            }
        }
        if (!isEmpty) items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    is ConversationListItem.DateHeader -> "date_${item.sortOrder}_${item.date}"
                    ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            },
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> if (showDates) DateHeaderItem(item.label, Modifier.animateItem())
                ConversationListItem.PinnedHeader -> DateHeaderItem("Закреплённые", Modifier.animateItem(), pinned = true)
                is ConversationListItem.Item -> if (item.conversation.id !in pendingRemovalIds) {
                    val metadata = rowMetadata[item.conversation.id] ?: ChatDrawerRowMetadata(
                        preview = item.previewText.ifBlank { "Работа с инструментами" },
                        hasTools = item.hasTools,
                        hasWorkflows = item.hasWorkflows,
                    )
                    SwipeableConversationItem(
                        conversation = item.conversation,
                        metadata = metadata,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        onRename = onRename,
                        modifier = Modifier.animateItem(),
                        selectionMode = selectionMode,
                        selectionChecked = item.conversation.id in selectedConversationIds,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                    )
                }
                null -> Unit
            }
        }
    }
}

@Composable
internal fun DateHeaderItem(label: String, modifier: Modifier = Modifier, pinned: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary)
        if (pinned) Icon(HugeIcons.Pin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    metadata: ChatDrawerRowMetadata,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onDelete: (Conversation) -> Unit,
    onRegenerateTitle: (Conversation) -> Unit,
    onPin: (Conversation) -> Unit,
    onMoveToAssistant: (Conversation) -> Unit,
    onMoveToFolder: (Conversation) -> Unit,
    onRename: (Conversation) -> Unit,
    onClick: (Conversation) -> Unit,
    selectionMode: Boolean = false,
    selectionChecked: Boolean = false,
    onToggleSelection: (Conversation) -> Unit = {},
    onStartSelection: (Conversation) -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.settledValue) {
        if (dismissState.settledValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete(conversation)
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(bottom = 4.dp),
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(HugeIcons.Delete01, "Удалить чат", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
    ) {
        ConversationItem(conversation, metadata, selected, loading, onDelete, onRegenerateTitle,
            onPin, onMoveToAssistant, onMoveToFolder, onRename, onClick,
            selectionMode, selectionChecked, onToggleSelection, onStartSelection)
    }
}

@Composable
internal fun ConversationItem(
    conversation: Conversation,
    metadata: ChatDrawerRowMetadata,
    selected: Boolean,
    loading: Boolean,
    onDelete: (Conversation) -> Unit,
    onRegenerateTitle: (Conversation) -> Unit,
    onPin: (Conversation) -> Unit,
    onMoveToAssistant: (Conversation) -> Unit,
    onMoveToFolder: (Conversation) -> Unit,
    onRename: (Conversation) -> Unit,
    onClick: (Conversation) -> Unit,
    selectionMode: Boolean = false,
    selectionChecked: Boolean = false,
    onToggleSelection: (Conversation) -> Unit = {},
    onStartSelection: (Conversation) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    var showMenu by remember { mutableStateOf(false) }
    val title = conversation.title.ifBlank { "Новый чат" }
    val time = chatDrawerRowTime(conversation.updateAt)
    val toolLabel = if (metadata.hasWorkflows) "Работали сценарии" else "Работали инструменты"
    val haptic = rememberAppHapticFeedback(HapticAction.LIST_ITEM)
    val selectLabel = stringResource(R.string.moru_drawer_select)
    val toggleSelectionLabel = stringResource(R.string.moru_drawer_toggle_selection)
    val highlighted = if (selectionMode) selectionChecked else selected
    val selectedBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (selectionMode) .16f else .12f)
    val foreground = MaterialTheme.colorScheme.onSurface
    val supportingForeground = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.testTag("chat_drawer_conversation_${conversation.id}")
            .fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .then(if (highlighted) Modifier.background(selectedBackground) else Modifier)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = if (selectionMode) Role.Checkbox else Role.Tab,
                onClickLabel = if (selectionMode) toggleSelectionLabel else "Открыть чат",
                onLongClickLabel = "Действия с чатом",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (selectionMode) onToggleSelection(conversation) else onClick(conversation)
                },
                onLongClick = { if (!selectionMode) {
                    focusManager.clearFocus(force = true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                } },
            )
            .semantics(mergeDescendants = true) {
                this.selected = highlighted
                contentDescription = listOfNotNull(title, metadata.preview, time,
                    "Закреплён".takeIf { conversation.isPinned },
                    toolLabel.takeIf { metadata.hasTools }, "Ответ формируется".takeIf { loading })
                    .joinToString(". ")
                customActions = listOf(
                    CustomAccessibilityAction(if (conversation.isPinned) "Открепить" else "Закрепить") { onPin(conversation); true },
                    CustomAccessibilityAction("Переименовать") { onRename(conversation); true },
                    CustomAccessibilityAction("Обновить название с ИИ") { onRegenerateTitle(conversation); true },
                    CustomAccessibilityAction("В папку") { onMoveToFolder(conversation); true },
                    CustomAccessibilityAction("К ассистенту") { onMoveToAssistant(conversation); true },
                    CustomAccessibilityAction("Удалить") { onDelete(conversation); true },
                    CustomAccessibilityAction(if (selectionMode) toggleSelectionLabel else selectLabel) {
                        if (selectionMode) onToggleSelection(conversation) else onStartSelection(conversation)
                        true
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectionMode) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selectionChecked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    if (selectionChecked) Icon(HugeIcons.Tick01, null, Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = foreground)
            if (conversation.isPinned) Icon(HugeIcons.Pin, null, Modifier.size(12.dp), tint = supportingForeground)
            AnimatedVisibility(loading) {
                Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            fun finish(action: (Conversation) -> Unit) { showMenu = false; action(conversation) }
            DropdownMenuItem(text = { Text(if (conversation.isPinned) "Открепить" else "Закрепить") },
                onClick = { finish(onPin) }, leadingIcon = { Icon(if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin, null) })
            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { finish(onRename) },
                leadingIcon = { Icon(HugeIcons.PencilEdit01, null) })
            DropdownMenuItem(text = { Text("Обновить название с ИИ") }, onClick = { finish(onRegenerateTitle) },
                leadingIcon = { Icon(HugeIcons.Refresh01, null) })
            DropdownMenuItem(text = { Text("В папку") }, onClick = { finish(onMoveToFolder) },
                leadingIcon = { Icon(HugeIcons.Folder01, null) })
            DropdownMenuItem(text = { Text("К ассистенту") }, onClick = { finish(onMoveToAssistant) },
                leadingIcon = { Icon(HugeIcons.Forward02, null, Modifier.mirrorForRtl()) })
            DropdownMenuItem(text = { Text(selectLabel) }, onClick = { finish(onStartSelection) },
                leadingIcon = { Icon(HugeIcons.Tick01, null) })
            DropdownMenuItem(text = { Text("Удалить") }, onClick = { finish(onDelete) },
                leadingIcon = { Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
private fun ChatDrawerEmptyContent(folderSelected: Boolean, onNewChat: () -> Unit, searching: Boolean = false) {
    val state = chatDrawerEmptyState(folderSelected)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsIconCircle(if (folderSelected) HugeIcons.Folder01 else HugeIcons.Message01,
            SettingsSectionTone.Tertiary, 64.dp, 32.dp)
        Text(if (searching) stringResource(R.string.moru_drawer_no_search_results) else state.title, style = MaterialTheme.typography.titleMedium)
        Text(if (searching) stringResource(R.string.moru_drawer_try_another_query) else state.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!searching) FilledTonalButton(onClick = onNewChat) {
            Icon(HugeIcons.Add01, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(state.actionLabel)
        }
    }
}
