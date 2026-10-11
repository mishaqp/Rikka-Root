package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusChip
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

/** Covers production drawer chrome, without a root manager or an update checker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDrawerChromeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `header contains identity and settings without a model shortcut`() {
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerHeaderContent(
                        nickname = "Пользователь",
                        rootChip = null,
                        hasUpdate = false,
                        onEditNickname = {},
                        onNavigate = {},
                        greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                        avatar = { Box(it.testTag("header_avatar")) },
                    )
                }
            }
        }

        compose.onNodeWithText("Пользователь", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Добрый вечер", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Настройки").assertExists()
        compose.onNodeWithText("Модель этого чата", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Текущая модель: Модель этого чата. Выбрать модель по умолчанию",
        ).assertDoesNotExist()
        assertEquals("Identity header uses a compact 48dp row", 48f,
            compose.onNodeWithTag("chat_drawer_header").fetchSemanticsNode().boundsInRoot.height, .5f)
    }

    @Test
    fun `history action is inside the search field and keeps a separate destination`() {
        val destinations = mutableListOf<Screen>()
        var newChats = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }

        val search = compose.onNodeWithContentDescription("Поиск по сообщениям")
        val history = compose.onNodeWithContentDescription("История чатов")
        val fieldBounds = search.fetchSemanticsNode().boundsInRoot
        val historyBounds = history.fetchSemanticsNode().boundsInRoot
        assertTrue("History extends beyond the search field", historyBounds.right <= fieldBounds.right)
        assertTrue("History must be a trailing action inside the field", historyBounds.left > fieldBounds.left)
        assertEquals(fieldBounds.center.y, historyBounds.center.y, .5f)
        assertTrue("History keeps its 48dp touch target", history.fetchSemanticsNode().touchBoundsInRoot.height >= 48f)

        search.performClick()
        history.performClick()
        assertEquals(listOf(Screen.MessageSearch, Screen.History), destinations)
        assertEquals(0, newChats)
    }

    @Test
    fun `compact new chat action is next to search and calls only its own callback`() {
        val destinations = mutableListOf<Screen>()
        var newChats = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }

        val search = compose.onNodeWithContentDescription("Поиск по сообщениям").fetchSemanticsNode()
        val newChat = compose.onNodeWithContentDescription("Новый чат")
        val action = newChat.fetchSemanticsNode()
        assertTrue("New chat sits to the right of the search field", action.boundsInRoot.left > search.boundsInRoot.right)
        assertEquals("New chat shares the search row", search.boundsInRoot.center.y, action.boundsInRoot.center.y, .5f)
        assertEquals("New chat has a compact 44dp visual", 44f, action.boundsInRoot.width, .5f)
        assertEquals("New chat has a compact 44dp visual", 44f, action.boundsInRoot.height, .5f)
        assertTrue("New chat retains a 48dp touch target", action.touchBoundsInRoot.width >= 48f)
        assertTrue("New chat retains a 48dp touch target", action.touchBoundsInRoot.height >= 48f)
        compose.onNodeWithText("Новый чат").assertDoesNotExist()

        newChat.performClick()
        assertEquals(1, newChats)
        assertTrue("New chat must not navigate to search or history", destinations.isEmpty())
    }

    @Test
    fun `new chat long press shows its Russian tooltip without opening a chat`() {
        var newChats = 0
        val destinations = mutableListOf<Screen>()
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp).padding(top = 64.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Новый чат").performTouchInput { longClick() }
        compose.onNodeWithText("Новый чат").assertExists()
        assertEquals(0, newChats)
        assertTrue(destinations.isEmpty())
    }

    @Test
    fun `header cached root update settings and nickname actions retain their destinations`() {
        val destinations = mutableListOf<Screen>()
        var edits = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerHeaderContent(
                        nickname = "Пользователь",
                        rootChip = SettingsDashboardStatusChip("Активен", SettingsDashboardStatusTone.Success),
                        hasUpdate = true,
                        onEditNickname = { edits++ },
                        onNavigate = { destinations += it },
                        greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                        avatar = { Box(it.testTag("header_avatar")) },
                    )
                }
            }
        }

        val avatar = compose.onNodeWithTag("header_avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithContentDescription("Настройки").fetchSemanticsNode()
        assertEquals(40f, avatar.width, .5f)
        assertEquals(40f, settings.boundsInRoot.width, .5f)
        assertEquals(avatar.center.y, settings.boundsInRoot.center.y, .5f)
        assertTrue(settings.touchBoundsInRoot.width >= 48f)
        compose.onNodeWithText("Пользователь").performClick()
        compose.onNodeWithContentDescription("Root: Активен").performClick()
        compose.onNodeWithContentDescription("Есть обновление. Открыть сведения").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        assertEquals(1, edits)
        assertEquals(listOf(Screen.SettingRoot, Screen.SettingUpdate, Screen.Setting), destinations)
    }

    @Test
    fun `assistant avatar aligns with the drawer inset instead of a second inner inset`() {
        val assistant = Assistant(name = "Ассистент")
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(320.dp).padding(horizontal = 16.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings,
                        onUpdateSettings = {},
                        onNavigate = {},
                        avatarContent = { _, _, modifier, _ -> Box(modifier.testTag("assistant_avatar")) },
                    )
                }
            }
        }

        val row = compose.onNodeWithTag("chat_drawer_assistant").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("assistant_avatar", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Assistant avatar aligns to the parent 16dp inset", 16f, avatar.left, .5f)
        assertEquals("Assistant avatar has no extra inset", row.left, avatar.left, .5f)
        assertEquals("Compact assistant row remains 48dp", 48f, row.height, .5f)
        assertEquals("Assistant avatar remains 28dp", 28f, avatar.width, .5f)
    }

    @Test
    fun `assistant row wraps its full name at large font size`() {
        val assistant = Assistant(name = "Ассистент для планирования семейных дел")
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            ScreenshotTheme(fontScale = scale.floatValue) {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings,
                        onUpdateSettings = {},
                        onNavigate = {},
                        avatarContent = { _, _, modifier, _ -> Box(modifier) },
                    )
                }
            }
        }
        compose.runOnIdle { scale.floatValue = 1.3f }
        val layouts = mutableListOf<TextLayoutResult>()
        val text = compose.onNodeWithText(assistant.name, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        assertTrue("Assistant name wraps", layouts.single().lineCount > 1)
        assertTrue("Assistant name is not truncated", !layouts.single().hasVisualOverflow)
        val row = compose.onNodeWithTag("chat_drawer_assistant").fetchSemanticsNode().boundsInRoot
        assertTrue("Large assistant name stays inside the row", text.boundsInRoot.bottom <= row.bottom)
    }

    @Test
    fun `assistant dropdown retains combined tag filters selection edit and management`() {
        val workTag = Tag(Uuid.parse("00000000-0000-0000-0000-000000000011"), "Работа")
        val homeTag = Tag(Uuid.parse("00000000-0000-0000-0000-000000000012"), "Дом")
        val work = Assistant(name = "Рабочий", tags = listOf(workTag.id))
        val home = Assistant(name = "Домашний", tags = listOf(homeTag.id))
        val general = Assistant(name = "Общий")
        val settings = mutableStateOf(Settings(assistantId = work.id, assistants = listOf(work, home, general),
            assistantTags = listOf(workTag, homeTag)))
        val destinations = mutableListOf<Screen>()
        compose.setContent {
            ScreenshotTheme(fontScale = 1.3f) {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings.value,
                        onUpdateSettings = { settings.value = it },
                        onNavigate = { destinations += it },
                        avatarContent = { _, _, modifier, _ -> Box(modifier) },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Работа").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Рабочий").assertExists()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки ассистента: Общий").assertDoesNotExist()
        compose.onNodeWithText("Дом").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").assertExists()
        compose.onNodeWithText("Работа").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Рабочий").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").performClick()
        assertEquals(listOf(Screen.AssistantDetail(home.id.toString())), destinations)

        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Домашний").performClick()
        assertEquals(home.id, settings.value.assistantId)
        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Управление ассистентами").performClick()
        assertEquals(listOf(Screen.AssistantDetail(home.id.toString()), Screen.Assistant), destinations)
    }
}
