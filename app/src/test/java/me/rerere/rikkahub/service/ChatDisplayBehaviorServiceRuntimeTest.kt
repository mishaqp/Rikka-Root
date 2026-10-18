package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
import org.junit.Assert.assertFalse
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
    private var requestedIds = emptyList<kotlin.uuid.Uuid>()
    private var respond: (List<UIMessage>) -> Flow<GenerationChunk> = { input ->
        flowOf(GenerationChunk.Messages(input + UIMessage.assistant("Новый ответ")))
    }

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
            prepareToolApproval = any(), beforeModelRequest = any(), approveEverything = any()) } answers {
            val input = arg<List<UIMessage>>(2)
            requestedTexts = input.map { it.toText() }
            requestedIds = input.map { it.id }
            respond(input)
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

    @Test(timeout = 30_000) fun regeneratingUserMessageFromChangedUiCopyKeepsHistoryAndTruncatesById() = runBlocking {
        val source = seed()
        // A streaming translation makes the UI copy differ from the stored message.
        service.regenerateAtMessage(source.id, source.messageNodes[0].currentMessage.copy(translation = "Question"))
        service.getGenerationJobStateFlow(source.id).first()?.join()
        val live = service.getConversationFlow(source.id).value
        assertEquals(listOf("Вопрос"), requestedTexts)
        assertEquals(listOf("Вопрос", "Новый ответ"), live.currentMessages.map { it.toText() })
        assertEquals(source.messageNodes[0], live.messageNodes[0])
        assertPersisted(live, requireNotNull(repository.getConversationById(source.id)))
        assertTrue(service.errors.value.isEmpty())
    }

    @Test(timeout = 30_000) fun regeneratingMissingMessageReportsErrorAndNeverSavesTruncatedHistory() = runBlocking {
        val source = seed()
        service.regenerateAtMessage(source.id, UIMessage.user("Сообщение из другого чата"))
        service.getGenerationJobStateFlow(source.id).first()?.join()
        assertEquals(source.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
        assertEquals(source.messageNodes, requireNotNull(repository.getConversationById(source.id)).messageNodes)
        assertTrue("A missing regeneration target needs a visible error", service.errors.value.any { it.conversationId == source.id })
        assertTrue(requestedTexts.isEmpty())
    }

    @Test(timeout = 30_000) fun regeneratingAssistantFromChangedUiCopyWritesIntoItsOwnNode() = runBlocking {
        val source = seed()
        service.regenerateAtMessage(source.id, source.messageNodes[1].currentMessage.copy(translation = "Translated"))
        service.getGenerationJobStateFlow(source.id).first()?.join()
        val live = service.getConversationFlow(source.id).value
        assertEquals(listOf("Вопрос"), requestedTexts)
        assertEquals(3, live.messageNodes.size)
        assertEquals(listOf("Первый ответ", "Выбранный ответ", "Новый ответ"), live.messageNodes[1].messages.map { it.toText() })
        assertEquals(source.messageNodes.last(), live.messageNodes.last())
        assertTrue(service.errors.value.isEmpty())
    }

    private suspend fun seedEarlierToolReply(state: ToolApprovalState): Conversation {
        val reply = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("Нужно подтверждение"),
            UIMessagePart.Tool("earlier-call", "ask_user", "{}", approvalState = state)))
        val source = Conversation(assistantId = assistant.id, title = "Чат с инструментом", config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("Вопрос").toMessageNode(),
                MessageNode(messages = listOf(UIMessage.assistant("Первый ответ"), reply), selectIndex = 1),
                UIMessage.user("Продолжение").toMessageNode(), UIMessage.assistant("Второй ответ").toMessageNode()),
            createAt = Instant.ofEpochMilli(1_000), updateAt = Instant.ofEpochMilli(2_000))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        return source
    }

    @Test(timeout = 30_000) fun handledToolInEarlierRegeneratedReplyResumesThatReply() = runBlocking {
        val source = seedEarlierToolReply(ToolApprovalState.Pending)
        val reply = source.messageNodes[1].currentMessage
        respond = { input ->
            val last = input.last()
            flowOf(GenerationChunk.Messages(input.dropLast(1) + last.copy(parts = last.parts + UIMessagePart.Text("Продолжение ответа"))))
        }
        service.handleToolApproval(source.id, "earlier-call", approved = false, reason = "Не нужно").join()
        val live = service.getConversationFlow(source.id).value
        assertEquals("The request must end with the reply that owns the tool", reply.id, requestedIds.last())
        assertEquals(listOf("Вопрос", reply.toText()), requestedTexts)
        assertEquals(4, live.messageNodes.size)
        assertTrue(live.messageNodes[1].currentMessage.toText().contains("Продолжение ответа"))
        assertEquals(source.messageNodes[2], live.messageNodes[2])
        assertEquals("A later reply must not receive the resumed output", source.messageNodes[3], live.messageNodes[3])
        assertEquals(live.messageNodes, requireNotNull(repository.getConversationById(source.id)).messageNodes)
        assertTrue(service.errors.value.isEmpty())
    }

    @Test(timeout = 30_000) fun stopClosesPendingToolOfRegeneratedEarlierReplySoQueueIsNotBlocked() = runBlocking {
        val source = seed()
        val emitted = CompletableDeferred<Unit>()
        respond = { input ->
            flow {
                emit(GenerationChunk.Messages(input + UIMessage.assistant("").copy(parts = listOf(
                    UIMessagePart.Tool("regenerated-call", "ask_user", "{}", approvalState = ToolApprovalState.Pending)))))
                emitted.complete(Unit)
                awaitCancellation()
            }
        }
        service.regenerateAtMessage(source.id, source.messageNodes[1].currentMessage)
        emitted.await()
        service.stopGeneration(source.id)
        val live = service.getConversationFlow(source.id).value
        val tool = live.messageNodes[1].currentMessage.getTools().single { it.toolCallId == "regenerated-call" }
        assertFalse("Stop must close the pending tool of an earlier reply", tool.isPending)
        assertTrue(tool.isExecuted)
        assertEquals(live.messageNodes, requireNotNull(repository.getConversationById(source.id)).messageNodes)

        service.resumeMessageQueue(source.id)
        service.sendMessage(source.id, listOf(UIMessagePart.Text("Следующий вопрос")), answer = false)
        service.getGenerationJobStateFlow(source.id).first()?.join()
        assertEquals("Следующий вопрос", service.getConversationFlow(source.id).value.currentMessages.last().toText())
        assertTrue(service.getMessageQueueFlow(source.id).value.messages.isEmpty())
    }
}
