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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
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

@Composable
internal fun DrawerScreenshotFixture() {
    val assistant = remember { Assistant(id = drawerFixtureAssistantId, name = "Ассистент по умолчанию") }
    val settings = remember { Settings(assistantId = assistant.id, assistants = listOf(assistant)) }
    val chats = remember {
        drawerFixtureTitles.mapIndexed { index, title ->
            Conversation(id = Uuid.parse("20000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"),
                assistantId = assistant.id, title = title, messageNodes = emptyList(), isPinned = index == 0,
                createAt = Instant.parse("2026-01-02T12:00:00Z"), updateAt = Instant.parse("2026-01-02T12:00:00Z"))
        }
    }
    val items = remember {
        buildList {
            add(ConversationListItem.PinnedHeader)
            chats.forEachIndexed { index, chat ->
                if (index == 1) add(ConversationListItem.DateHeader(LocalDate.of(2026, 1, 2), "Сегодня"))
                if (index == 8) add(ConversationListItem.DateHeader(LocalDate.of(2026, 1, 1), "Вчера"))
                if (index == 10) add(ConversationListItem.DateHeader(LocalDate.of(2025, 12, 29), "На этой неделе"))
                if (index == 11) add(ConversationListItem.DateHeader(LocalDate.of(2025, 12, 22), "Раньше"))
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
    val paged = remember(items) { flowOf(PagingData.from(items)) }.collectAsLazyPagingItems()
    val folders = remember { listOf("Работа", "Личное", "Идеи").mapIndexed { index, name ->
        Folder(id = Uuid.parse("30000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"),
            assistantId = assistant.id, name = name)
    } }
    Box(Modifier.fillMaxSize().testTag("drawer_fixture")) {
        ChatDrawerFrame {
            ConversationList(current = chats[1], conversations = paged, conversationJobs = emptyList(),
                listState = rememberLazyListState(), modifier = Modifier.fillMaxWidth().weight(1f),
                headerContent = {
                    ChatDrawerTopArea {
                        ChatDrawerHeaderContent(nickname = "Пользователь",
                            rootChip = null, hasUpdate = false, onEditNickname = {}, onNavigate = {},
                            greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                            avatar = { FixtureAvatar("П", it) })
                        ChatDrawerSearchBar(onNavigate = {}, onNewChat = {})
                        ChatDrawerAssistantCard(settings = settings, onUpdateSettings = {}, onNavigate = {},
                            avatarContent = { _, _, modifier, _ -> FixtureAvatar("А", modifier) })
                        ChatDrawerFolderControls(
                            sort = { ConversationSortButton(DrawerConversationSort.NEWEST, {}, ConversationSortOrder.UPDATE_TIME, {}) },
                            folders = { FolderBar(folders, null, true, {}, {}, {}, {}, {}, Modifier.fillMaxWidth()) })
                    }
                })
            ChatDrawerBottomBar(assistant.id.toString(), onNavigate = {})
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
