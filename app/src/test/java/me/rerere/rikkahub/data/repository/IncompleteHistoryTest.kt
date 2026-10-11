package me.rerere.rikkahub.data.repository

import android.app.Application
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

/** Only the failing page and external Android/provider boundaries are replaced. */
internal class IncompleteHistoryFixture : AutoCloseable {
    val context: Application = RuntimeEnvironment.getApplication()
    val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val id = Uuid.parse("10000000-0000-0000-0000-000000000001")
    val settings = me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures.settings
    val attachment = java.io.File(context.filesDir, "upload/history-recovery.png").apply { parentFile!!.mkdirs(); writeText("original attachment bytes") }
    val files = mockk<FilesManager>(relaxed = true)
    val store = mockk<SettingsStore>(relaxed = true).also {
        every { it.settingsFlow } returns MutableStateFlow(settings)
        every { it.settingsFlowRaw } returns MutableStateFlow(settings)
    }
    var failure: (() -> RuntimeException)? = { SQLiteBlobTooBigException("fixture CursorWindow failure") }
    var failAt: (Int) -> Boolean = { it == 64 }
    var abortAfter: Int? = null
    val offsets = mutableListOf<Int>()
    val dao = object : MessageNodeDAO by database.messageNodeDao() {
        override suspend fun getNodesOfConversationPaged(conversationId: String, limit: Int, offset: Int): List<MessageNodeEntity> {
            kotlinx.coroutines.yield()
            offsets.add(offset)
            abortAfter?.let { if (offset > it) throw UnsupportedOperationException("fixture stopped unbounded pagination") }
            if (failAt(offset)) failure?.let { throw it() }
            return database.messageNodeDao().getNodesOfConversationPaged(conversationId, limit, offset)
        }
    }
    val fts = me.rerere.rikkahub.data.db.fts.MessageFtsManager(database)
    val repository = ConversationRepository(database.conversationDao(), dao, database.favoriteDao(), database,
        files, fts)
    val scope = AppScope()
    val providerCalls = java.util.concurrent.atomic.AtomicInteger()
    val providers = me.rerere.ai.provider.ProviderManager(okhttp3.OkHttpClient(), context).also { manager ->
        manager.registerProvider("openai", object : me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI) = providerSetting.models
            override suspend fun generateText(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI,
                messages: List<UIMessage>, params: me.rerere.ai.provider.TextGenerationParams): me.rerere.ai.provider.TextGenerationResult {
                providerCalls.incrementAndGet()
                error("Fixture provider request was executed")
            }
            override suspend fun streamText(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI,
                messages: List<UIMessage>, params: me.rerere.ai.provider.TextGenerationParams): kotlinx.coroutines.flow.Flow<me.rerere.ai.ui.StreamChunk> {
                providerCalls.incrementAndGet()
                error("Fixture provider request was executed")
            }
        })
    }
    val generation = me.rerere.rikkahub.data.ai.GenerationLoop(context, providers, JsonInstant)
    val translation = me.rerere.rikkahub.data.ai.TranslationHandler(providers)
    private val approvalFile = java.io.File(context.cacheDir, "${Uuid.random()}.preferences_pb")
    val approvals = me.rerere.rikkahub.data.preferences.ToolApprovalPreferences(
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope, produceFile = { approvalFile }))
    val template = me.rerere.rikkahub.data.ai.transformers.TemplateTransformer(
        io.pebbletemplates.pebble.PebbleEngine.Builder()
            .loader(me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader(store)).build(), store)
    val service = ChatService(context, scope, me.rerere.rikkahub.data.event.AppEventBus(), store, repository,
        MemoryRepository(database.memoryDao()), generation, translation, template,
        providers, mockk(relaxed = true), mockk(relaxed = true), files, mockk(relaxed = true),
        FolderRepository(database.folderDao(), database.conversationDao()), approvals,
        me.rerere.rikkahub.costguards.TokenBudgetStore(java.io.File(context.cacheDir, "history-test-budget")),
        me.rerere.rikkahub.subagent.SubAgentRegistry())
    suspend fun seed() {
        // Index writes need no Android-only jieba tokenizer; use its exact writable column schema.
        database.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
        database.conversationDao().insert(ConversationEntity(id.toString(), settings.assistantId.toString(),
            "Исходное название", "[]", 1L, 2L, "[]", false, config = JsonInstant.encodeToString(ConversationConfig(chatModelId = settings.chatModelId))))
        database.messageNodeDao().insertAll((0 until 130).map { index ->
            val message = when (index) {
                0 -> UIMessage.user("attachment").copy(parts = listOf(UIMessagePart.Image(android.net.Uri.fromFile(attachment).toString())))
                2 -> UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("pending", "get_time_info", "{}", approvalState = ToolApprovalState.Pending)))
                else -> UIMessage.user("original row $index")
            }
            MessageNodeEntity(Uuid.parse("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}").toString(),
                id.toString(), index, JsonInstant.encodeToString(listOf(message)), 0)
        })
    }
    suspend fun rows() = database.messageNodeDao().getNodesOfConversation(id.toString())
    fun ftsRows(): List<List<String>> = database.openHelper.readableDatabase.query("SELECT text, node_id, message_id, conversation_id, title, update_at FROM message_fts ORDER BY node_id, message_id").use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until 6).map { cursor.getString(it) }) }
    }
    override fun close() { service.cleanup(); scope.cancel(); database.close(); approvalFile.delete() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncompleteHistoryTest {
    private lateinit var fixture: IncompleteHistoryFixture
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); fixture = IncompleteHistoryFixture(); runBlocking { fixture.seed() } }
    @After fun tearDown() { fixture.close(); Dispatchers.resetMain() }

    @Test fun blobFailureCannotRewriteOriginalRowsOrTitle() = preservesRowsAfterFailure { SQLiteBlobTooBigException("fixture CursorWindow failure") }
    @Test fun illegalStateFailureCannotRewriteOriginalRowsOrTitle() = preservesRowsAfterFailure { IllegalStateException("fixture CursorWindow failure") }
    private fun preservesRowsAfterFailure(error: () -> RuntimeException) = runBlocking {
        fixture.failure = error
        val before = fixture.rows()
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        runCatching { fixture.repository.updateConversation(loaded.copy(title = "Новое название")) }
        assertEquals("Full save must preserve every original row", before, fixture.rows())
        assertEquals("Исходное название", fixture.database.conversationDao().getConversationById(fixture.id.toString())!!.title)
    }
    @Test fun loadingStopsAtTheFirstFailedPage() = runBlocking {
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        assertEquals("Only the verified prefix may be exposed", 64, loaded.messageNodes.size)
        assertEquals(listOf(0, 64), fixture.offsets)
    }
    @Test fun persistentPageFailureTerminatesAtTheFirstFailure() = runBlocking {
        fixture.failAt = { it >= 64 }
        fixture.abortAfter = 256 // Bound the broken implementation instead of hanging the test worker.
        val loaded = runCatching { kotlinx.coroutines.withTimeout(1000) { fixture.repository.getConversationById(fixture.id) } }.getOrNull()
        assertNotNull("Persistent page failure must return a readable prefix promptly", loaded)
        assertEquals(64, loaded!!.messageNodes.size)
        assertEquals(listOf(0, 64), fixture.offsets)
    }
    @Test fun cancellationIsPropagatedInsteadOfSkippingAPage() = runBlocking {
        fixture.failure = { kotlinx.coroutines.CancellationException("fixture cancellation") }
        val result = runCatching { fixture.repository.getConversationById(fixture.id) }
        assertTrue("Cancellation must not become a writable partial history", result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
    @Test fun summariesCannotOverwriteFullHistoryWithAnEmptySnapshot() = runBlocking {
        val before = fixture.rows()
        val summary = fixture.repository.getConversationsOfAssistant(fixture.settings.assistantId).first().single()
        runCatching { fixture.repository.updateConversation(summary) }
        assertEquals(before, fixture.rows())
    }
    @Test fun reconstructingIncompleteSnapshotCannotBypassRepositoryGuard() = runBlocking {
        val before = fixture.rows()
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        val unmarked = me.rerere.rikkahub.data.model.Conversation.ofId(fixture.id,
            assistantId = loaded.assistantId, messages = loaded.messageNodes)
        runCatching { fixture.repository.updateConversation(unmarked) }
        assertEquals(before, fixture.rows())
    }
    @Test fun titleBackfillCannotBypassReadOnlyHistory() = runBlocking {
        fixture.repository.getConversationById(fixture.id)
        val before = fixture.database.conversationDao().getConversationById(fixture.id.toString())!!
        runCatching { fixture.repository.backfillConversationTitle(fixture.id, before.title, "Новое название") }
        assertEquals("Metadata CAS must respect the failed history load", before,
            fixture.database.conversationDao().getConversationById(fixture.id.toString()))
    }
    @Test fun completeHistoryAllowsFullSaveAndPreservesOrderAndBranches() = runBlocking {
        fixture.failure = null
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        val changed = loaded.copy(title = "Изменено", messageNodes = loaded.messageNodes.dropLast(1).mapIndexed { index, node ->
            if (index == 3) node.copy(messages = node.messages + UIMessage.assistant("new branch"), selectIndex = 1) else node
        })
        fixture.repository.updateConversation(changed)
        assertEquals(changed, fixture.repository.getConversationById(fixture.id))
        assertEquals((0 until 129).toList(), fixture.rows().map { it.nodeIndex })
    }
    @Test fun rebuildingIndexesMustNotReplaceAFullIndexWithAPartialHistory() = runBlocking {
        fixture.failure = null
        fixture.fts.indexConversation(fixture.repository.getConversationById(fixture.id)!!)
        val before = fixture.ftsRows()
        assertTrue(before.isNotEmpty())
        fixture.failure = { SQLiteBlobTooBigException("fixture CursorWindow failure") }
        runCatching { fixture.repository.rebuildAllIndexes() }
        assertEquals("The complete search index must survive the failed rebuild", before, fixture.ftsRows())
    }
    @Test fun successfulReloadDoesNotAuthorizeAnOldIncompleteSnapshot() = runBlocking {
        val old = fixture.repository.getConversationById(fixture.id)!!
        fixture.failure = null
        val full = fixture.repository.getConversationById(fixture.id)!!
        fixture.repository.updateConversation(full.copy(title = "После повторной загрузки"))
        assertEquals(130, fixture.rows().size)
        assertTrue("Old snapshot remains read-only after reload", runCatching { fixture.repository.updateConversation(old) }.isFailure)
        assertEquals(130, fixture.rows().size)
    }
    @Test fun deleteCannotChangeSessionOrDeleteFiles() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        val rows = fixture.rows()
        runCatching { fixture.service.deleteMessage(fixture.id, before.currentMessages.first().id) }
        assertEquals("Read-only delete must preserve memory", before, fixture.service.getConversationFlow(fixture.id).value)
        assertEquals(rows, fixture.rows())
        verify(exactly = 0) { fixture.files.deleteChatFiles(any()) }
    }
    @Test fun directStateUpdateCannotChangeSessionOrDeleteFiles() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        var callbackInvoked = false
        runCatching { fixture.service.updateConversationState(fixture.id) { callbackInvoked = true; it.copy(messageNodes = emptyList()) } }
        assertFalse("Guard must run before the mutation callback", callbackInvoked)
        assertEquals("Read-only update must preserve memory", before, fixture.service.getConversationFlow(fixture.id).value)
        verify(exactly = 0) { fixture.files.deleteChatFiles(any()) }
    }
    @Test fun forkCannotInsertConversationOrCopyAttachments() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        runCatching { fixture.service.forkConversationAtMessage(fixture.id, before.currentMessages.first().id) }
        assertEquals("Read-only fork must not insert a conversation", 1, fixture.repository.countConversations())
        verify(exactly = 0) { fixture.files.createChatFilesByContents(any()) }
    }
    @Test fun approvalCannotGrantPersistentPermissionsOrChangeHistory() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        runCatching { fixture.service.handleToolApproval(fixture.id, "pending", true,
            scope = ChatService.ApprovalScope.Always, toolName = "get_time_info").join() }
        assertTrue("Persistent permissions must remain empty", fixture.approvals.current().isEmpty())
        assertEquals(before, fixture.service.getConversationFlow(fixture.id).value)
    }
    @Test fun sendWaitsForInitializationAndRejectsIncompleteHistoryBeforeQueueing() = runBlocking {
        val before = fixture.rows()
        fixture.service.sendMessage(fixture.id, listOf(UIMessagePart.Text("new message")), answer = false)
        fixture.service.getGenerationJobStateFlow(fixture.id).first()?.join()
        kotlinx.coroutines.withTimeout(2000) { fixture.service.errors.first { it.any { error -> error.error.message == "Часть истории не загрузилась, чат открыт только для чтения" } } }
        assertEquals(before, fixture.rows())
        assertTrue(fixture.service.getMessageQueueFlow(fixture.id).value.messages.isEmpty())
        assertTrue(fixture.service.errors.value.any { it.error.message == "Часть истории не загрузилась, чат открыт только для чтения" })
    }
    @Test fun voiceSubmissionSettlesWithReadOnlyErrorAndNeverEnqueues() = runBlocking {
        val reply = fixture.service.enqueueVoiceMessage(fixture.id, "voice message")
        val result = runCatching { kotlinx.coroutines.withTimeout(2000) { reply.await() } }
        assertEquals("Часть истории не загрузилась, чат открыт только для чтения", result.exceptionOrNull()?.message)
        assertTrue(fixture.service.getMessageQueueFlow(fixture.id).value.messages.isEmpty())
        assertEquals(130, fixture.rows().size)
    }
    @Test fun translationCannotContactProviderOrChangeMemory() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        fixture.service.translateMessage(fixture.id, before.currentMessages[3], java.util.Locale.ENGLISH)
        kotlinx.coroutines.withTimeout(2000) { fixture.service.errors.first { it.isNotEmpty() } }
        assertEquals("No provider request may start", 0, fixture.providerCalls.get())
        assertEquals(before, fixture.service.getConversationFlow(fixture.id).value)
    }
    @Test fun insertOfAnIncompleteSnapshotIsBlockedBeforeNewConversationRows() = runBlocking {
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        val result = runCatching { fixture.repository.insertConversation(loaded.copy(id = Uuid.random())) }
        assertTrue(result.isFailure)
        assertEquals(1, fixture.repository.countConversations())
    }
    @Test fun titleGenerationCannotContactProvider() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        runCatching { fixture.service.generateTitle(fixture.id, before, force = true) }
        assertEquals("No provider request may start", 0, fixture.providerCalls.get())
        assertEquals(before, fixture.service.getConversationFlow(fixture.id).value)
    }
    @Test fun regenerationCannotTruncateHistory() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val before = fixture.service.getConversationFlow(fixture.id).value
        runCatching {
            fixture.service.regenerateAtMessage(fixture.id, before.currentMessages.first(), false)
            fixture.service.getGenerationJobStateFlow(fixture.id).first()?.join()
        }
        assertEquals(before, fixture.service.getConversationFlow(fixture.id).value)
        assertEquals("No generation provider request may start", 0, fixture.providerCalls.get())
    }
    @Test fun recoveryArchiveIncludesUnloadedRowsAndOriginalAttachments() = runBlocking {
        fixture.service.initializeConversation(fixture.id)
        val workflows = Room.inMemoryDatabaseBuilder(fixture.context, me.rerere.rikkahub.workflow.db.WorkflowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val backup = me.rerere.rikkahub.data.sync.BackupManager(fixture.context, fixture.database,
                fixture.store, JsonInstant, workflows).createBackup(includeDatabase = true, includeFiles = true)
            assertTrue(backup.isFile && backup.length() > 0)
            val snapshot = java.io.File(fixture.context.cacheDir, "test-recovery-snapshot.db")
            java.util.zip.ZipFile(backup).use { zip ->
                zip.getInputStream(zip.getEntry("rikka_hub.db")).use { input -> snapshot.outputStream().use { input.copyTo(it) } }
                assertEquals("original attachment bytes", zip.getInputStream(zip.getEntry("upload/history-recovery.png")).bufferedReader().use { it.readText() })
                assertNotNull(zip.getEntry("settings.json"))
            }
            android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM message_node", null).use { cursor -> cursor.moveToFirst(); assertEquals(130, cursor.getInt(0)) }
                db.rawQuery("SELECT messages FROM message_node WHERE node_index=64", null).use { cursor ->
                    cursor.moveToFirst(); assertEquals(fixture.rows()[64].messages, cursor.getString(0))
                }
            }
            snapshot.delete(); backup.delete(); Unit
        } finally { workflows.close() }
    }
    @Test fun deleteAndUndoCannotReduceOriginalHistory() = runBlocking {
        val before = fixture.rows()
        val loaded = fixture.repository.getConversationById(fixture.id)!!
        runCatching { fixture.repository.deleteConversation(loaded); fixture.repository.insertConversation(loaded) }
        assertEquals("Delete and Undo must preserve unread rows", before, fixture.rows())
    }
}
