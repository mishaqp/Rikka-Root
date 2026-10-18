package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import io.mockk.mockk
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationRestoreOwnershipCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var workflows: WorkflowDatabase
    private lateinit var settings: SettingsStore
    private lateinit var guard: AssistantOwnershipGuard
    private enum class Writer { UPDATE, CHECKPOINT }

    @Before fun setup() {
        val root = temporary.newFolder("application")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        }
        singletonField().set(null, null)
        Dispatchers.setMain(StandardTestDispatcher())
        reopenDatabases()
        // No background consumer: reads below collect the real authoritative DataStore flow directly.
        settings = SettingsStore(context, AppScope().apply { cancel() })
        val application = startKoin {
            modules(repositoryModule, module {
                single { settings }
                single { PebbleEngine.Builder().build() }
            })
        }
        guard = application.koin.get()
    }

    @After fun cleanup() = runBlocking {
        database.close()
        workflows.close()
        stopKoin()
        singletonField().get(null)?.let { store ->
            var delegate: Any = store
            while (delegate.javaClass.declaredFields.none { it.name == "scope" }) {
                delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate) as Any
            }
            val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        singletonField().set(null, null)
        Dispatchers.resetMain()
    }

    @Test fun `actual settings only restore permits same owner update without recreating assistant`() = runBlocking {
        restoredWrite(Writer.UPDATE)
    }

    @Test fun `actual settings only restore permits same owner checkpoints without recreating assistant`() = runBlocking {
        restoredWrite(Writer.CHECKPOINT)
    }

    @Test fun `legacy orphan conversation permits same owner update`() = runBlocking {
        legacyWrite(Writer.UPDATE)
    }

    @Test fun `legacy orphan conversation permits same owner checkpoints`() = runBlocking {
        legacyWrite(Writer.CHECKPOINT)
    }

    @Test fun `update may change an existing binding to a known live owner`() = runBlocking {
        liveOwnerChange(Writer.UPDATE)
    }

    @Test fun `checkpoint may change an existing binding to a known live owner`() = runBlocking {
        liveOwnerChange(Writer.CHECKPOINT)
    }

    @Test fun `deleted row cannot be recreated by a stale update or checkpoint`() = runBlocking {
        val owner = Assistant(name = "Удалённый владелец")
        seed(owner)
        val original = chat(owner)
        repository().insertConversation(original)
        repository().deleteConversation(original)
        seed(Assistant(name = "Текущий"))
        for (writer in Writer.entries) {
            assertRejected { write(writer, original.copy(messageNodes = original.messageNodes + node("Не сохранять")), null) }
            assertNull(repository().getConversationById(original.id))
            assertTrue(database.messageNodeDao().getNodesOfConversation(original.id.toString()).isEmpty())
        }
    }

    @Test fun `new conversation cannot bind to absent assistant`() = runBlocking {
        val missingOwner = Assistant(name = "Нет в настройках")
        seed(Assistant(name = "Текущий"))
        val proposed = chat(missingOwner)
        assertRejected { repository().insertConversation(proposed) }
        assertNull(repository().getConversationById(proposed.id))
        assertTrue(database.messageNodeDao().getNodesOfConversation(proposed.id.toString()).isEmpty())
    }

    @Test fun `insert cannot replace existing orphan under an absent assistant`() = runBlocking {
        val absent = Assistant(name = "Старый владелец")
        seed(Assistant(name = "Текущий"))
        val original = chat(absent)
        seedLegacyRow(original)
        val before = repository().getConversationById(original.id)!!
        assertRejected { repository().insertConversation(before.copy(messageNodes = listOf(node("Замена запрещена")))) }
        assertEquals(before, repository().getConversationById(original.id))
    }

    @Test fun `stale snapshot cannot restore old owner after actual row was rebound`() = runBlocking {
        val oldOwner = Assistant(name = "Старый")
        val actualOwner = Assistant(name = "Новый")
        seed(oldOwner, actualOwner)
        val original = chat(oldOwner)
        repository().insertConversation(original)
        repository().updateConversationAssistant(original.id, actualOwner.id)
        seed(actualOwner)
        val before = repository().getConversationById(original.id)!!
        for (writer in Writer.entries) {
            assertRejected { write(writer, original.copy(messageNodes = original.messageNodes + node("Устаревшая запись")), null) }
            assertEquals(before, repository().getConversationById(original.id))
        }
    }

    @Test fun `explicit rebind and owner changing saves reject an absent target`() = runBlocking {
        val owner = Assistant(name = "Есть в настройках")
        val absent = Assistant(name = "Нет в настройках")
        seed(owner)
        val original = chat(owner)
        repository().insertConversation(original)
        val before = repository().getConversationById(original.id)!!
        assertRejected { repository().updateConversationAssistant(original.id, absent.id) }
        for (writer in Writer.entries) {
            assertRejected { write(writer, before.copy(assistantId = absent.id, messageNodes = before.messageNodes + node("Новая привязка")), null) }
        }
        assertEquals(before, repository().getConversationById(original.id))
    }

    private suspend fun liveOwnerChange(writer: Writer) {
        val originalOwner = Assistant(name = "Первый")
        val liveTarget = Assistant(name = "Второй")
        seed(originalOwner, liveTarget)
        val original = chat(originalOwner)
        repository().insertConversation(original)
        val loaded = repository().getConversationById(original.id)!!
        val changed = loaded.copy(assistantId = liveTarget.id, messageNodes = loaded.messageNodes + node("Разрешённая привязка"))
        write(writer, changed, loaded.messageNodes)
        assertEquals(changed, repository().getConversationById(original.id))
        assertTrue(settings.settingsFlowRaw.first().assistants.any { it.id == liveTarget.id })
    }

    private suspend fun restoredWrite(writer: Writer) {
        val owner = Assistant(name = "Локальный владелец")
        seed(owner)
        val original = chat(owner)
        repository().insertConversation(original)
        val incoming = Assistant(name = "Из архива")
        val archive = temporary.newFile("settings-only.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(JsonInstant.encodeToString(Settings(assistants = listOf(incoming), assistantId = incoming.id)).toByteArray())
            zip.closeEntry()
        }
        BackupManager(context, database, settings, JsonInstant, workflows)
            .stageRestore(archive, includeDatabase = false, includeFiles = false)
        database.close()
        workflows.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        reopenDatabases()
        assertEquals(incoming.id, settings.settingsFlowRaw.first().assistantId)
        assertFalse(settings.settingsFlowRaw.first().assistants.any { it.id == owner.id })
        assertExistingWrite(writer, original)
        // Compatibility is confined to existing bindings; restore must not resurrect the assistant catalog.
        assertFalse(settings.settingsFlowRaw.first().assistants.any { it.id == owner.id })
    }

    private suspend fun legacyWrite(writer: Writer) {
        val absent = Assistant(name = "Владелец старого архива")
        seed(Assistant(name = "Текущий"))
        val original = chat(absent)
        // Historical row, as obtained from a pre-guard database/backup; no live writer bypass is used afterwards.
        seedLegacyRow(original)
        assertFalse(settings.settingsFlowRaw.first().assistants.any { it.id == absent.id })
        assertExistingWrite(writer, original)
        assertFalse(settings.settingsFlowRaw.first().assistants.any { it.id == absent.id })
    }

    private suspend fun assertExistingWrite(writer: Writer, original: Conversation) {
        val before = repository().getConversationById(original.id)!!
        assertEquals(original.assistantId, before.assistantId)
        assertEquals(original.messageNodes, before.messageNodes)
        val first = before.copy(title = "Сохранён", messageNodes = before.messageNodes + node("Первое дополнение"))
        write(writer, first, null)
        val saved = repository().getConversationById(original.id)!!
        assertEquals(first, saved)
        if (writer == Writer.CHECKPOINT) {
            val incremental = saved.copy(messageNodes = saved.messageNodes + node("Следующий фрагмент"))
            write(writer, incremental, saved.messageNodes)
            assertEquals(incremental, repository().getConversationById(original.id))
        }
        assertEquals(original.assistantId, repository().getConversationById(original.id)!!.assistantId)
    }

    private suspend fun write(writer: Writer, conversation: Conversation, previous: List<MessageNode>?) {
        when (writer) {
            Writer.UPDATE -> repository().updateConversation(conversation)
            Writer.CHECKPOINT -> repository().checkpointGeneratedNodes(conversation, previous)
        }
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ownership rejection, got $error", error is IllegalStateException)
    }

    private suspend fun seedLegacyRow(conversation: Conversation) {
        database.conversationDao().insert(repository().conversationToConversationEntity(conversation))
        database.messageNodeDao().insertAll(conversation.messageNodes.mapIndexed { index, item ->
            MessageNodeEntity(item.id.toString(), conversation.id.toString(), index, JsonInstant.encodeToString(item.messages), item.selectIndex)
        })
    }
    private suspend fun seed(vararg owners: Assistant) {
        SettingsStore.restoreBeforeInitialization(context, Settings(assistants = owners.toList(), assistantId = owners.first().id))
    }
    private fun reopenDatabases() {
        database = Room.databaseBuilder(context, AppDatabase::class.java,
            context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME).absolutePath).allowMainThreadQueries().build()
        workflows = Room.databaseBuilder(context, WorkflowDatabase::class.java,
            context.getDatabasePath(WorkflowDatabase.NAME).absolutePath).allowMainThreadQueries().build()
    }
    private fun repository() = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
        database, mockk(relaxed = true), mockk(relaxed = true), ownershipGuard = guard)
    private fun chat(owner: Assistant) = Conversation(assistantId = owner.id, title = "Существующий чат", messageNodes = listOf(node("Исходное сообщение")))
    private fun node(text: String) = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text))))
    private fun singletonField() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").also { it.isAccessible = true }
}
