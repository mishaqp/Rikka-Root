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
class ChatProcessDeathTest {
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

    @Test(timeout = 30_000) fun persistedInterruptedGenerationClosesToolsAndKeepsAllBranchesAndAttachments() = runBlocking {
        val user = UIMessage.user("Вопрос").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("Вопрос"),
            me.rerere.ai.ui.UIMessagePart.Image("file:///original.png"))).toMessageNode()
        val originalBranch = UIMessage.assistant("Старый вариант")
        val partial = UIMessage.assistant("Часть ответа").copy(parts = listOf(
            me.rerere.ai.ui.UIMessagePart.Text("Часть ответа"),
            me.rerere.ai.ui.UIMessagePart.Reasoning("Часть рассуждения", finishedAt = null),
            me.rerere.ai.ui.UIMessagePart.Tool("started-ssh", "ssh_exec", "{\"command\":\"long-task\"}",
                approvalState = me.rerere.ai.ui.ToolApprovalState.Approved),
            me.rerere.ai.ui.UIMessagePart.Tool("waiting-root", "root_exec", "{}",
                approvalState = me.rerere.ai.ui.ToolApprovalState.Auto),
            me.rerere.ai.ui.UIMessagePart.Tool("finished", "get_time_info", "{}",
                output = listOf(me.rerere.ai.ui.UIMessagePart.Text("Готовый результат"))),
        ))
        val branch = MessageNode(messages = listOf(originalBranch, partial), selectIndex = 1)
        val source = Conversation(assistantId = assistant.id, title = "Исходный чат",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(user, branch))
        repository.insertConversation(source)
        recoveryFile.writeText(me.rerere.rikkahub.utils.JsonInstant.encodeToString(listOf(source.id.toString())))

        service.initializeConversation(source.id)
        val restored = service.getConversationFlow(source.id).value

        assertTrue("Process-death recovery must be visible", restored.currentMessages.last().toText().contains("Прервано: приложение было закрыто"))
        assertTrue(restored.currentMessages.last().toText().contains("Часть ответа"))
        assertEquals(user, restored.messageNodes.first())
        assertEquals(originalBranch, restored.messageNodes[1].messages.first())
        assertEquals(1, restored.messageNodes[1].selectIndex)
        assertEquals(source.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
        assertEquals(partial.id, restored.currentMessages.last().id)
        val tools = restored.currentMessages.last().getTools()
        assertTrue("No approved or automatic tool may be replayed after process death", tools.all { it.isExecuted })
        assertTrue(tools.take(2).all { it.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text.contains("process_interrupted") })
        assertEquals(partial.getTools().last(), tools.last())
        assertTrue(restored.currentMessages.last().parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Reasoning>().all { it.finishedAt != null })
        assertEquals(restored.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        service.initializeConversation(source.id)
        assertEquals("Recovery must be idempotent", restored.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
        assertTrue(requestedTexts.isEmpty())
    }

    @Test(timeout = 30_000) fun interruptionBeforeFirstNewChunkDoesNotRewriteThePreviousCompletedAnswer() = runBlocking {
        val original = UIMessage.assistant("Предыдущий завершённый ответ")
        val source = Conversation(assistantId = assistant.id, title = "Повтор ответа", config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("Старый вопрос").toMessageNode(), original.toMessageNode()))
        repository.insertConversation(source)
        recoveryFile.writeText("""{"${source.id}":"previous-process/${original.id}"}""")

        service.initializeConversation(source.id)

        val restored = repository.getConversationById(source.id)!!
        assertEquals("The baseline answer predates the interrupted operation and must keep its bytes", original,
            restored.messageNodes[1].currentMessage)
        assertEquals(3, restored.messageNodes.size)
        assertTrue(restored.currentMessages.last().toText().contains("Прервано: приложение было закрыто"))
    }

    @Test(timeout = 30_000) fun cancellingANewerWaitingGenerationDoesNotEraseItsStillActivePredecessorReceipt() = runBlocking {
        val source = Conversation(assistantId = assistant.id, title = "Перекрывающиеся операции",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(UIMessage.user("Вопрос").toMessageNode(),
                UIMessage.assistant("Сохранённая часть").toMessageNode()))
        repository.insertConversation(source)
        val recovery = ChatProcessRecovery(recoveryFile, repository, registry)
        val predecessor = recovery.begin(source.id, source.currentMessages.first().id)
        val waitingSuccessor = recovery.begin(source.id, source.currentMessages.last().id)

        recovery.finish(waitingSuccessor, source)

        assertTrue("An older operation still in non-cancellable cleanup must retain its own durable receipt",
            recoveryFile.readText().contains(source.id.toString()))
        recovery.finish(predecessor, source)
        assertFalse("Only completion of the last owner may remove the conversation marker",
            recoveryFile.readText().contains(source.id.toString()))
    }

    @Test(timeout = 30_000) fun interruptedReasoningInTheBaselineClosesWithoutChangingItsExistingText() = runBlocking {
        val partial = UIMessage.assistant("Сохранённая часть").copy(parts = listOf(
            me.rerere.ai.ui.UIMessagePart.Text("Сохранённая часть"),
            me.rerere.ai.ui.UIMessagePart.Reasoning("Неоконченное рассуждение", finishedAt = null)))
        val source = Conversation(assistantId = assistant.id, title = "Ожидающий повтор",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(partial.toMessageNode()))
        repository.insertConversation(source)
        recoveryFile.writeText("""{"${source.id}":"previous-process/${partial.id}"}""")

        service.initializeConversation(source.id)

        val restored = repository.getConversationById(source.id)!!
        val original = restored.messageNodes.first().currentMessage
        assertEquals(partial.id, original.id)
        assertEquals(partial.toText(), original.toText())
        assertEquals(partial.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>(),
            original.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>())
        assertTrue("An interrupted predecessor cannot retain an infinite reasoning indicator",
            original.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Reasoning>().all { it.finishedAt != null })
    }

    @Test(timeout = 30_000) fun failedStartReceiptCannotSurviveACompletedRetryAsAPhantomGeneration() = runBlocking {
        val source = Conversation(assistantId = assistant.id, title = "Повтор после ошибки сохранения",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(UIMessage.user("Вопрос").toMessageNode()))
        repository.insertConversation(source)
        val directory = java.io.File(recoveryFile.parentFile, "blocked-recovery-${source.id}")
        val marker = java.io.File(directory, "chat-executions.json")
        val recovery = ChatProcessRecovery(marker, repository, registry)
        try {
            recovery.recoverInterrupted()
            directory.writeText("Временно недоступный каталог")
            assertTrue("A failed durable receipt must prevent generation start", runCatching {
                recovery.begin(source.id)
            }.isFailure)
            assertTrue(directory.delete())
            assertTrue(directory.mkdirs())

            val retry = recovery.begin(source.id)
            recovery.finish(retry, source)

            assertFalse("A receipt that was never committed must not outlive the completed retry",
                marker.readText().contains(source.id.toString()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000) fun unreadableSubagentHistoryCannotBlockIndependentChatRecovery() = runBlocking {
        val partial = UIMessage.assistant("Сохранённый ответ")
        val source = Conversation(assistantId = assistant.id, title = "Независимый чат",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(partial.toMessageNode()))
        repository.insertConversation(source)
        recoveryFile.writeText("""{"${source.id}":"previous-process"}""")
        val directory = java.io.File(recoveryFile.parentFile, "unreadable-subagent-${source.id}")
        assertTrue(directory.mkdirs())
        val unreadable = java.io.File(directory, "damaged.run")
        val originalBytes = "Неповреждаемый исходный файл".toByteArray()
        unreadable.writeBytes(originalBytes)
        val damagedRegistry = me.rerere.rikkahub.subagent.SubAgentRegistry(directory,
            mockk<me.rerere.rikkahub.data.repository.CronPayloadCipher>(relaxed = true))
        try {
            val reported = mutableListOf<Exception>()
            val recovery = ChatProcessRecovery(recoveryFile, repository, damagedRegistry) { reported += it }

            assertTrue("A separate damaged subagent store must not prevent reading or settling chats",
                runCatching { recovery.recoverInterrupted() }.isSuccess)

            val restored = repository.getConversationById(source.id)!!
            assertTrue(restored.currentMessages.last().toText().contains("Прервано: приложение было закрыто"))
            assertTrue(unreadable.readBytes().contentEquals(originalBytes))
            assertTrue(reported.single().message.orEmpty().contains("Чаты доступны"))
            assertTrue(reported.single().cause == null)
            assertTrue("Subagent execution must remain fail-closed while its own history is unreadable",
                runCatching { damagedRegistry.recoverInterrupted() }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000) fun cancellationAfterDurableStartBeforeReturnRetainsTicketUntilCleanup() = runBlocking {
        val source = Conversation(assistantId = assistant.id, title = "Остановка до запуска ответа",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(UIMessage.assistant("Старый ответ").toMessageNode()))
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val durableStart = CompletableDeferred<Unit>()
        val releaseReturn = CompletableDeferred<Unit>()
        val recoveryField = ChatService::class.java.getDeclaredField("processRecovery").apply { isAccessible = true }
        val realRecovery = recoveryField.get(service) as ChatProcessRecovery
        val gatedRecovery = mockk<ChatProcessRecovery>()
        // All recovery operations use the same real state, Room and AtomicFile.
        coEvery { gatedRecovery.recoverInterrupted() } coAnswers { realRecovery.recoverInterrupted() }
        coEvery { gatedRecovery.begin(any(), any()) } coAnswers {
            val ticket = realRecovery.begin(firstArg(), secondArg())
            durableStart.complete(Unit)
            releaseReturn.await()
            ticket
        }
        coEvery { gatedRecovery.finish(any(), any()) } coAnswers { realRecovery.finish(firstArg(), secondArg()) }
        recoveryField.set(service, gatedRecovery)
        Dispatchers.setMain(dispatcher)
        try {
            service.sendMessage(source.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("Принятый вопрос")))
            kotlinx.coroutines.withTimeout(5_000) {
                while (!durableStart.isCompleted) {
                    dispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.withContext(Dispatchers.IO) { kotlinx.coroutines.yield() }
                }
            }
            // Real begin has completed its durable write; its return remains explicitly gated.
            assertTrue("The durable receipt must exist before cancellation",
                recoveryFile.readText().contains(source.id.toString()))
            assertEquals(source.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
            val job = service.getGenerationJobStateFlow(source.id).first { it != null }!!
            job.cancel()
            assertTrue("Cancellation must retain the receipt until its owner can receive the ticket",
                recoveryFile.readText().contains(source.id.toString()))
            releaseReturn.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) {
                while (!job.isCompleted) {
                    dispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.withContext(Dispatchers.IO) { kotlinx.coroutines.yield() }
                }
            }

            assertFalse("Cancellation at the IO return boundary must not orphan an unowned durable receipt",
                recoveryFile.readText().contains(source.id.toString()))
            assertTrue(requestedTexts.isEmpty())
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
            val queue = service.getMessageQueueFlow(source.id).value
            assertTrue(queue.paused)
            assertEquals("Принятый вопрос", queue.messages.single().parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text)
        } finally {
            releaseReturn.complete(Unit)
            dispatcher.scheduler.runCurrent()
            recoveryField.set(service, realRecovery)
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }
    }

    @Test(timeout = 30_000) fun startupRefreshesACompletedBackgroundSubagentCardEvenWithoutAnActiveGenerationMarker() = runBlocking {
        val oldOutput = """{"id":"background-run","label":"Проверка","status":"RUNNING"}"""
        val tool = me.rerere.ai.ui.UIMessagePart.Tool("dispatch", "subagent_dispatch", "{}",
            output = listOf(me.rerere.ai.ui.UIMessagePart.Text(oldOutput)))
        val source = Conversation(assistantId = assistant.id, title = "Фоновая задача",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(
                UIMessage.user("Запусти проверку").toMessageNode(),
                UIMessage.assistant("Проверка запущена").copy(parts = listOf(tool)).toMessageNode()))
        repository.insertConversation(source)
        val owner = me.rerere.rikkahub.subagent.SubAgentOwner(assistant.id.toString(), source.id.toString())
        registry.reserve(me.rerere.rikkahub.subagent.SubAgentRun("background-run", owner, "Проверка", model.id.toString()), 1)
        registry.finish(owner, "background-run") { it.copy(status = me.rerere.rikkahub.subagent.SubAgentStatus.INDETERMINATE,
            errorCode = "process_interrupted") }

        service.recoverInterruptedGenerations()

        val restored = repository.getConversationById(source.id)!!
        val output = restored.currentMessages.last().getTools().single().output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
        assertTrue("A background card cannot remain running after process death", output.contains("INDETERMINATE"))
        assertTrue(output.contains("Прервано: приложение было закрыто"))
        assertEquals(source.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
    }

    @Test(timeout = 30_000) fun startupClosesPersistedWorkspaceBackgroundSnapshotsWithoutLosingOutput() = runBlocking {
        val running = """{"id":"bg_old","command":"sleep 60","cwd":"/workspace","running":true,"exitCode":null,"stdout":"сохранённый вывод","stderr":""}"""
        val source = Conversation(assistantId = assistant.id, title = "Фоновая команда",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(
                UIMessage.user("Запусти проверку").toMessageNode(),
                UIMessage.assistant("").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Tool("background", "workspace_background_start", "{}",
                    output = listOf(me.rerere.ai.ui.UIMessagePart.Text(running))))).toMessageNode()))
        repository.insertConversation(source)
        java.io.File(recoveryFile.parentFile, "chat-background-executions.json").writeText(
            """{"${source.id}":"previous-process"}""")

        service.recoverInterruptedGenerations()

        val restored = repository.getConversationById(source.id)!!
        val output = restored.currentMessages.last().getTools().single().output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
        val json = kotlinx.serialization.json.Json.parseToJsonElement(output) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), json["running"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("process_interrupted"), json["error"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("сохранённый вывод"), json["stdout"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("bg_old"), json["id"])
        assertTrue(output.contains("Прервано: приложение было закрыто"))
        assertEquals(source.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
    }

    @Test(timeout = 30_000) fun legacyWorkspaceBackgroundSnapshotIsClosedEvenWhenTheOldVersionWroteNoMarker() = runBlocking {
        val running = """{"id":"bg_legacy","running":true,"exitCode":null,"stdout":"старый частичный вывод"}"""
        val source = Conversation(assistantId = assistant.id, title = "Старый чат", config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Tool("legacy-background", "workspace_background_start", "{}",
                    output = listOf(me.rerere.ai.ui.UIMessagePart.Text(running))))).toMessageNode()))
        repository.insertConversation(source)
        assertFalse(recoveryFile.exists())
        assertFalse(java.io.File(recoveryFile.parentFile, "chat-background-executions.json").exists())

        service.recoverInterruptedGenerations()

        val output = repository.getConversationById(source.id)!!.currentMessages.last().getTools().single()
            .output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
        assertTrue("Old cards must not stay running after an upgrade or old backup restore", output.contains("process_interrupted"))
        assertTrue(output.contains("старый частичный вывод"))
    }

    @Test(timeout = 30_000) fun oneUnreadableLegacyChatCannotBlockOtherRecoveryOrOverwriteItsOriginalRows() = runBlocking {
        fun legacy(title: String) = Conversation(assistantId = assistant.id, title = title,
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Tool("background", "workspace_background_start", "{}", output = listOf(
                    me.rerere.ai.ui.UIMessagePart.Text("""{"id":"bg_legacy","running":true}"""))))).toMessageNode()))
        val broken = legacy("Повреждённая история")
        val healthy = legacy("Сохранённая история")
        repository.insertConversation(broken)
        repository.insertConversation(healthy)
        val original = database.messageNodeDao().getNodesOfConversation(broken.id.toString()).single()
        val invalid = original.copy(messages = original.messages.dropLast(1))
        database.messageNodeDao().insert(invalid)

        service.recoverInterruptedGenerations()

        val output = repository.getConversationById(healthy.id)!!.currentMessages.last().getTools().single()
            .output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
        assertTrue("An unrelated broken chat cannot block a valid old receipt", output.contains("process_interrupted"))
        assertEquals(listOf(invalid), database.messageNodeDao().getNodesOfConversation(broken.id.toString()))
    }

    @Test(timeout = 30_000) fun legacyScanDoesNotChangeOrdinaryProviderOrMcpDataThatMentionsBackgroundTools() = runBlocking {
        val result = """{"status":"RUNNING","id":"foreign","text":"workspace_background_start"}"""
        val source = Conversation(assistantId = assistant.id, title = "Обычный ответ", config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("workspace_background_start").toMessageNode(),
                UIMessage.assistant("").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Tool("mcp-result", "mcp_echo", "{}",
                    output = listOf(me.rerere.ai.ui.UIMessagePart.Text(result))))).toMessageNode()))
        repository.insertConversation(source)

        service.recoverInterruptedGenerations()

        assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
    }

    @Test(timeout = 30_000) fun finishedGenerationKeepsDurableMarkerForItsStillRunningWorkspaceCommand() = runBlocking {
        generationBody = { input ->
            emit(GenerationChunk.Messages(input + UIMessage.assistant("").copy(parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Tool("background", "workspace_background_start", "{}",
                    output = listOf(me.rerere.ai.ui.UIMessagePart.Text("""{"id":"bg_active","running":true,"exitCode":null}""")))))))
        }
        val source = Conversation(assistantId = assistant.id, title = "Чат",
            config = ConversationConfig(chatModelId = model.id), messageNodes = emptyList())
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        service.sendMessage(source.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("Вопрос")))
        kotlinx.coroutines.withTimeout(5_000) {
            service.getGenerationJobStateFlow(source.id).first()?.join()
        }
        val marker = java.io.File(recoveryFile.parentFile, "chat-background-executions.json")
        assertTrue("Successful chat completion cannot discard the background process recovery marker", marker.isFile)
        assertTrue(marker.readText().contains(source.id.toString()))
    }

    @Test(timeout = 30_000) fun streamHasADurablePartialAnswerBeforeProviderOrLongCommandFinishes() = runBlocking {
        val partialReceived = kotlinx.coroutines.CompletableDeferred<Unit>()
        generationBody = { input ->
            emit(GenerationChunk.Messages(input + UIMessage.assistant("Уже полученная часть ответа")))
            partialReceived.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val source = Conversation(assistantId = assistant.id, title = "Чат",
            config = ConversationConfig(chatModelId = model.id), messageNodes = emptyList())
        repository.insertConversation(source)
        service.initializeConversation(source.id)
        service.addConversationReference(source.id)
        try {
            service.sendMessage(source.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("Вопрос")))
            kotlinx.coroutines.withTimeout(5_000) { partialReceived.await() }
            val stored = repository.getConversationById(source.id)!!
            assertEquals("The accepted question is durable", "Вопрос", stored.currentMessages.first().toText())
            assertTrue("A process kill must not discard the entire received answer",
                stored.currentMessages.any { it.toText() == "Уже полученная часть ответа" })
            assertTrue("An in-flight generation must have a durable recovery marker", recoveryFile.readText().contains(source.id.toString()))
        } finally {
            service.getGenerationJobStateFlow(source.id).first()?.cancel()
            service.getGenerationJobStateFlow(source.id).first()?.join()
        }
    }

    @Test(timeout = 30_000) fun completedMcpResultWithObjectCheckpointFieldSurvivesRoundTripRecovery() = runBlocking {
        assertCompletedMcpResultSurvivesRoundTrip(
            """{"checkpointed_before_launch":{"note":"ordinary tool data"},"value":"keep"}""")
    }

    @Test(timeout = 30_000) fun completedMcpResultWithObjectErrorFieldSurvivesRoundTripRecovery() = runBlocking {
        assertCompletedMcpResultSurvivesRoundTrip(
            """{"checkpointed_before_launch":true,"error":{"code":"external"},"value":"keep"}""")
    }

    private suspend fun assertCompletedMcpResultSurvivesRoundTrip(result: String) {
        val completedTool = me.rerere.ai.ui.UIMessagePart.Tool("completed-mcp", "mcp_echo", "{}",
            output = listOf(me.rerere.ai.ui.UIMessagePart.Text(result)))
        val selected = UIMessage.assistant("Выбранный вариант").copy(parts = listOf(completedTool))
        val alternative = UIMessage.assistant("Альтернативный вариант")
        val branch = MessageNode(messages = listOf(alternative, selected), selectIndex = 1)
        val source = Conversation(assistantId = assistant.id, title = "Завершённый MCP результат",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(
                UIMessage.user("Вопрос").toMessageNode(), branch))
        repository.insertConversation(source)
        recoveryFile.writeText("""{"${source.id}":"previous-process/${selected.id}"}""")

        service.initializeConversation(source.id)

        val restored = repository.getConversationById(source.id)!!
        val restoredBranch = restored.messageNodes[1]
        assertEquals("Recovery must preserve every message variant", 2, restoredBranch.messages.size)
        assertEquals("Recovery must preserve the selected branch", 1, restoredBranch.selectIndex)
        assertEquals(alternative, restoredBranch.messages[0])
        assertEquals("A completed ordinary tool result must keep its exact JSON and unknown fields",
            selected, restoredBranch.messages[1])
        val restoredTool = restoredBranch.messages[1].getTools().single()
        assertTrue(restoredTool.isExecuted)
        assertEquals(result, restoredTool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text)
        assertTrue(restored.currentMessages.last().toText().contains("Прервано: приложение было закрыто"))
        assertTrue("Recovery must not start generation or replay the completed tool", requestedTexts.isEmpty())
        service.initializeConversation(source.id)
        assertEquals("Recovery must remain idempotent", restored.messageNodes,
            repository.getConversationById(source.id)!!.messageNodes)
        assertTrue(requestedTexts.isEmpty())
    }

    @Test(timeout = 30_000) fun recoveryJsonTypeMatrixPreservesOrdinaryResultsAndUpdatesRealCheckpoints() {
        val ordinaryResults = listOf(
            """{"checkpointed_before_launch":[],"value":"keep"}""",
            """{"checkpointed_before_launch":null,"error":"root_execution_indeterminate","value":"keep"}""",
            """{"checkpointed_before_launch":false,"error":"root_execution_indeterminate","value":"keep"}""",
            """{"checkpointed_before_launch":true,"error":"external","value":"keep"}""",
            """{"checkpointed_before_launch":true,"error":null,"value":"keep"}""",
            """{"checkpointed_before_launch":true,"error":[],"value":"keep"}""",
        )
        val checkpointResults = listOf("root_execution_indeterminate", "tool_execution_indeterminate").map { error ->
            """{"checkpointed_before_launch":true,"error":"$error","value":"keep","unknown":{"nested":true}}"""
        }
        val message = UIMessage.assistant("Завершённые инструменты").copy(
            parts = (ordinaryResults + checkpointResults).mapIndexed { index, result ->
                me.rerere.ai.ui.UIMessagePart.Tool("completed-$index", "mcp_echo", "{}",
                    output = listOf(me.rerere.ai.ui.UIMessagePart.Text(result)))
            })
        val source = Conversation(assistantId = assistant.id, title = "Матрица JSON типов",
            config = ConversationConfig(chatModelId = model.id), messageNodes = listOf(message.toMessageNode()))

        val restored = interruptConversation(source, baselineMessageId = message.id)

        val tools = restored.messageNodes.first().currentMessage.getTools()
        assertEquals(ordinaryResults.size + checkpointResults.size, tools.size)
        ordinaryResults.forEachIndexed { index, result ->
            assertEquals("Ordinary structured tool data must remain byte-for-byte unchanged at index $index",
                result, tools[index].output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text)
        }
        checkpointResults.forEachIndexed { offset, result ->
            val original = kotlinx.serialization.json.Json.parseToJsonElement(result) as kotlinx.serialization.json.JsonObject
            val updated = kotlinx.serialization.json.Json.parseToJsonElement(
                tools[ordinaryResults.size + offset].output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
            ) as kotlinx.serialization.json.JsonObject
            original.forEach { (name, value) -> assertEquals("Checkpoint field $name must be preserved", value, updated[name]) }
            assertEquals(kotlinx.serialization.json.JsonPrimitive(PROCESS_INTERRUPTED_MESSAGE), updated["message"])
        }
    }
}
