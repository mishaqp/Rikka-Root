package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
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
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** Public approval service with real Room and grant DataStore; only factory dependencies are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceArgumentApprovalBoundaryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private lateinit var grants: ToolApprovalTestStore
    private lateinit var factory: ChatToolFactory
    private var definitions = emptyList<Tool>()
    private var factoryGate: CompletableDeferred<Unit>? = null
    private val model = Model(modelId = "approval-contract")
    private val assistant = Assistant(chatModelId = model.id)
    private val conversations = mutableListOf<Uuid>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = mockk<FilesManager>(relaxed = true)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, files, mockk<MessageFtsManager>(relaxed = true))
        val settings = MutableStateFlow(Settings(init = false, assistants = listOf(assistant), assistantId = assistant.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
        val store = mockk<SettingsStore>(relaxed = true)
        every { store.settingsFlow } returns settings
        every { store.settingsFlowRaw } returns settings
        grants = ToolApprovalTestStore(folder.newFolder())
        factory = mockk()
        coEvery { factory.createTools(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            factoryGate?.await()
            definitions
        }
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = store, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = mockk(relaxed = true), translationHandler = mockk(relaxed = true),
            templateTransformer = mockk(relaxed = true), providerManager = mockk(relaxed = true),
            chatToolFactory = factory, mcpManager = mockk(relaxed = true), filesManager = files,
            workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = grants.preferences, tokenBudgetStore = mockk(relaxed = true),
            subAgentRegistry = mockk(relaxed = true))
    }

    @After fun cleanup() {
        service.cleanup()
        appScope.cancel()
        grants.close()
        conversations.forEach(ToolApprovalAllowList::clearChat)
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `invalid saved pending argument cannot create Always grant or become approved`() = runBlocking {
        definitions = listOf(contractTool())
        val id = seed(listOf(pending("target", "contract_tool", """{"count":"not-a-number","label":"ok"}""")))
        approve(id, "target", "contract_tool", ChatService.ApprovalScope.Always, expectError = true)
        assertFalse(grants.preferences.current().contains("contract_tool"))
        assertFalse(currentTools(id).single { it.toolCallId == "target" }.approvalState is ToolApprovalState.Approved)
        assertVisibleError()
    }

    @Test fun `duplicate saved pending ids cannot grant scopes or bulk approve either tool`() = runBlocking {
        definitions = listOf(contractTool(), contractTool().copy(name = "other_tool"))
        val id = seed(listOf(pending("target", "contract_tool", """{"count":1,"label":"first"}"""),
            pending("target", "other_tool", """{"count":2,"label":"second"}""")))
        approve(id, "target", "contract_tool", ChatService.ApprovalScope.Always, expectError = true)
        assertTrue(grants.preferences.current().isEmpty())
        assertTrue(currentTools(id).filter { it.toolCallId == "target" }.none { it.approvalState is ToolApprovalState.Approved })
        assertVisibleError()
    }

    @Test fun `removed tool definition cannot obtain Always from an old pending card`() = runBlocking {
        definitions = emptyList()
        val id = seed(listOf(pending("target", "removed_tool", "{}")))
        approve(id, "target", "removed_tool", ChatService.ApprovalScope.Always, expectError = true)
        assertTrue(grants.preferences.current().isEmpty())
        assertFalse(currentTools(id).single { it.toolCallId == "target" }.approvalState is ToolApprovalState.Approved)
        assertVisibleError()
    }

    @Test fun `valid numeric string pending card is canonical before chat grant and persisted approval`() = runBlocking {
        definitions = listOf(contractTool())
        val id = seed(listOf(pending("target", "contract_tool", """{"count":"12","label":"007"}""")))
        approve(id, "target", "contract_tool", ChatService.ApprovalScope.ChatScope)
        assertTrue(ToolApprovalAllowList.isAllowedForChat(id, "contract_tool"))
        val expected = Json.parseToJsonElement("""{"count":12,"label":"007"}""")
        val live = currentTools(id).single { it.toolCallId == "target" }
        assertEquals(ToolApprovalState.Approved, live.approvalState)
        assertEquals(expected, Json.parseToJsonElement(live.input))
        val stored = repository.getConversationById(id)!!.currentMessages.flatMap { it.getTools() }.single { it.toolCallId == "target" }
        assertEquals(expected, Json.parseToJsonElement(stored.input))
    }

    @Test fun `protected root pending card still cannot create a persistent or chat grant`() = runBlocking {
        definitions = listOf(Tool("root_exec", "test", parameters = {
            InputSchema.Obj(buildJsonObject { putJsonObject("command") { put("type", "string") } }, listOf("command"))
        }, needsApproval = { true }, execute = { fail("Service must not execute root directly"); emptyList() }))
        val id = seed(listOf(pending("target", "root_exec", """{"command":"reboot bootloader"}""")))
        approve(id, "target", "root_exec", ChatService.ApprovalScope.Always)
        assertFalse(grants.preferences.current().contains("root_exec"))
        assertFalse(ToolApprovalAllowList.isAllowedForChat(id, "root_exec"))
    }

    @Test fun `approving selected pending call leaves same id hidden alternative unchanged`() = runBlocking {
        definitions = listOf(contractTool())
        val hidden = UIMessage.assistant("").copy(parts = listOf(
            pending("target", "contract_tool", """{"count":99,"label":"hidden"}""")))
        val id = seed(listOf(pending("target", "contract_tool", """{"count":1,"label":"selected"}""")), hidden)
        approve(id, "target", "contract_tool", ChatService.ApprovalScope.Once)
        val live = service.getConversationFlow(id).value.messageNodes.last()
        assertEquals(0, live.selectIndex)
        assertEquals(hidden, live.messages[1])
        assertEquals(ToolApprovalState.Approved, live.currentMessage.getTools().single { it.toolCallId == "target" }.approvalState)
        assertEquals(hidden, repository.getConversationById(id)!!.messageNodes.last().messages[1])
        assertTrue(grants.preferences.current().isEmpty())
    }

    private fun contractTool() = Tool("contract_tool", "test", parameters = {
        InputSchema.Obj(Json.parseToJsonElement("""{"count":{"type":"integer"},"label":{"type":"string"}}""").jsonObject,
            listOf("count", "label"))
    }, needsApproval = { true }, execute = { fail("An approval with another waiting sibling cannot execute tools"); emptyList() })

    private fun pending(id: String, name: String, args: String) = UIMessagePart.Tool(toolCallId = id,
        toolName = name, input = args, output = emptyList(), approvalState = ToolApprovalState.Pending)

    private suspend fun seed(calls: List<UIMessagePart.Tool>, hiddenAlternative: UIMessage? = null): Uuid {
        // A separate pending sibling keeps approval on the public mutation path, without starting a provider request.
        val source = Conversation(assistantId = assistant.id, config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("Проверь").toMessageNode(),
                MessageNode(messages = listOfNotNull(
                    UIMessage.assistant("").copy(parts = calls + pending("waiting", "ask_user", "{}" )), hiddenAlternative), selectIndex = 0)))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        conversations += source.id
        return source.id
    }

    private suspend fun approve(id: Uuid, callId: String, name: String, scope: ChatService.ApprovalScope,
        expectError: Boolean = false) = kotlinx.coroutines.coroutineScope {
        if (expectError) {
            service.handleToolApproval(id, callId, approved = true, scope = scope, toolName = name)
            // Validation may fail synchronously; errors are retained even if a short-lived
            // non-null generation job was conflated before the observer received it.
            withTimeout(15_000) { service.errors.first { errors -> errors.any { it.conversationId == id } } }
            withTimeout(15_000) { service.getGenerationJobStateFlow(id).first()?.join() }
        } else {
            val gate = CompletableDeferred<Unit>()
            factoryGate = gate
            try {
                val started = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(15_000) { service.getGenerationJobStateFlow(id).filterNotNull().first() }
                }
                service.handleToolApproval(id, callId, approved = true, scope = scope, toolName = name)
                val job = started.await()
                gate.complete(Unit)
                withTimeout(15_000) { job.join() }
            } finally { gate.complete(Unit); factoryGate = null }
        }
    }

    private fun currentTools(id: Uuid) = service.getConversationFlow(id).value.currentMessages.flatMap { it.getTools() }
    private fun assertVisibleError() {
        assertTrue("Invalid approval needs a visible recovery message", service.errors.value.isNotEmpty())
        assertTrue(service.errors.value.toString(), Regex("[А-Яа-я]").containsMatchIn(service.errors.value.toString()))
    }
}
