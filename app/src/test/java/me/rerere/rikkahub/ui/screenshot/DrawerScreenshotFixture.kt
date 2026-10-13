package me.rerere.rikkahub.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerAssistantCard
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerBottomBar
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerFolderControls
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerFrame
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerHeaderContent
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerSearchBar
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerSelectionActionBar
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerSelectionHeader
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerTopArea
import me.rerere.rikkahub.ui.pages.chat.ConversationList
import me.rerere.rikkahub.ui.pages.chat.ConversationListItem
import me.rerere.rikkahub.ui.pages.chat.ConversationSortButton
import me.rerere.rikkahub.ui.pages.chat.FolderBar
import me.rerere.rikkahub.ui.pages.chat.DrawerConversationSort
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

internal val drawerFixtureAssistantId = Uuid.parse("10000000-0000-0000-0000-000000000001")
internal val drawerFixtureTitles = listOf(
    "План поездки", "Настройка телефона", "Очень длинное название чата о настройке сервера и автоматизации",
    "Коротко", "Резервная копия", "Задачи на завтра", "Поиск документов", "Музыка в дороге",
    "Семейный календарь", "Рецепт ужина", "Прогулка по городу", "Идеи для выходных",
)

internal enum class DrawerScreenshotMode { Normal, Search, MultiSelect }

/** Mutable boundary state; clicks still go through the production drawer controls. */
internal class DrawerScreenshotState(mode: DrawerScreenshotMode = DrawerScreenshotMode.Normal) {
    var query by mutableStateOf(if (mode == DrawerScreenshotMode.Search) "настройк" else "")
    var selectionMode by mutableStateOf(mode == DrawerScreenshotMode.MultiSelect)
    var selectedIds by mutableStateOf(if (selectionMode) setOf(drawerFixtureId(1), drawerFixtureId(2)) else emptySet())
    var currentId by mutableStateOf(drawerFixtureId(2))
    var lastDestination: Screen? = null
    var newChatCalls = 0
    var pinCalls = 0
    var moveCalls = 0
    var deleteCalls = 0
    var nicknameCalls = 0
}

internal fun drawerFixtureId(index: Int) = Uuid.parse("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}")

