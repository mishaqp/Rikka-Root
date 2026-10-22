package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChatboxPartialRestorePart3Test : BackupVmPart3Fixture() {
    private fun archive(secondValid: Boolean): File {
        val manifest = """{"format":"chatbox-backup","formatVersion":2,"sessions":[{"id":"one","path":"sessions/one.json"},{"id":"two","path":"sessions/two.json"}],"resources":[{"id":"image","path":"resources/one.png","mimeType":"image/png","kind":"image","originalStorageKeys":["picture:one"]}]}"""
        val first = """{"id":"one","name":"Imported first","messages":[{"id":"system","role":"system","contentParts":[{"type":"text","text":"USER English system instructions"}]},{"id":"user","role":"user","contentParts":[{"type":"text","text":"USER English first"},{"type":"image","storageKey":"picture:one"}]}]}"""
        val second = if (secondValid) """{"id":"two","messages":[{"id":"u2","role":"user","contentParts":[{"type":"text","text":"USER English second"}]}]}""" else "{" // Real late parser failure, after the first image/conversation callbacks.
        return zip("chatbox-${System.nanoTime()}.zip", linkedMapOf("manifest.json" to manifest.encodeToByteArray(), "sessions/one.json" to first.encodeToByteArray(), "sessions/two.json" to second.encodeToByteArray(), "resources/one.png" to byteArrayOf(1,2,3,4)))
    }
    private suspend fun <T> fixture(block: suspend (ConversationRepository, FilesManager, AppDatabase, Conversation) -> T): T {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, context.getDatabasePath("chatbox-test").absolutePath).allowMainThreadQueries().build()
        val scope = AppScope().apply { cancel() }
        try {
            val files = FilesManager(context, FilesRepository(database.managedFileDao()), scope, store)
            val repo = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database, files, mockk(relaxed = true), ownershipGuard = AssistantOwnershipGuard { id -> store.settingsFlow.value.assistants.any { it.id.toString() == id } })
            val old = Conversation(assistantId = owner.id, title = "PREEXISTING CHAT", messageNodes = listOf(MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("PREEXISTING TEXT"))))))
            repo.insertConversation(old)
            return block(repo, files, database, repo.getConversationById(old.id)!!)
        } finally { database.close(); scope.cancel() }
    }
    private fun id(session: String) = Uuid.parse(UUID.nameUUIDFromBytes("chatbox:session:$session".encodeToByteArray()).toString())

    @Test fun lateFailureEitherRollsBackNewDataOrReportsTheSavedPartialImportTruthfully() = runBlocking {
        fixture { repo, files, database, old ->
            val oldUpload = files.saveUploadFromBytes("ORIGINAL BYTES".encodeToByteArray(), "existing.txt", "text/plain")
            val oldPath = files.getFile(oldUpload); val before = repo.getConversationById(old.id)
            val error = runCatching { vm(repo, files).restoreFromChatBox(archive(secondValid = false)) }.exceptionOrNull()
            assertNotNull("Malformed second session must not claim complete success", error)
            assertEquals(before, repo.getConversationById(old.id)); assertEquals("ORIGINAL BYTES", oldPath.readText())
            val imported = repo.getConversationById(id("one"))
            if (imported != null) {
                val message = error!!.message.orEmpty()
                assertTrue("Saved first chat requires an explicit Russian partial warning: $message", message.contains(Regex("(?i)частич|часть")))
                assertTrue("Warning must include the saved chat count: $message", message.contains(Regex("(?i)(чат\\p{L}*[: ]+1|1\\s+чат)")))
                assertTrue("Saved custom prompt must remain enabled", store.update { it }.assistants.single { it.id == owner.id }.allowConversationSystemPrompt)
                val uploads = database.managedFileDao().listByFolder("upload").first()
                assertTrue(uploads.size >= 2); assertTrue(uploads.all { files.getFile(it).isFile })
            } else {
                assertEquals(listOf(oldUpload.relativePath), database.managedFileDao().listByFolder("upload").first().map { it.relativePath })
                assertEquals(listOf(oldPath.name), File(context.filesDir, "upload").listFiles().orEmpty().map { it.name })
            }
        }
    }

    @Test fun completeStreamingImportPreservesOldDataAndManagedImageReferences() = runBlocking {
        fixture { repo, files, database, old ->
            val result = vm(repo, files).restoreFromChatBox(archive(secondValid = true))
            assertEquals(2, result.importedConversations); assertEquals(1, result.importedImageParts)
            assertEquals(old, repo.getConversationById(old.id))
            assertNotNull(repo.getConversationById(id("one"))); assertNotNull(repo.getConversationById(id("two")))
            val rows = database.managedFileDao().listByFolder("upload").first()
            assertEquals(1, rows.size); assertArrayEquals(byteArrayOf(1,2,3,4), files.getFile(rows.single()).readBytes())
        }
    }

    @Test fun reimportSkipsExistingConversationsAndDoesNotDuplicateImagesOrOverwriteEdits() = runBlocking {
        fixture { repo, files, database, _ ->
            val model = vm(repo, files); model.restoreFromChatBox(archive(secondValid = true))
            val edited = repo.getConversationById(id("one"))!!.copy(title = "USER EDITED TITLE")
            repo.updateConversation(edited)
            val result = model.restoreFromChatBox(archive(secondValid = true))
            assertEquals(0, result.importedConversations); assertEquals(2, result.skippedExistingConversations)
            assertEquals(edited, repo.getConversationById(id("one")))
            assertEquals(1, database.managedFileDao().listByFolder("upload").first().size)
        }
    }
}
