package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.RoomDatabase
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
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
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeBackupAttachmentPathsPart3Test {
    @get:Rule val temporary = TemporaryFolder()
    private val opened = mutableListOf<() -> Unit>()
    private fun context(name: String): Context { val root = temporary.newFolder(name); return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) =
            (if (File(name).isAbsolute) File(name) else File(root, "databases/$name"))
                .apply { parentFile!!.mkdirs() }
    } }
    private fun main(context: Context, name: String = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME).absolutePath): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build().also { db -> opened += { db.close() } }
    // Runtime's libsimple FTS extension is Android-only. This environment adapter still opens
    // actual on-disk SQLite/Room and validates/checkpoints it; no backup/path logic is mocked.
    @Before fun sqliteEnvironment() {
        mockkObject(DatabaseBackup, AppDatabaseFactory, WorkflowDatabase.Companion)
        every { DatabaseBackup.normalize(any(), any()) } answers {
            val file = secondArg<File>()
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            }
            DatabaseBackup.removeSidecars(file)
        }
        every { AppDatabaseFactory.create(any(), any()) } answers { main(firstArg(), secondArg()) }
        // Preserve WorkflowDatabaseBackup's real schema validation and secret sanitization;
        // only replace its Android-only Requery factory with Robolectric native SQLite.
        every { WorkflowDatabase.create(any(), any()) } answers {
            Room.databaseBuilder(firstArg<Context>(), WorkflowDatabase::class.java, secondArg<String>())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .allowMainThreadQueries()
                .build()
        }
    }
    @After fun cleanup() { opened.asReversed().forEach { it() }; unmockkObject(DatabaseBackup, AppDatabaseFactory, WorkflowDatabase.Companion) }

    private suspend fun restore(includeFiles: Boolean): Triple<List<UIMessage>, List<UIMessage>, Pair<Context, Context>> {
        val source = context("user-10"); val target = context("user-0")
        fun sourceFile(path: String): File = File(source.filesDir, path).apply { parentFile!!.mkdirs(); writeText("EXACT BYTES $path") }
        val pdf = sourceFile("upload/source document.pdf"); val image = sourceFile("upload/image.png")
        val audio = sourceFile("media_creation/task/audio.mp3"); val video = sourceFile("media_creation/task/video.mp4")
        fun oldUrl(path: String) = File("/data/user/10/${source.packageName}/files/$path").toUri().toString()
        val old = oldUrl("upload/source document.pdf")
        val nested = UIMessagePart.Tool("outer", "read_file", buildJsonObject { put("path", old) }.toString(), output = listOf(
            UIMessagePart.Tool("inner", "read_file", "{}", output = listOf(UIMessagePart.Audio(oldUrl("media_creation/task/audio.mp3")), UIMessagePart.Video(oldUrl("media_creation/task/video.mp4")),
                UIMessagePart.Document("content://foreign/document/keep", "saf"), UIMessagePart.Image("https://example.invalid/keep.png"),
                UIMessagePart.Document(oldUrl("upload/missing.pdf"), "missing"),
                UIMessagePart.Document("file:///outside/files/other/keep.pdf", "foreign"),
                UIMessagePart.Document("file:///storage/emulated/0/files/upload/image.png", "external collision"),
                UIMessagePart.Document("file://foreign-authority/data/user/10/${source.packageName}/files/upload/image.png", "foreign authority"),
                UIMessagePart.Document("file:///data/user/10/${source.packageName}/files/upload/../escape.pdf", "dot traversal")))))
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("USER literal $old"), UIMessagePart.Document(old, "source document.pdf"), UIMessagePart.Image(oldUrl("upload/image.png"), buildJsonObject { put("sourceFile", old) }), nested)))
        val alternative = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(oldUrl("upload/image.png"))))
        val all = messages + alternative
        val database = main(source)
        val chat = Conversation(assistantId = Uuid.random(), title = "Backup fixture", messageNodes = listOf(MessageNode.of(messages.single())))
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database, mockk(relaxed = true), mockk(relaxed = true))
        database.conversationDao().insert(repository.conversationToConversationEntity(chat))
        database.messageNodeDao().insertAll(listOf(MessageNodeEntity(chat.messageNodes.single().id.toString(), chat.id.toString(), 0, JsonInstant.encodeToString(all), 1)))
        val workflows = Room.databaseBuilder(source, WorkflowDatabase::class.java, source.getDatabasePath(WorkflowDatabase.NAME).absolutePath).allowMainThreadQueries().build().also { db -> opened += { db.close() } }
        val settings = Settings(assistants = emptyList())
        val store = mockk<SettingsStore>(); every { store.settingsFlowRaw } returns flowOf(settings)
        val sourceManager = BackupManager(source, database, store, JsonInstant, workflows)
        val archive = sourceManager.createBackup(includeDatabase = true, includeFiles = true)
        val archiveBytes = archive.readBytes()
        BackupManager(target, mockk(), store, JsonInstant, mockk()).stageRestore(archive, includeDatabase = true, includeFiles = includeFiles)
        PendingRestore(File(target.noBackupFilesDir, "backup-restore"), target.getDatabasePath(SQLiteConfiguration.DATABASE_NAME), target.filesDir,
            listOf(target.getDatabasePath(WorkflowDatabase.NAME))).apply { }
        assertArrayEquals("Restore must never rewrite the source archive", archiveBytes, archive.readBytes())
        val original = database.messageNodeDao().getNodesOfConversation(chat.id.toString()).single()
        assertEquals(all, JsonInstant.decodeFromString<List<UIMessage>>(original.messages))
        val installed = main(target).messageNodeDao().getNodesOfConversation(chat.id.toString()).single()
        assertEquals(1, installed.selectIndex)
        return Triple(JsonInstant.decodeFromString(installed.messages), all, source to target)
    }

    @Test fun selectedAppAttachmentsRebaseAcrossRootsRecursivelyWithoutChangingUserTextOrOriginalArchive() = runBlocking {
        val (restored, original, roots) = restore(includeFiles = true); val (source, target) = roots
        fun targetUrl(path: String) = File(target.filesDir, path).toUri().toString()
        val parts = restored.first().parts
        assertEquals(targetUrl("upload/source document.pdf"), (parts[1] as UIMessagePart.Document).url)
        assertEquals(targetUrl("upload/image.png"), (parts[2] as UIMessagePart.Image).url)
        val inner = ((parts[3] as UIMessagePart.Tool).output.single() as UIMessagePart.Tool).output
        assertEquals(targetUrl("media_creation/task/audio.mp3"), (inner[0] as UIMessagePart.Audio).url)
        assertEquals(targetUrl("media_creation/task/video.mp4"), (inner[1] as UIMessagePart.Video).url)
        assertEquals(targetUrl("upload/image.png"), (restored[1].parts.single() as UIMessagePart.Image).url)
        assertEquals(original[0].parts[0], parts[0]); assertEquals(original[0].parts[2].metadata, parts[2].metadata)
        assertEquals((original[0].parts[3] as UIMessagePart.Tool).input, (parts[3] as UIMessagePart.Tool).input)
        val oldInner = ((original[0].parts[3] as UIMessagePart.Tool).output.single() as UIMessagePart.Tool).output
        assertEquals(oldInner.drop(2), inner.drop(2))
        assertEquals("EXACT BYTES upload/source document.pdf", File(target.filesDir, "upload/source document.pdf").readText())
        assertTrue(File(source.filesDir, "upload/source document.pdf").isFile)
    }

    @Test fun databaseOnlyRestoreLeavesAllAbsoluteAndExternalReferencesUnchanged() = runBlocking {
        val (restored, original, roots) = restore(includeFiles = false)
        assertEquals(original, restored)
        assertFalse(File(roots.second.filesDir, "upload/source document.pdf").exists())
    }
}