@Composable
internal fun DrawerScreenshotFixture(state: DrawerScreenshotState = remember { DrawerScreenshotState() }) {
    val assistant = remember { Assistant(id = drawerFixtureAssistantId, name = "Ассистент по умолчанию") }
    val settings = remember { Settings(assistantId = assistant.id, assistants = listOf(assistant)) }
    val chats = remember {
        drawerFixtureTitles.mapIndexed { index, title ->
            Conversation(id = drawerFixtureId(index + 1),
                assistantId = assistant.id, title = title, messageNodes = emptyList(), isPinned = index == 0,
                createAt = Instant.parse("2026-01-02T12:00:00Z"), updateAt = Instant.parse("2026-01-02T12:00:00Z"))
        }
    }
    val visibleChats = remember(chats, state.query) { chats.filter { it.title.contains(state.query, ignoreCase = true) } }
    val items = remember(visibleChats) {
        buildList {
            if (visibleChats.any { it.isPinned }) add(ConversationListItem.PinnedHeader)
            var previousGroup: String? = null
            visibleChats.forEach { chat ->
                val index = chats.indexOf(chat)
                if (!chat.isPinned) {
                    val group = when {
                        index >= 11 -> LocalDate.of(2025, 12, 22) to "Раньше"
                        index >= 10 -> LocalDate.of(2025, 12, 29) to "На этой неделе"
                        index >= 8 -> LocalDate.of(2026, 1, 1) to "Вчера"
                        else -> LocalDate.of(2026, 1, 2) to "Сегодня"
                    }
                    if (group.second != previousGroup) add(ConversationListItem.DateHeader(group.first, group.second))
                    previousGroup = group.second
                }
                add(ConversationListItem.Item(chat,
                    previewText = when (index) {
                        2 -> "Запустил команду и проверил результат"
                        4 -> "Сценарий сохранил файлы в рабочую папку"
                        5 -> "Встреча в 10:00, после неё забрать заказ и отправить письмо"
                        else -> "Хорошо, продолжим обсуждение"
                    }, hasTools = index == 2 || index == 4, hasWorkflows = index == 4))
            }
        }
    }
    val paged = remember(items) {
        // A completed one-page query must publish its load states as the real
        // database Pager does. StaticList without states leaves refresh Loading.
        flowOf(PagingData.from(items, sourceLoadStates = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = false),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )))
    }.collectAsLazyPagingItems()
    val folders = remember { listOf("Работа", "Личное", "Идеи").mapIndexed { index, name ->
        Folder(id = Uuid.parse("30000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"),
            assistantId = assistant.id, name = name)
    } }
    Box(Modifier.fillMaxSize().testTag("drawer_fixture")) {
        ChatDrawerFrame {
            ChatDrawerTopArea {
                if (state.selectionMode) {
                    ChatDrawerSelectionHeader(state.selectedIds.size,
                        allSelected = visibleChats.isNotEmpty() && visibleChats.all { it.id in state.selectedIds },
                        onCancel = { state.selectionMode = false; state.selectedIds = emptySet() },
                        onToggleSelectAll = {
                            state.selectedIds = if (visibleChats.all { it.id in state.selectedIds }) emptySet()
                            else visibleChats.map { it.id }.toSet()
                        })
                } else {
                    ChatDrawerSearchBar(onNavigate = { state.lastDestination = it }, onNewChat = { state.newChatCalls++ },
                        query = state.query, onQueryChange = { state.query = it })
                }
                ChatDrawerAssistantCard(settings = settings, onUpdateSettings = {}, onNavigate = { state.lastDestination = it },
                    avatarContent = { _, _, modifier, _ -> FixtureAvatar("А", modifier) })
                ChatDrawerFolderControls(
                    sort = { ConversationSortButton(DrawerConversationSort.NEWEST, {}, ConversationSortOrder.UPDATE_TIME, {}) },
                    folders = { FolderBar(folders, null, true, {}, {}, {}, {}, {}, Modifier.fillMaxWidth()) })
            }
            ConversationList(current = chats.first { it.id == state.currentId }, conversations = paged, conversationJobs = emptyList(),
                listState = rememberLazyListState(), modifier = Modifier.fillMaxWidth().weight(1f).testTag("drawer_fixture_list"),
                selectionMode = state.selectionMode, selectedConversationIds = state.selectedIds,
                onClick = { state.currentId = it.id },
                onStartSelection = { state.selectionMode = true; state.selectedIds = setOf(it.id) },
                onToggleSelection = { chat -> state.selectedIds = if (chat.id in state.selectedIds) state.selectedIds - chat.id else state.selectedIds + chat.id },
                searchQuery = state.query)
            if (state.selectionMode) {
                ChatDrawerSelectionActionBar(state.selectedIds.size,
                    allSelectedPinned = state.selectedIds.isNotEmpty() && chats.filter { it.id in state.selectedIds }.all { it.isPinned },
                    onPin = { state.pinCalls++ }, onMove = { state.moveCalls++ }, onDelete = { state.deleteCalls++ })
            } else {
                ChatDrawerHeaderContent(nickname = "Пользователь",
                    rootChip = null, hasUpdate = false, onEditNickname = { state.nicknameCalls++ },
                    onNavigate = { state.lastDestination = it }, modifier = Modifier.padding(horizontal = 16.dp),
                    greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                    avatar = { FixtureAvatar("П", it) })
                ChatDrawerBottomBar(assistant.id.toString(), onNavigate = { state.lastDestination = it })
            }
        }
    }
}

@Composable
private fun FixtureAvatar(letter: String, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(letter, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.titleMedium) }
    }
}
