package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
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
class ChatRecoveryRetryPart3Test {
    private var failNextReadOf: String? = null
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private lateinit var settings: MutableStateFlow<Settings>
    private val assistant = Assistant(enableWebSearch = false)
    private val model = Model(modelId = "fixture")
    private var requestedTexts = emptyList<String>()
    private var generationBody: suspend kotlinx.coroutines.flow.FlowCollector<GenerationChunk>.(List<UIMessage>) -> Unit = { input ->
        emit(GenerationChunk.Messages(input + UIMessage.assistant("Новый ответ")))
    }
    private lateinit var recoveryFile: java.io.File
    private val registry = me.rerere.rikkahub.subagent.SubAgentRegistry()

    @Before fun install() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        recoveryFile = java.io.File(application.noBackupFilesDir, "chat-executions.json")
        recoveryFile.delete()
        java.io.File(application.noBackupFilesDir, "chat-background-executions.json").delete()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = mockk<FilesManager>(relaxed = true)
        val actualConversationDao = database.conversationDao()
        val faultDao = object : me.rerere.rikkahub.data.db.dao.ConversationDAO by actualConversationDao {
            override suspend fun getConversationMetadataById(id: String): me.rerere.rikkahub.data.db.entity.ConversationEntity? {
                if (failNextReadOf == id) {
                    failNextReadOf = null
                    throw java.io.IOException("temporary conversation read failure")
                }
                return actualConversationDao.getConversationMetadataById(id)
            }
        }
        repository = ConversationRepository(faultDao, database.messageNodeDao(), database.favoriteDao(),
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
            kotlinx.coroutines.flow.flow { generationBody(input) }
        }
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = store, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = generation, translationHandler = mockk(relaxed = true), templateTransformer = mockk(relaxed = true),
            providerManager = mockk(relaxed = true), chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true),
            filesManager = files, workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = mockk(relaxed = true), tokenBudgetStore = budgets, subAgentRegistry = registry)
    }

    @After fun close() {
        service.cleanup()
        appScope.cancel()
        database.close()
        recoveryFile.delete()
        java.io.File(recoveryFile.parentFile, "chat-background-executions.json").delete()
        Dispatchers.resetMain()
    }

    private fun interrupted(title: String) = Conversation(assistantId = assistant.id, title = title,
        config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(
            UIMessage.user("Сохранённый запрос").toMessageNode(),
            UIMessage.assistant("Сохранённый частичный ответ").copy(parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Text("Сохранённый частичный ответ"),
                me.rerere.ai.ui.UIMessagePart.Tool("old-root", "root_exec", "{}",
                    approvalState = me.rerere.ai.ui.ToolApprovalState.Approved),
            )).toMessageNode()))

    private fun saveOldMarkers(vararg ids: kotlin.uuid.Uuid) {
        recoveryFile.writeText(kotlinx.serialization.json.buildJsonObject {
            ids.forEach { id -> put(id.toString(), kotlinx.serialization.json.JsonPrimitive("old-process/old-owner/")) }
        }.toString())
    }

    private fun assertInterruptedWithoutEffects(conversation: Conversation) {
        assertTrue("Сохранённый частичный ответ остаётся", conversation.currentMessages.last().toText().contains("Сохранённый частичный ответ"))
        val tool = conversation.currentMessages.last().getTools().single()
        assertTrue("Старый approved tool должен быть закрыт без исполнения", tool.isExecuted)
        assertTrue(tool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text.contains("process_interrupted"))
        assertTrue("Открытие не должно запускать модель или tools", requestedTexts.isEmpty())
    }

    @Test(timeout = 30_000) fun repairedIncompleteHistoryIsRecoveredByTheSameChatServiceWithoutTouchingHealthyHistoryTwice() = runBlocking {
        val damaged = interrupted("Временно повреждённая история")
        val healthy = interrupted("Здоровая история")
        repository.insertConversation(damaged); repository.insertConversation(healthy)
        saveOldMarkers(damaged.id, healthy.id)
        val original = database.messageNodeDao().getNodesOfConversation(damaged.id.toString()).last()
        val invalid = original.copy(messages = original.messages.dropLast(1))
        database.messageNodeDao().insert(invalid)
        service.recoverInterruptedGenerations()
        assertEquals("Не переписывать повреждённые исходные bytes", invalid,
            database.messageNodeDao().getNodesOfConversation(damaged.id.toString()).last())
        val healthyOnce = repository.getConversationById(healthy.id)!!
        assertInterruptedWithoutEffects(healthyOnce)
        database.messageNodeDao().insert(original)

        service.initializeConversation(damaged.id)

        assertInterruptedWithoutEffects(service.getConversationFlow(damaged.id).value)
        assertEquals(healthyOnce.messageNodes, repository.getConversationById(healthy.id)!!.messageNodes)
        assertFalse("Старый marker нужно снять после успешного retry", recoveryFile.readText().contains(damaged.id.toString()))
        service.initializeConversation(damaged.id)
        assertEquals(service.getConversationFlow(damaged.id).value.messageNodes, repository.getConversationById(damaged.id)!!.messageNodes)
    }

    @Test(timeout = 30_000) fun oneShotReadFailureIsRetriedWhenTheSameChatServiceOpensTheChat() = runBlocking {
        val source = interrupted("Временный отказ чтения")
        repository.insertConversation(source); saveOldMarkers(source.id)
        failNextReadOf = source.id.toString()
        service.recoverInterruptedGenerations()
        assertTrue("До успешного восстановления старый marker сохраняется", recoveryFile.readText().contains(source.id.toString()))

        service.initializeConversation(source.id)

        assertInterruptedWithoutEffects(service.getConversationFlow(source.id).value)
        assertFalse(recoveryFile.readText().contains(source.id.toString()))
    }

    @Test(timeout = 30_000) fun retryRecoveryDoesNotCloseActualCurrentProcessOwnerOrClearItsReceipt() = runBlocking {
        val source = interrupted("Текущий процесс")
        repository.insertConversation(source)
        val recovery = ChatProcessRecovery(recoveryFile, repository, registry)
        val ticket = recovery.begin(source.id, source.currentMessages.first().id)
        recovery.recoverInterrupted()
        assertFalse(repository.getConversationById(source.id)!!.currentMessages.last().getTools().single().isExecuted)
        assertTrue(recoveryFile.readText().contains(source.id.toString()))
        recovery.finish(ticket, source)
        assertFalse(recoveryFile.readText().contains(source.id.toString()))
    }
}
