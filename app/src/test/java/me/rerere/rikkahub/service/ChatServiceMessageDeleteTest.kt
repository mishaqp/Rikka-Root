package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.web.NotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Public service deletion with real Room persistence and the production tree transformation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceMessageDeleteTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private val sqlWrites = AtomicInteger()
    private val assistant = Assistant()
    private val candidates = listOf(UIMessage.assistant("A"), UIMessage.assistant("B"), UIMessage.assistant("C"))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ ->
                if (listOf("INSERT", "UPDATE", "DELETE").any { sql.trimStart().startsWith(it, ignoreCase = true) }) {
                    sqlWrites.incrementAndGet()
                }
            }, Executor { it.run() })
            .build()
        val files = mockk<FilesManager>(relaxed = true)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, mockk<MessageFtsManager>(relaxed = true))
        val settings = MutableStateFlow(Settings(init = false, assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns settings
        every { settingsStore.settingsFlowRaw } returns settings
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = settingsStore, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = mockk(relaxed = true), translationHandler = mockk(relaxed = true),
            templateTransformer = mockk(relaxed = true), providerManager = mockk(relaxed = true),
            chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true), filesManager = files,
            workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = mockk(relaxed = true), tokenBudgetStore = mockk(relaxed = true),
            subAgentRegistry = mockk(relaxed = true))
    }

    @After fun tearDown() {
        service.cleanup()
        appScope.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test(timeout = 30_000)
    fun `deleting first candidate keeps selected middle candidate in live state and Room`() = runBlocking {
        val source = seed(selected = 1)
        service.deleteMessage(source.id, candidates[0].id)

        val live = service.getConversationFlow(source.id).value
        val reopened = repository.getConversationById(source.id)!!
        assertEquals("Live and Room must both keep B selected at index 0 after deleting A",
            listOf(0 to candidates[1].id, 0 to candidates[1].id),
            listOf(live.messageNodes[1], reopened.messageNodes[1]).map { it.selectIndex to it.currentMessage.id })
        assertAfterDelete(source, source.messageNodes[1].copy(messages = candidates.drop(1), selectIndex = 0))
    }

    @Test(timeout = 30_000)
    fun `deleting before selected last candidate adjusts its index`() = runBlocking {
        assertCandidateDeletion(selected = 2, deleted = 0, expectedSelected = 1)
    }

    @Test(timeout = 30_000)
    fun `deleting after selected candidate keeps its identity and index`() = runBlocking {
        assertCandidateDeletion(selected = 0, deleted = 1, expectedSelected = 0)
    }

    @Test(timeout = 30_000)
    fun `deleting last unselected candidate keeps selected middle candidate`() = runBlocking {
        assertCandidateDeletion(selected = 1, deleted = 2, expectedSelected = 1)
    }

    @Test(timeout = 30_000)
    fun `deleting selected first candidate selects its old position`() = runBlocking {
        assertCandidateDeletion(selected = 0, deleted = 0, expectedSelected = 0)
    }

    @Test(timeout = 30_000)
    fun `deleting selected middle candidate selects its old position`() = runBlocking {
        assertCandidateDeletion(selected = 1, deleted = 1, expectedSelected = 1)
    }

    @Test(timeout = 30_000)
    fun `deleting selected last candidate selects previous last candidate`() = runBlocking {
        assertCandidateDeletion(selected = 2, deleted = 2, expectedSelected = 1)
    }

    @Test(timeout = 30_000)
    fun `deleting only candidate removes its node and preserves other nodes and metadata`() = runBlocking {
        val source = seed(selected = 0, messages = candidates.take(1))
        service.deleteMessage(source.id, candidates[0].id)
        val expected = source.copy(messageNodes = listOf(source.messageNodes.first(), source.messageNodes.last()))
        assertEquals(expected, service.getConversationFlow(source.id).value)
        assertEquals(expected, repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `deleting unselected alternative preserves checkpoints for the unchanged selected history`() = runBlocking {
        val source = seed(selected = 1, checkpoints = true)
        service.deleteMessage(source.id, candidates[0].id)
        val expected = source.copy(messageNodes = source.messageNodes.map { node ->
            if (node.messages == candidates) node.copy(messages = candidates.drop(1), selectIndex = 0) else node
        })
        assertEquals(expected, service.getConversationFlow(source.id).value)
        assertEquals(expected, repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `deleting selected alternative retains earlier checkpoint and invalidates later checkpoint`() = runBlocking {
        val source = seed(selected = 1, checkpoints = true)
        service.deleteMessage(source.id, candidates[1].id)
        val expected = source.copy(messageNodes = source.messageNodes.dropLast(1).map { node ->
            if (node.messages == candidates) node.copy(messages = listOf(candidates[0], candidates[2]), selectIndex = 1) else node
        })
        assertEquals(expected, service.getConversationFlow(source.id).value)
        assertEquals(expected, repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `missing message is a no-op when allowed and throws by default without writing`() = runBlocking {
        val source = seed(selected = 1)
        val live = service.getConversationFlow(source.id).value
        val row = database.conversationDao().getConversationById(source.id.toString())
        val nodes = database.messageNodeDao().getNodesOfConversation(source.id.toString())
        val writesBefore = sqlWrites.get()
        assertTrue("The SQLite observer must see fixture persistence", writesBefore > 0)
        val missing = Uuid.random()
        service.deleteMessage(source.id, missing, failIfMissing = false)
        assertThrows(NotFoundException::class.java) { runBlocking { service.deleteMessage(source.id, missing) } }

        assertSame(live, service.getConversationFlow(source.id).value)
        assertEquals(row, database.conversationDao().getConversationById(source.id.toString()))
        assertEquals(nodes, database.messageNodeDao().getNodesOfConversation(source.id.toString()))
        assertEquals("Missing-message deletion must not issue any SQLite writes", writesBefore, sqlWrites.get())
    }

    private suspend fun assertCandidateDeletion(selected: Int, deleted: Int, expectedSelected: Int) {
        val source = seed(selected)
        service.deleteMessage(source.id, candidates[deleted].id)
        assertAfterDelete(source, source.messageNodes[1].copy(
            messages = candidates.filterIndexed { index, _ -> index != deleted }, selectIndex = expectedSelected))
    }

    private suspend fun assertAfterDelete(source: Conversation, branch: MessageNode) {
        val expected = source.copy(messageNodes = listOf(source.messageNodes.first(), branch, source.messageNodes.last()))
        assertEquals("Live state must preserve surviving message and node IDs and all unrelated fields",
            expected, service.getConversationFlow(source.id).value)
        assertEquals("Room must persist the same candidate identity, IDs and metadata", expected,
            repository.getConversationById(source.id))
    }

    private suspend fun seed(selected: Int, messages: List<UIMessage> = candidates, checkpoints: Boolean = false): Conversation {
        val first = UIMessage.user("Question").toMessageNode()
        val branch = MessageNode(messages = messages, selectIndex = selected)
        val last = UIMessage.user("Follow-up").toMessageNode()
        val nodes = if (checkpoints) listOf(first,
            UIMessage.user("Earlier summary").copy(isContextCheckpoint = true).toMessageNode(), branch, last,
            UIMessage.user("Later summary").copy(isContextCheckpoint = true).toMessageNode())
        else listOf(first, branch, last)
        val source = Conversation(assistantId = assistant.id, title = "Alternatives", messageNodes = nodes,
            config = ConversationConfig(enabledSkills = setOf("fixture-skill")),
            folderId = Uuid.random(), isPinned = true, customSystemPrompt = "Custom system prompt",
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
            workspaceCwd = "/workspace/chat", chatSuggestions = listOf("Keep suggestion"),
            createAt = Instant.ofEpochMilli(1_000), updateAt = Instant.ofEpochMilli(2_000))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        return source
    }
}
