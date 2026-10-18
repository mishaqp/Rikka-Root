package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
class ChatDurableQueueIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private lateinit var settings: MutableStateFlow<Settings>
    private val assistant = Assistant(enableWebSearch = false)
    private val model = Model(modelId = "fixture")
    private var generationCalls = 0
    private lateinit var queueDirectory: java.io.File
    private lateinit var application: android.app.Application
    private lateinit var files: FilesManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var dao: GatedConversationDao
    private val cipher = me.rerere.rikkahub.data.repository.AesGcmCronPayloadCipher {
        javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    }

    @Before fun install() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = RuntimeEnvironment.getApplication()
        queueDirectory = java.io.File(application.cacheDir, "queue-integration-${kotlin.uuid.Uuid.random()}")
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
        files = mockk<FilesManager>(relaxed = true)
        every { files.deleteChatFiles(any()) } answers {
            firstArg<List<android.net.Uri>>().forEach { uri -> uri.path?.let { java.io.File(it).delete() } }
        }
        dao = GatedConversationDao(database.conversationDao())
        repository = ConversationRepository(dao, database.messageNodeDao(), database.favoriteDao(),
            database, files, mockk<MessageFtsManager>(relaxed = true))
        settings = MutableStateFlow(Settings(init = false, enableSuggestion = false, assistants = listOf(assistant), assistantId = assistant.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
        settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns settings
        every { settingsStore.settingsFlowRaw } returns settings
        restartService()
    }

    private fun restartService() {
        if (::service.isInitialized) service.cleanup()
        if (::appScope.isInitialized) appScope.cancel()
        val budgets = mockk<TokenBudgetStore>()
        every { budgets.getLedger(any(), any(), any()) } returns TokenBudgetLedger(enabled = false)
        val generation = mockk<GenerationLoop>()
        every { generation.generateText(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            prepareToolApproval = any(), beforeModelRequest = any(), approveEverything = any()) } answers {
            generationCalls++
            flowOf(GenerationChunk.Messages(arg<List<UIMessage>>(2) + UIMessage.assistant("Новый ответ")))
        }
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = settingsStore, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = generation, translationHandler = mockk(relaxed = true), templateTransformer = mockk(relaxed = true),
            providerManager = mockk(relaxed = true), chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true),
            filesManager = files, workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = mockk(relaxed = true), tokenBudgetStore = budgets,
            subAgentRegistry = me.rerere.rikkahub.subagent.SubAgentRegistry(), messageQueueStore = MessageQueueStore(queueDirectory, cipher))
    }

    @After fun close() {
        service.cleanup()
        appScope.cancel()
        database.close()
        queueDirectory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun pendingConversation() = Conversation(assistantId = assistant.id, title = "Чат с очередью",
        config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(
            me.rerere.ai.ui.UIMessagePart.Tool("waiting", "wait", "{}",
                approvalState = me.rerere.ai.ui.ToolApprovalState.Pending))).toMessageNode()))

    @Test(timeout = 30_000) fun realChatServiceRestoresAcceptedInputIdsOrderAndAttachmentsPausedWithoutGeneration() = runBlocking {
        val source = pendingConversation()
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        val image = java.io.File(application.filesDir, "durable-queue-image-${source.id}.png").apply { writeText("original bytes") }
        val firstParts = listOf(me.rerere.ai.ui.UIMessagePart.Text("Первый вопрос"), me.rerere.ai.ui.UIMessagePart.Image(android.net.Uri.fromFile(image).toString()))
        service.sendMessage(source.id, firstParts)
        service.sendMessage(source.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("Второй вопрос")), answer = false)
        val accepted = kotlinx.coroutines.withTimeout(5_000) { service.getMessageQueueFlow(source.id).first { it.messages.size == 2 }.messages }

        restartService()
        service.initializeConversation(source.id)

        val restored = service.getMessageQueueFlow(source.id).value
        assertTrue("Recovered input must wait for an explicit user resume", restored.paused)
        assertEquals(accepted.map { it.id }, restored.messages.map { it.id })
        assertEquals(accepted.map { it.parts }, restored.messages.map { it.parts })
        assertEquals(listOf(true, false), restored.messages.map { it.answer })
        assertTrue(restored.messages.none { it.isEditing })
        assertEquals(0, generationCalls)
        assertTrue(image.exists())
        assertEquals("The queue remains outside persisted chat history until manual dispatch", source.messageNodes,
            repository.getConversationById(source.id)!!.messageNodes)
        image.delete()
        Unit
    }

    @Test(timeout = 30_000) fun unopenedRecoveredQueueRetainsItsAttachmentDuringAnotherConversationDeletion() = runBlocking {
        val waiting = pendingConversation()
        repository.insertConversation(waiting)
        val image = java.io.File(application.filesDir, "unopened-queue-${waiting.id}.png").apply { writeText("kept bytes") }
        val parts = listOf(me.rerere.ai.ui.UIMessagePart.Image(android.net.Uri.fromFile(image).toString()))
        val queue = MessageQueue()
        queue.restoreFromStore(MessageQueueStore(queueDirectory, cipher), waiting.id, emptySet())
        queue.enqueue(parts)
        val source = Conversation(assistantId = assistant.id, title = "Другая история", messageNodes = listOf(
            UIMessage.user("").copy(parts = parts).toMessageNode()))
        repository.insertConversation(source)

        restartService()
        repository.deleteConversation(source)

        assertTrue("Startup's unknown guard and cached persisted references must protect unopened queue bytes", image.exists())
        service.initializeConversation(waiting.id)
        assertEquals(parts, service.getMessageQueueFlow(waiting.id).value.messages.single().parts)
        assertEquals(0, generationCalls)
        image.delete()
        Unit
    }

    @Test(timeout = 30_000) fun unreadableQueueKeepsOriginalCiphertextAndUnknownAttachmentsInsteadOfDeletingThem() = runBlocking {
        appScope.cancel()
        val id = kotlin.uuid.Uuid.random()
        queueDirectory.mkdirs()
        val unreadable = java.io.File(queueDirectory, "$id.queue").apply { writeText("unreadable original encrypted queue") }
        val image = java.io.File(application.filesDir, "unknown-queue-$id.png").apply { writeText("must survive") }
        val source = Conversation(assistantId = assistant.id, title = "Исходная история", messageNodes = listOf(
            UIMessage.user("").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Image(android.net.Uri.fromFile(image).toString()))).toMessageNode()))
        repository.insertConversation(source)
        val original = unreadable.readBytes()

        restartService()
        repository.deleteConversation(source)

        assertTrue(image.exists())
        assertTrue(original.contentEquals(unreadable.readBytes()))
        assertEquals(0, generationCalls)
        image.delete()
        Unit
    }

    @Test(timeout = 30_000) fun stopAfterHistoryCommitRemovesSubmittedInputFromDurableQueue() = runBlocking {
        val source = Conversation(assistantId = assistant.id, title = "Чат с очередью",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(
                UIMessage.user("Первый вопрос").toMessageNode(), UIMessage.assistant("Первый ответ").toMessageNode()))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.updateGate = entered to release
        try {
            service.sendMessage(source.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("Вопрос перед остановкой")), answer = false)
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            // Stop lands while the row write runs: the write commits, then the job sees the cancellation.
            val stopping = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { service.stopGeneration(source.id) }
            release.complete(Unit)
            stopping.join()
            val saved = repository.getConversationById(source.id)!!.currentMessages.single { it.toText() == "Вопрос перед остановкой" }
            assertTrue("A question saved in history must leave the durable queue, or it returns after restart",
                MessageQueueStore(queueDirectory, cipher).read(source.id).none { it.id == saved.id })
            assertTrue(service.getMessageQueueFlow(source.id).value.messages.isEmpty())
            assertEquals(0, generationCalls)
        } finally { release.complete(Unit) }
    }

    private class GatedConversationDao(private val delegate: me.rerere.rikkahub.data.db.dao.ConversationDAO) :
        me.rerere.rikkahub.data.db.dao.ConversationDAO by delegate {
        var updateGate: Pair<kotlinx.coroutines.CompletableDeferred<Unit>, kotlinx.coroutines.CompletableDeferred<Unit>>? = null
        override suspend fun update(conversation: me.rerere.rikkahub.data.db.entity.ConversationEntity) {
            updateGate?.also { updateGate = null }?.let { (entered, release) ->
                entered.complete(Unit)
                release.await()
            }
            delegate.update(conversation)
        }
    }
}
