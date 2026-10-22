package me.rerere.rikkahub.service

import android.app.Application
import androidx.core.net.toUri
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import kotlin.uuid.Uuid

/** Real public ChatService, ownership-aware Room repository and attachment deletion.
 * Only generation/provider work is a gated boundary; no production method is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ChatAuditServiceFixture {
    protected lateinit var context: Application
    protected lateinit var db: AppDatabase
    protected lateinit var repo: ConversationRepository
    protected lateinit var service: ChatService
    protected lateinit var scope: AppScope
    protected lateinit var settings: MutableStateFlow<Settings>
    protected lateinit var store: SettingsStore
    protected lateinit var files: FilesManager
    protected lateinit var memories: MemoryRepository
    protected lateinit var dao: GateDao
    protected lateinit var factory: ChatToolFactory
    protected var actualFactory: ChatToolFactory? = null
    protected val model = Model(modelId = "chat-audit-fixture", abilities = listOf(ModelAbility.TOOL))
    protected val owner = Assistant(name = "Живой владелец", chatModelId = model.id)
    protected val generationInputs = mutableListOf<List<UIMessage>>()
    protected var runtimeAssistant: Assistant? = null
    protected var runtimeMemories: List<AssistantMemory> = emptyList()
    protected var runtimeTools: List<Tool> = emptyList()
    protected var generationBody: suspend FlowCollector<GenerationChunk>.(List<UIMessage>) -> Unit = { input ->
        emit(GenerationChunk.Messages(input + UIMessage.assistant("Ответ fixture")))
    }
    protected val trackedIds = linkedSetOf<Uuid>()
    private lateinit var recoveryFile: File

    @Before fun setupChatAuditFixture() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        recoveryFile = File(context.noBackupFilesDir, "chat-executions.json")
        recoveryFile.delete(); File(recoveryFile.parentFile, "chat-background-executions.json").delete()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = AppScope()
        settings = MutableStateFlow(Settings(init = false, enableSuggestion = false,
            assistants = listOf(owner), assistantId = owner.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
        store = mockk(relaxed = true)
        every { store.settingsFlow } returns settings
        every { store.settingsFlowRaw } returns settings
        files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
        dao = GateDao(db.conversationDao())
        val ownership = AssistantOwnershipGuard { id -> settings.value.assistants.any { it.id.toString() == id } }
        repo = ConversationRepository(dao, db.messageNodeDao(), db.favoriteDao(), db,
            files, mockk<MessageFtsManager>(relaxed = true), ownershipGuard = ownership)
        memories = MemoryRepository(db.memoryDao(), ownership)
        factory = mockk(relaxed = true)
        coEvery { factory.createTools(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            actualFactory?.createTools(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7)) ?: emptyList()
        }
        val budgets = mockk<TokenBudgetStore>()
        every { budgets.getLedger(any(), any(), any()) } returns TokenBudgetLedger(enabled = false)
        val generation = mockk<GenerationLoop>()
        every { generation.generateText(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            prepareToolApproval = any(), beforeModelRequest = any(), approveEverything = any()) } answers {
            val input = arg<List<UIMessage>>(2)
            runtimeAssistant = arg(5)
            runtimeMemories = arg<List<AssistantMemory>?>(6).orEmpty()
            runtimeTools = arg(7)
            generationInputs += input
            flow { generationBody(input) }
        }
        service = ChatService(context, scope, mockk(relaxed = true), store, repo, memories,
            generation, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            factory, mockk(relaxed = true), files, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), budgets, SubAgentRegistry())
    }

    @After fun cleanupChatAuditFixture() = runBlocking {
        trackedIds.forEach { id -> service.stopGeneration(id) }
        service.cleanup()
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        db.close()
        recoveryFile.delete(); File(recoveryFile.parentFile, "chat-background-executions.json").delete()
        Dispatchers.resetMain()
    }

    protected suspend fun seed(
        nodes: List<MessageNode> = listOf(UIMessage.user("Первый вопрос").toMessageNode(), UIMessage.assistant("Первый ответ").toMessageNode()),
        assistantId: Uuid = owner.id,
        title: String = "Аудит чата",
        rawOrphan: Boolean = false,
    ): Conversation {
        val value = Conversation(assistantId = assistantId, title = title,
            config = ConversationConfig(chatModelId = model.id), messageNodes = nodes)
        if (rawOrphan) {
            db.conversationDao().insert(repo.conversationToConversationEntity(value))
            db.messageNodeDao().insertAll(value.messageNodes.mapIndexed { i, node -> MessageNodeEntity(
                node.id.toString(), value.id.toString(), i, JsonInstant.encodeToString(node.messages), node.selectIndex) })
        } else repo.insertConversation(value)
        trackedIds += value.id
        service.initializeConversation(value.id)
        service.addConversationReference(value.id)
        return requireNotNull(repo.getConversationById(value.id))
    }
    protected suspend fun waitGeneration(id: Uuid) = withTimeout(10_000) {
        service.getGenerationJobStateFlow(id).first { it == null }
    }
    protected fun imageFile(): File = File(context.filesDir, "upload/${Uuid.random()}-chat.png").apply {
        parentFile!!.mkdirs(); writeText("EXACT ATTACHMENT BYTES")
    }
    protected fun image(file: File) = UIMessagePart.Image(file.toUri().toString())
    protected class Gate { val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>() }
    protected class GateDao(private val delegate: ConversationDAO) : ConversationDAO by delegate {
        var existsGate: Gate? = null
        override suspend fun existsById(id: String): Boolean {
            val result = delegate.existsById(id)
            existsGate?.also { existsGate = null }?.let { it.entered.complete(Unit); it.release.await() }
            return result
        }
    }
}
