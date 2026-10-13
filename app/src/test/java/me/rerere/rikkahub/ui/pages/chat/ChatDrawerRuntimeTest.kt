package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.room.Room
import com.dokar.sonner.rememberToasterState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.utils.NightlyUpdateState
import me.rerere.rikkahub.utils.UpdateChecker
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual drawer, VM, paging and Room; ChatVM/service remain external mutation boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDrawerRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var drawer: ChatDrawerVM
    private val assistant = Assistant(name = "Первый ассистент")
    private val current = Conversation(assistantId = assistant.id, title = "Текущий чат", messageNodes = emptyList())
    private val target = Conversation(assistantId = assistant.id, title = "Сервер", messageNodes = emptyList())
    private val other = Conversation(assistantId = assistant.id, title = "Дом",
        customSystemPrompt = "Сохранить настройки разговора",
        messageNodes = listOf(MessageNode.of(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("Сохранённая история другого чата"))))))
    private val settings = MutableStateFlow(Settings(assistantId = assistant.id, assistants = listOf(assistant)))
    private var open by mutableStateOf(true)
    private val navigation = mutableListOf<NavKey>(Screen.Chat(current.id.toString()))

    @Before fun install() {
        val app = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = mockk<FilesManager>(relaxed = true)
        database.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database))
        runBlocking { listOf(current, target, other).forEach { repository.insertConversation(it) } }
        val store = mockk<SettingsStore>(relaxed = true)
        every { store.settingsFlow } returns settings
        val service = mockk<ChatService>(relaxed = true)
        drawer = ChatDrawerVM(app, store, repository, FolderRepository(database.folderDao(), database.conversationDao()), service, SavedStateHandle())
        val root = mockk<RootShellManager>(relaxed = true)
        every { root.status } returns MutableStateFlow(RootStatus.UNCHECKED)
        val checker = mockk<UpdateChecker>(relaxed = true)
        every { checker.nightlyState } returns MutableStateFlow(NightlyUpdateState())
        startKoin { modules(module {
            single { repository }; single { drawer }; single { files }; single { root }; single { checker }
        }) }
    }

    @After fun close() { drawer.viewModelScope.cancel(); stopKoin(); database.close() }

    private fun render() {
        val vm = mockk<ChatVM>(relaxed = true)
        every { vm.conversationJobs } returns MutableStateFlow(emptyMap())
        every { vm.updateSettings(any()) } answers {
            settings.value = firstArg<(Settings) -> Settings>().invoke(settings.value)
            Job().apply { complete() }
        }
        val navigator = Navigator(navigation)
        compose.setContent { ScreenshotTheme {
            val displayed by settings.collectAsState()
            CompositionLocalProvider(LocalSettings provides displayed, LocalToaster provides rememberToasterState(),
                LocalNavController provides navigator) {
                Box(Modifier.requiredSize(270.dp, 800.dp)) {
                    ChatDrawerContent(navigator, vm, displayed, current, open, onRequestClose = { open = false })
                }
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сервер").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun selectTarget() {
        val row = compose.onNodeWithTag("chat_drawer_conversation_${target.id}")
        row.performTouchInput { longClick() }
        compose.onNodeWithText("Выбрать").performClick()
        compose.onNodeWithText("Выбрано: 1").assertExists()
    }

    private fun storedIds() = runBlocking { repository.getConversationsOfAssistant(assistant.id).let { flow ->
        flow.first().map { it.id }.toSet()
    } }

    private fun storedConversations() = runBlocking {
        repository.getConversationsOfAssistant(assistant.id).first()
            .associate { it.id to repository.getConversationById(it.id) }
    }

    @Test fun deleteCancelKeepsSelectionAndAllowsFolderMoveCancel() {
        val before = storedConversations()
        render(); selectTarget()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        assertEquals(setOf(current.id, target.id, other.id), storedIds())
        compose.onNodeWithText("Перенести").performClick()
        compose.onNodeWithText("В папку").performClick()
        compose.onNodeWithText("Переместить в папку").assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("Переместить в папку").assertDoesNotExist()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        assertEquals(before, storedConversations())
    }

    @Test fun searchSelectAllDeleteConfirmDeletesOnlyVisibleMatch() {
        val before = storedConversations()
        render()
        compose.onNodeWithTag("chat_drawer_topic_search").performTextInput("СЕРВЕР")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Дом").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Текущий чат").assertDoesNotExist()
        selectTarget()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Снять").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Снять").performClick()
        compose.onNodeWithText("Выбрано: 0").assertExists()
        compose.onNode(hasText("Все") and hasAnyAncestor(hasTestTag("chat_drawer_selection_header"))).performClick()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        compose.onNodeWithText("Удалить").performClick()
        compose.onAllNodesWithText("Удалить").onLast().performClick()
        compose.waitUntil(10_000) { storedIds() == setOf(current.id, other.id) }
        compose.onNodeWithTag("chat_drawer_selection_header").assertDoesNotExist()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Дом").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before - target.id, storedConversations())
    }

    @Test fun assistantSelectionNavigatesToItsExistingChatAfterSettingsCommit() {
        val second = Assistant(name = "Второй ассистент")
        val secondChat = Conversation(assistantId = second.id, title = "Чат второго", messageNodes = emptyList())
        runBlocking { repository.insertConversation(secondChat) }
        val secondBefore = runBlocking { repository.getConversationById(secondChat.id) }
        settings.value = settings.value.copy(assistants = listOf(assistant, second),
            displaySetting = settings.value.displaySetting.copy(newChatOnAssistantSwitch = false))
        render()
        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Второй ассистент").performClick()
        compose.waitUntil(10_000) { navigation.lastOrNull() == Screen.Chat(secondChat.id.toString()) }
        assertEquals(second.id, settings.value.assistantId)
        assertFalse(open)
        assertEquals(setOf(current.id, target.id, other.id), storedIds())
        assertEquals(secondBefore, runBlocking { repository.getConversationById(secondChat.id) })
    }

    @Test fun closingDrawerDismissesPendingBatchFolderMoveAndRetainsChats() {
        render(); selectTarget()
        compose.onNodeWithText("Перенести").performClick()
        compose.onNodeWithText("В папку").performClick()
        compose.onNodeWithText("Переместить в папку").assertExists()
        compose.runOnIdle { open = false }
        compose.onNodeWithText("Переместить в папку").assertDoesNotExist()
        compose.runOnIdle { open = true }
        selectTarget()
        compose.onNodeWithText("Перенести").performClick()
        compose.onNodeWithText("К ассистенту").assertExists()
        assertEquals(setOf(current.id, target.id, other.id), storedIds())
    }

    @Test fun closingDrawerDismissesPendingBatchDeleteInsteadOfLeavingEmptyConfirmation() {
        render(); selectTarget()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Отмена").assertExists()
        compose.runOnIdle { open = false }
        compose.onNodeWithText("Отмена").assertDoesNotExist()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("chat_drawer_selection_header").assertDoesNotExist()
        assertEquals(setOf(current.id, target.id, other.id), storedIds())
    }
}
