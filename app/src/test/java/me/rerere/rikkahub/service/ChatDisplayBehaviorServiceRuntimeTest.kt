package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
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
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public ChatService operations, live session and real Room; generation is a recorded boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDisplayBehaviorServiceRuntimeTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private lateinit var settings: MutableStateFlow<Settings>
    private val assistant = Assistant(enableWebSearch = false)
    private val model = Model(modelId = "fixture")
    private var requestedTexts = emptyList<String>()

    @Before fun install() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = mockk<FilesManager>(relaxed = true)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, files, mockk<MessageFtsManager>(relaxed = true))
        settings = MutableStateFlow(Settings(init = false, enableSuggestion = false, assistants = listOf(assistant), assistantId = assistant.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
        val store = mockk<SettingsStore>(relaxed = true)
        every { store.settingsFlow } returns settings
        every { store.settingsFlowRaw } returns settings
        val budgets = mockk<TokenBudgetStore>()
        every { budgets.getLedger(any(), any(), any()) } returns TokenBudgetLedger(enabled = false)
        val generation = mockk<GenerationLoop>()
        every { generation.generateText(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            prepareToolApproval = any(), beforeModelRequest = any()) } answers {
            val input = arg<List<UIMessage>>(2)
            requestedTexts = input.map { it.toText() }
            flowOf(GenerationChunk.Messages(input + UIMessage.assistant("Новый ответ")))
        }
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = store, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = generation, translationHandler = mockk(relaxed = true), templateTransformer = mockk(relaxed = true),
            providerManager = mockk(relaxed = true), chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true),
            filesManager = files, workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = mockk(relaxed = true), tokenBudgetStore = budgets, subAgentRegistry = mockk(relaxed = true))
    }

    @After fun close() {
        service.cleanup()
        appScope.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed(): Conversation {
        val branch = MessageNode(messages = listOf(UIMessage.assistant("Первый ответ"), UIMessage.assistant("Выбранный ответ")), selectIndex = 1)
        val source = Conversation(assistantId = assistant.id, title = "Исходный чат", config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("Вопрос").toMessageNode(), branch, UIMessage.user("Продолжение").toMessageNode()),
            createAt = Instant.ofEpochMilli(1_000), updateAt = Instant.ofEpochMilli(2_000))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        return source
    }

    private fun assertPersisted(expected: Conversation, actual: Conversation) {
        // Conversation metadata uses Room's existing millisecond converters; messages retain JSON precision.
        assertEquals(expected.createAt.toEpochMilli(), actual.createAt.toEpochMilli())
        assertEquals(expected.updateAt.toEpochMilli(), actual.updateAt.toEpochMilli())
        assertEquals(expected.copy(createAt = actual.createAt, updateAt = actual.updateAt), actual)
    }

    @Test(timeout = 30_000) fun forkVersionsSettingReachesRealServiceAndRoomWithoutChangingSource() = runBlocking {
        val source = seed()
        for (keep in listOf(true, false)) {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(forkKeepMessageVersions = keep))
            val fork = service.forkConversationAtMessage(source.id, source.messageNodes[1].currentMessage.id)
            val persisted = requireNotNull(repository.getConversationById(fork.id))
            assertPersisted(fork, persisted)
            assertEquals(2, fork.messageNodes.size)
            assertEquals(source.config, fork.config)
            assertNotEquals(source.messageNodes[1].id, fork.messageNodes[1].id)
            assertEquals(if (keep) source.messageNodes[1].messages else listOf(source.messageNodes[1].currentMessage), fork.messageNodes[1].messages)
            assertEquals(if (keep) 1 else 0, fork.messageNodes[1].selectIndex)
            assertEquals(source, repository.getConversationById(source.id))
            assertEquals(source, service.getConversationFlow(source.id).value)
        }
    }

    @Test(timeout = 30_000) fun regenerationSettingsReachLiveStateAndRoomAndKeepRequestPrefix() = runBlocking {
        for (deleteTrailing in listOf(false, true)) {
            val source = seed()
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(regenerateDeleteTrailingMessages = deleteTrailing))
            service.regenerateAtMessage(source.id, source.messageNodes[1].currentMessage)
            service.getGenerationJobStateFlow(source.id).first()?.join()
            val live = service.getConversationFlow(source.id).value
            assertEquals(listOf("Вопрос"), requestedTexts)
            assertEquals(if (deleteTrailing) 2 else 3, live.messageNodes.size)
            assertEquals(listOf("Первый ответ", "Выбранный ответ", "Новый ответ"), live.messageNodes[1].messages.map { it.toText() })
            assertEquals("Новый ответ", live.messageNodes[1].currentMessage.toText())
            assertPersisted(live, requireNotNull(repository.getConversationById(source.id)))
            if (!deleteTrailing) assertEquals(source.messageNodes.last(), live.messageNodes.last())
            assertTrue(service.errors.value.isEmpty())
        }
    }
}
