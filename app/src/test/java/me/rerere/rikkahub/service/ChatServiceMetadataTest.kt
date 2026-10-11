package me.rerere.rikkahub.service

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.routes.conversationRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.net.HttpURLConnection
import java.net.URI
import kotlin.uuid.Uuid

/** Delayed real service operations against Room, with only the external provider deferred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceMetadataTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var service: ChatService
    private lateinit var appScope: AppScope
    private lateinit var provider: DeferredProvider
    private lateinit var fts: MessageFtsManager
    private lateinit var dao: BarrierConversationDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var settings: MutableStateFlow<Settings>
    private val assistant = Assistant()
    private val targetAssistant = Assistant()
    private val model = Model(modelId = "fast", displayName = "Fast")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val files = mockk<FilesManager>(relaxed = true)
        // Ordinary SQLite storage exercises the real FTS metadata SQL without the native Jieba tokenizer.
        database.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
        fts = MessageFtsManager(database)
        dao = BarrierConversationDao(database.conversationDao())
        repository = ConversationRepository(dao, database.messageNodeDao(),
            database.favoriteDao(), database, files, fts)
        settings = MutableStateFlow(Settings(init = false, assistants = listOf(assistant, targetAssistant),
            assistantId = assistant.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            fastModelId = model.id))
        settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns settings
        every { settingsStore.settingsFlowRaw } returns settings
        provider = DeferredProvider()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(any()) } returns provider
        val budgets = mockk<TokenBudgetStore>()
        every { budgets.getLedger(any(), any(), any()) } returns TokenBudgetLedger(enabled = false)
        appScope = AppScope()
        service = ChatService(context = application, appScope = appScope, appEventBus = mockk(relaxed = true),
            settingsStore = settingsStore, conversationRepo = repository, memoryRepository = mockk(relaxed = true),
            generationLoop = mockk(relaxed = true), translationHandler = mockk(relaxed = true),
            templateTransformer = mockk(relaxed = true), providerManager = manager,
            chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true), filesManager = files,
            workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
            toolApprovalPreferences = mockk(relaxed = true), tokenBudgetStore = budgets,
            subAgentRegistry = mockk(relaxed = true))
    }

    @After fun tearDown() {
        service.cleanup()
        appScope.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test(timeout = 30_000)
    fun `delayed title keeps live partial output and changes only persisted title`() = runBlocking {
        val source = seed()
        val row = database.conversationDao().getConversationById(source.id.toString())!!
        val nodes = database.messageNodeDao().getNodesOfConversation(source.id.toString())
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        appendPartial(source)
        service.updateConversationState(source.id) { current ->
            current.copy(assistantId = Uuid.random(), folderId = Uuid.random(), isPinned = false,
                config = current.config!!.copy(enableWebSearch = true, enabledSkills = setOf("changed-skill")),
                customSystemPrompt = "Changed system prompt", workspaceCwd = "/workspace/changed",
                modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()))
        }
        val streaming = service.getConversationFlow(source.id).value
        provider.complete("Generated title")
        request.await()

        assertEquals("Metadata must retain current message IDs, text and all unrelated live fields",
            streaming.copy(title = "Generated title"), service.getConversationFlow(source.id).value)
        assertEquals(row.copy(title = "Generated title"), database.conversationDao().getConversationById(source.id.toString()))
        assertEquals("Background title must not rewrite Room message rows", nodes,
            database.messageNodeDao().getNodesOfConversation(source.id.toString()))
        service.saveConversation(source.id, service.getConversationFlow(source.id).value)
        assertEquals(streaming.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `chat menu rename continues to change the title while preserving live history`() = runBlocking {
        val source = seed(title = "Existing title")
        val streaming = appendPartial(source)
        val indexBefore = indexedRows()
        val vm = ChatVM(source.id.toString(), RuntimeEnvironment.getApplication(), settingsStore,
            repository, service, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        try {
            updateMenuTitle(vm, "Ручное название")
            assertEquals("The existing chat menu must still rename through metadata persistence",
                streaming.copy(title = "Ручное название"), service.getConversationFlow(source.id).value)
            assertEquals("Ручное название", repository.getConversationById(source.id)!!.title)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
            assertEquals(indexBefore.map { it.toMutableList().apply { set(5, "Ручное название") }.toList() }, indexedRows())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `chat menu keeps verbatim title formatting`() = runBlocking {
        val source = seed(title = "Existing title")
        val vm = viewModel(source.id)
        try {
            updateMenuTitle(vm, "  Название с пробелами  ")
            assertEquals("  Название с пробелами  ", service.getConversationFlow(source.id).value.title)
            assertEquals("  Название с пробелами  ", repository.getConversationById(source.id)!!.title)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `chat menu still accepts blank title with a configured fast model`() = runBlocking {
        val source = seed(title = "Existing title")
        val vm = viewModel(source.id)
        try {
            updateMenuTitle(vm, "")
            assertEquals("The existing native dialog allows clearing the title", "", service.getConversationFlow(source.id).value.title)
            assertEquals("", repository.getConversationById(source.id)!!.title)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `chat menu blank title still uses local fallback without fast model`() = runBlocking {
        val source = seed(title = "Existing title")
        settings.value = settings.value.copy(fastModelId = Uuid.random())
        val vm = viewModel(source.id)
        try {
            updateMenuTitle(vm, "")
            assertEquals("Blank native titles retain the existing local fallback", "First request",
                service.getConversationFlow(source.id).value.title)
            assertEquals("First request", repository.getConversationById(source.id)!!.title)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `chat menu naming an empty unsaved conversation still creates its row`() = runBlocking {
        val id = Uuid.random()
        service.initializeConversation(id)
        val vm = viewModel(id)
        try {
            updateMenuTitle(vm, "New named chat")
            assertEquals("An explicit native rename may save a new empty conversation", 1, repository.countConversations())
            val persisted = repository.getConversationById(id)!!
            assertEquals("New named chat", persisted.title)
            assertEquals(assistant.id, persisted.assistantId)
            assertEquals(emptyList<me.rerere.rikkahub.data.model.MessageNode>(), persisted.messageNodes)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `chat settings public viewmodel save retains explicit prompt config and cwd`() = runBlocking {
        val source = seed(title = "Existing title")
        val vm = viewModel(source.id)
        val updated = source.copy(customSystemPrompt = "Edited prompt", workspaceCwd = "/workspace/edited",
            config = source.config!!.copy(enableWebSearch = true, enabledSkills = setOf("edited-skill")))
        try {
            vm.updateConversation(updated)
            runViewModelAction(vm) { vm.saveConversationAsync() }
            assertEquals(updated, service.getConversationFlow(source.id).value)
            assertEquals(updated, repository.getConversationById(source.id))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test(timeout = 30_000)
    fun `web rename endpoint changes only title metadata`() = runBlocking {
        val source = seed(title = "Existing title")
        val streaming = appendPartial(source)
        val indexBefore = indexedRows()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json(JsonInstant) }
            install(SSE)
            routing { conversationRoutes(service, repository, mockk(relaxed = true), settingsStore) }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val connection = URI("http://127.0.0.1:$port/conversations/${source.id}/title").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true
                connection.outputStream.use { it.write("{\"title\":\"  Ручное название  \"}".toByteArray(Charsets.UTF_8)) }
                assertEquals(200, connection.responseCode)
                assertEquals("The web rename must trim and preserve user intent", "Ручное название",
                    service.getConversationFlow(source.id).value.title)
                assertEquals(streaming.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
                assertEquals("Ручное название", repository.getConversationById(source.id)!!.title)
                assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
                assertEquals(indexBefore.map { it.toMutableList().apply { set(5, "Ручное название") }.toList() }, indexedRows())
            } finally { connection.disconnect() }
        } finally { server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
    }

    @Test(timeout = 30_000)
    fun `suggestion reset leaves an uninitialized session available for normal history loading`() = runBlocking {
        val source = seed(title = "Existing title")
        service.cleanup()
        service.getConversationFlow(source.id) // Only creates the uninitialized placeholder.
        val request = async { service.generateSuggestion(source.id, source) }
        provider.entered.await()
        service.initializeConversation(source.id)
        val loadedWhilePending = service.getConversationFlow(source.id).value
        provider.complete("New suggestion")
        request.await()

        assertEquals("Safety loading must retain full history while the reset changes only suggestions",
            source.copy(chatSuggestions = emptyList()), loadedWhilePending)
        assertEquals(source.copy(chatSuggestions = listOf("New suggestion")), service.getConversationFlow(source.id).value)
        assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `title committed during first state publication merges into the latest initialized session`() = runBlocking {
        val source = seed()
        service.cleanup()
        service.getConversationFlow(source.id)
        val gate = FullWriteGate()
        dao.titleCasGate = gate
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        provider.complete("Generated title")
        gate.entered.await()
        service.updateConversationState(source.id) { source.copy(isPinned = false, folderId = Uuid.random()) }
        val streaming = appendPartial(service.getConversationFlow(source.id).value)
        gate.release.complete(Unit)
        request.await()

        assertEquals(streaming.copy(title = "Generated title"), service.getConversationFlow(source.id).value)
        assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `title request started before manual rename preserves that rename and partial output`() = runBlocking {
        assertRenameSurvives(initialTitle = "", force = false)
    }

    @Test(timeout = 30_000)
    fun `forced title request also preserves a newer manual rename`() = runBlocking {
        assertRenameSurvives(initialTitle = "Previous title", force = true)
    }

    @Test(timeout = 30_000)
    fun `delayed suggestions keep live partial output and change only persisted suggestions`() = runBlocking {
        val source = seed(title = "Existing title")
        val row = database.conversationDao().getConversationById(source.id.toString())!!
        val nodes = database.messageNodeDao().getNodesOfConversation(source.id.toString())
        val request = async { service.generateSuggestion(source.id, source) }
        provider.entered.await()
        assertEquals(emptyList<String>(), service.getConversationFlow(source.id).value.chatSuggestions)
        val streaming = appendPartial(service.getConversationFlow(source.id).value)
        service.renameConversation(source.id, "Ручное название")
        provider.complete(" First suggestion \n\nSecond suggestion ")
        request.await()

        val suggestions = listOf("First suggestion", "Second suggestion")
        assertEquals("Suggestions must retain current message IDs, text and unrelated live fields",
            streaming.copy(title = "Ручное название", chatSuggestions = suggestions), service.getConversationFlow(source.id).value)
        assertEquals(row.copy(title = "Ручное название", chatSuggestions = "[\"First suggestion\",\"Second suggestion\"]"),
            database.conversationDao().getConversationById(source.id.toString()))
        assertEquals("Background suggestions must not rewrite Room message rows", nodes,
            database.messageNodeDao().getNodesOfConversation(source.id.toString()))
        service.saveConversation(source.id, service.getConversationFlow(source.id).value)
        assertEquals(streaming.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `generated title updates FTS metadata without replacing indexed messages`() = runBlocking {
        val source = seed()
        val indexBefore = indexedRows()
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        provider.complete("Generated title")
        request.await()

        assertEquals("Title metadata must retain FTS row identity, text, IDs and update time",
            indexBefore.map { it.toMutableList().apply { set(5, "Generated title") }.toList() }, indexedRows())
    }

    @Test(timeout = 30_000)
    fun `stale final save cannot restore metadata captured before title result`() = runBlocking {
        val source = seed()
        val staleFinal = appendPartial(source)
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        provider.complete("Generated title")
        request.await()
        service.saveConversation(source.id, staleFinal)

        assertEquals("Generated title", service.getConversationFlow(source.id).value.title)
        val reopened = repository.getConversationById(source.id)!!
        assertEquals("A final save must preserve the committed title", "Generated title", reopened.title)
        assertEquals(staleFinal.messageNodes, reopened.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `stale final save preserves all metadata edited while title request was pending`() = runBlocking {
        val source = seed()
        val staleFinal = appendPartial(source)
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        service.toggleConversationPinned(source.id)
        service.moveConversationToAssistant(source.id, targetAssistant.id)
        service.moveConversationToFolder(source.id, Uuid.random())
        service.updateChatSearch(source.id, enableWebSearch = true, builtInSearch = true)
        service.updateConversationState(source.id) { it.copy(customSystemPrompt = "New prompt", workspaceCwd = "/workspace/new") }
        val latest = service.getConversationFlow(source.id).value
        provider.complete("Generated title")
        request.await()
        service.saveConversation(source.id, staleFinal)

        assertEquals("A final message snapshot must preserve all current metadata",
            latest.copy(title = "Generated title"), service.getConversationFlow(source.id).value)
        assertEquals(latest.copy(title = "Generated title"), repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `public save keeps explicit fresh metadata after default new chat initialization`() = runBlocking {
        val source = explicitConversation()
        service.initializeConversation(source.id)
        service.saveConversation(source.id, source)

        assertEquals("A fresh caller's metadata must replace the default new-chat placeholder", source,
            service.getConversationFlow(source.id).value)
        assertEquals(source, repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `public save keeps supplied persisted metadata before first session initialization`() = runBlocking {
        val persisted = explicitConversation()
        repository.insertConversation(persisted)
        service.getConversationFlow(persisted.id)
        val updated = persisted.copy(title = "External update", isPinned = false, folderId = Uuid.random(),
            customSystemPrompt = "External prompt", config = persisted.config!!.copy(enableWebSearch = false))
        service.saveConversation(updated.id, updated)

        assertEquals("An unloaded placeholder is not authoritative conversation metadata", updated,
            service.getConversationFlow(updated.id).value)
        assertEquals(updated, repository.getConversationById(updated.id))
    }

    @Test(timeout = 30_000)
    fun `metadata persistence waits for an already started full save`() = runBlocking {
        val source = seed(title = "Existing title")
        val staleFinal = appendPartial(source)
        val writeGate = FullWriteGate()
        dao.fullWriteGate = writeGate
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { service.saveConversation(source.id, staleFinal) }
        writeGate.entered.await()
        val renaming = launch(start = CoroutineStart.UNDISPATCHED) { service.renameConversation(source.id, "Ручное название") }
        try {
            // If metadata is allowed past the full save, make its real Room commit happen first.
            // A shared persistence lock instead holds it here until the full save is released.
            if (dao.titleWriteEntered.isCompleted) renaming.join()
        } finally {
            writeGate.release.complete(Unit)
            saving.join()
            renaming.join()
        }

        assertEquals("A suspended old full save must retain a committed manual title",
            "Ручное название", service.getConversationFlow(source.id).value.title)
        assertEquals("A suspended old full save must retain a committed manual title in Room",
            "Ручное название", repository.getConversationById(source.id)!!.title)
        assertEquals(staleFinal.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `stale checkpoint save cannot restore suggestions captured before result`() = runBlocking {
        val source = seed(title = "Existing title")
        val staleCheckpoint = appendPartial(source)
        val request = async { service.generateSuggestion(source.id, source) }
        provider.entered.await()
        provider.complete("New suggestion")
        request.await()
        service.saveConversation(source.id, staleCheckpoint)

        assertEquals(listOf("New suggestion"), service.getConversationFlow(source.id).value.chatSuggestions)
        val reopened = repository.getConversationById(source.id)!!
        assertEquals("A checkpoint must preserve committed suggestions", listOf("New suggestion"), reopened.chatSuggestions)
        assertEquals(staleCheckpoint.messageNodes, reopened.messageNodes)
    }

    @Test(timeout = 30_000)
    fun `suggestion result does not recreate a row deleted during provider request`() = runBlocking {
        val source = seed(title = "Existing title")
        val request = async { service.generateSuggestion(source.id, source) }
        provider.entered.await()
        repository.deleteConversation(source)
        provider.complete("New suggestion")
        request.await()

        assertEquals("Deleted conversations must stay deleted", 0, repository.countConversations())
        assertNull(repository.getConversationById(source.id))
    }

    @Test(timeout = 30_000)
    fun `title result does not recreate a row deleted during provider request`() = runBlocking {
        val source = seed()
        val request = async { service.generateTitle(source.id, source) }
        provider.entered.await()
        repository.deleteConversation(source)
        provider.complete("Generated title")
        request.await()

        assertEquals(0, repository.countConversations())
        assertNull(repository.getConversationById(source.id))
    }

    private suspend fun assertRenameSurvives(initialTitle: String, force: Boolean) {
        val source = seed(title = initialTitle)
        kotlinx.coroutines.coroutineScope {
            val request = async { service.generateTitle(source.id, source, force) }
            provider.entered.await()
            val streaming = appendPartial(source)
            service.renameConversation(source.id, "Ручное название")
            provider.complete("Generated title")
            request.await()

            assertEquals("A newer manual rename must beat the request-start expected title",
                "Ручное название", service.getConversationFlow(source.id).value.title)
            assertEquals(streaming.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
            assertEquals("Ручное название", repository.getConversationById(source.id)!!.title)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        }
    }

    private suspend fun seed(title: String = ""): Conversation {
        val source = Conversation(assistantId = assistant.id, title = title,
            messageNodes = listOf(UIMessage.user("First request").toMessageNode(), UIMessage.assistant("First answer").toMessageNode()),
            config = ConversationConfig(chatModelId = model.id, enabledSkills = setOf("fixture-skill")),
            folderId = Uuid.random(), isPinned = true, customSystemPrompt = "Custom system prompt",
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
            workspaceCwd = "/workspace/chat", chatSuggestions = listOf("Old suggestion"),
            createAt = Instant.ofEpochMilli(1_000), updateAt = Instant.ofEpochMilli(2_000))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        // Keep the loaded session alive while provider and Room operations are suspended.
        service.addConversationReference(source.id)
        return source
    }

    private fun explicitConversation() = Conversation(assistantId = targetAssistant.id, title = "Explicit title",
        messageNodes = listOf(UIMessage.user("External first message").toMessageNode()),
        chatSuggestions = listOf("Explicit suggestion"), isPinned = true,
        config = ConversationConfig(chatModelId = model.id, enableWebSearch = true, enabledSkills = setOf("explicit-skill")),
        folderId = Uuid.random(), customSystemPrompt = "Explicit prompt", workspaceCwd = "/workspace/explicit",
        modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
        createAt = Instant.ofEpochMilli(1_000), updateAt = Instant.ofEpochMilli(2_000))

    private fun viewModel(id: Uuid) = ChatVM(id.toString(), RuntimeEnvironment.getApplication(), settingsStore,
        repository, service, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private suspend fun updateMenuTitle(vm: ChatVM, title: String) {
        runViewModelAction(vm) { vm.updateTitle(title) }
    }

    private suspend fun runViewModelAction(vm: ChatVM, action: () -> Unit) {
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val existing = scopeJob.children.toSet()
        action()
        scopeJob.children.filter { it !in existing }.toList().forEach { it.join() }
    }

    private fun appendPartial(current: Conversation): Conversation {
        val partial = UIMessage.assistant("Partial next answer").toMessageNode()
        service.updateConversationState(current.id) { it.copy(messageNodes = it.messageNodes + partial) }
        return service.getConversationFlow(current.id).value
    }

    private fun indexedRows(): List<List<String>> = database.openHelper.readableDatabase
        .query("SELECT _id, text, node_id, message_id, conversation_id, title, update_at FROM message_fts ORDER BY _id")
        .use { cursor -> buildList { while (cursor.moveToNext()) add((0..6).map(cursor::getString)) } }

    private class DeferredProvider : Provider<ProviderSetting> {
        val entered = CompletableDeferred<Unit>()
        private val response = CompletableDeferred<String>()
        fun complete(text: String) { response.complete(text) }
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>,
            params: TextGenerationParams): TextGenerationResult {
            entered.complete(Unit)
            return TextGenerationResult(id = "result", model = params.model.modelId,
                message = UIMessage.assistant(response.await()),
                usage = TokenUsage(promptTokens = 8, completionTokens = 4, totalTokens = 12))
        }
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>,
            params: TextGenerationParams): Flow<StreamChunk> = error("Background metadata must use generateText")
    }

    private class FullWriteGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class BarrierConversationDao(private val delegate: ConversationDAO) : ConversationDAO by delegate {
        var fullWriteGate: FullWriteGate? = null
        var titleCasGate: FullWriteGate? = null
        val titleWriteEntered = CompletableDeferred<Unit>()
        override suspend fun existsById(id: String): Boolean {
            val exists = delegate.existsById(id)
            fullWriteGate?.also { fullWriteGate = null }?.let {
                it.entered.complete(Unit)
                it.release.await()
            }
            return exists
        }
        override suspend fun updateTitle(id: String, title: String) {
            titleWriteEntered.complete(Unit)
            delegate.updateTitle(id, title)
        }
        override suspend fun updateTitleIfUnchanged(id: String, expectedTitle: String, title: String): Int {
            titleCasGate?.also { titleCasGate = null }?.let {
                it.entered.complete(Unit)
                it.release.await()
            }
            return delegate.updateTitleIfUnchanged(id, expectedTitle, title)
        }
    }
}
