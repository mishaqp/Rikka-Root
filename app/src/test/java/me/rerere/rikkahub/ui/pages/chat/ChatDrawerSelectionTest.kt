package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDrawerSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchEditsLocalQueryAndClearKeepsItsIndependentAction() {
        val query = mutableStateOf("")
        compose.setContent { ScreenshotTheme {
            Box(Modifier.width(288.dp)) { ChatDrawerSearchBar({}, {}, query = query.value, onQueryChange = { query.value = it }) }
        } }
        compose.onNodeWithTag("chat_drawer_topic_search").performTextInput("СЕРВЕР")
        assertEquals("СЕРВЕР", query.value)
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        assertEquals("", query.value)
    }

    @Test fun selectionControlsDispatchEveryActionAndDisableEmptyActions() {
        var canceled = 0; var selectedAll = 0; var pinned = 0; var moved = 0; var deleted = 0
        val count = mutableStateOf(2)
        compose.setContent { ScreenshotTheme {
            Column(Modifier.width(288.dp)) {
                ChatDrawerSelectionHeader(count.value, false, { canceled++ }, { selectedAll++ })
                ChatDrawerSelectionActionBar(count.value, false, { pinned++ }, { moved++ }, { deleted++ })
            }
        } }
        compose.onNodeWithText("Выбрано: 2").assertExists()
        compose.onNodeWithText("Все").performClick()
        compose.onNodeWithText("Закрепить").performClick()
        compose.onNodeWithText("Перенести").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithContentDescription("Завершить выбор").performClick()
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(canceled, selectedAll, pinned, moved, deleted))
        compose.runOnIdle { count.value = 0 }
        compose.onNodeWithText("Закрепить").assertIsNotEnabled()
        compose.onNodeWithText("Перенести").assertIsNotEnabled()
        compose.onNodeWithText("Удалить").assertIsNotEnabled()
        assertEquals(listOf(1, 1, 1), listOf(pinned, moved, deleted))
    }

    @Test fun selectionRowTogglesWithoutNavigatingOrOpeningItsMenu() {
        val chat = Conversation(assistantId = Uuid.random(), title = "План поездки", messageNodes = emptyList())
        var opened = 0; var toggled = 0
        compose.setContent { ScreenshotTheme {
            Box(Modifier.width(288.dp)) {
                ConversationItem(chat, ChatDrawerRowMetadata("Последний ответ", true, false),
                    selected = false, loading = false, onDelete = {}, onRegenerateTitle = {}, onPin = {},
                    onMoveToAssistant = {}, onMoveToFolder = {}, onRename = {}, onClick = { opened++ },
                    selectionMode = true, selectionChecked = true, onToggleSelection = { toggled++ })
            }
        } }
        val row = compose.onNodeWithTag("chat_drawer_conversation_${chat.id}")
        row.assertIsSelected().performClick()
        row.performTouchInput { longClick() }
        compose.onNodeWithText("Переименовать").assertDoesNotExist()
        assertEquals(0, opened)
        assertEquals(1, toggled)
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height >= 48f)
        // Preview is still available to TalkBack although Moru's visual row only shows the title.
        compose.onNodeWithContentDescription("План поездки. Последний ответ", substring = true).assertExists()
    }

    @Test fun selectAllFiltersTheFullCollectionAcrossPagingAndFolders() {
        val assistant = Uuid.random(); val folder = Uuid.random()
        val all = (0 until 90).map { index ->
            Conversation(assistantId = assistant, title = if (index % 2 == 0) "Сервер $index" else "Дом $index",
                folderId = if (index % 3 == 0) folder else null, messageNodes = emptyList())
        }
        assertEquals(45, drawerSelectableConversations(all, "  сервер ", true, null).size)
        assertEquals(15, drawerSelectableConversations(all, "СЕРВЕР", false, folder).size)
        assertEquals(30, drawerSelectableConversations(all, "сервер", false, null).size)
        assertEquals(90, drawerSelectableConversations(all, "", true, null).size)
    }

    @Test fun selectAllKeepsSelectionsHiddenByTheCurrentQuery() {
        val assistant = Uuid.random()
        val hidden = Conversation(assistantId = assistant, title = "Дом", messageNodes = emptyList())
        val visible = listOf(Conversation(assistantId = assistant, title = "Работа", messageNodes = emptyList()),
            Conversation(assistantId = assistant, title = "Работа 2", messageNodes = emptyList()))
        val selected = drawerToggleAllSelection(mapOf(hidden.id to hidden), visible)
        assertEquals(3, selected.size)
        assertEquals(mapOf(hidden.id to hidden), drawerToggleAllSelection(selected, visible))
        assertEquals(selected, drawerToggleAllSelection(selected, emptyList()))
    }
}
