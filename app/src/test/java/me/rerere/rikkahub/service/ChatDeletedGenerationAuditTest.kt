package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatDeletedGenerationAuditTest : ChatAuditServiceFixture() {
    @Test(timeout = 30_000) fun deletedPersistedChatIsNeverReinsertedByChunkCompletionOrFinalCleanup() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            generationBody = { input ->
                val partial = UIMessage.assistant("Сохранённая часть")
                emit(GenerationChunk.Messages(input + partial)); entered.complete(Unit)
                release.await(); emit(GenerationChunk.Messages(input + partial.copy(parts = listOf(UIMessagePart.Text("Завершено")))))
            }
            val source = seed()
            service.regenerateAtMessage(source.id, source.currentMessages.first())
            entered.await()
            repo.deleteConversation(requireNotNull(repo.getConversationById(source.id)))
            assertNull(repo.getConversationById(source.id))
            release.complete(Unit)
            waitGeneration(source.id)
            service.stopGeneration(source.id)
            assertNull("A completed old job is not an explicit Undo and cannot recreate a deleted chat", repo.getConversationById(source.id))
            assertTrue(db.messageNodeDao().getNodesOfConversation(source.id.toString()).isEmpty())
            assertEquals(1, generationInputs.size)
        }
    }
    @Test(timeout = 30_000) fun genuinelyNewDraftStillGetsItsFirstDurableSave() = runBlocking {
        val id = Uuid.random(); trackedIds += id
        service.initializeConversation(id)
        service.sendMessage(id, listOf(UIMessagePart.Text("Новый вопрос")), answer = false)
        waitGeneration(id)
        assertEquals("Новый вопрос", repo.getConversationById(id)!!.currentMessages.last().toText())
    }
    @Test(timeout = 30_000) fun nativeRenameCannotRecreateAKnownDeletedPersistedChat() = runBlocking {
        val source = seed()
        repo.deleteConversation(source)
        assertNull(repo.getConversationById(source.id))
        val error = runCatching {
            service.renameConversation(source.id, "Новое имя удалённого чата", normalize = false)
        }.exceptionOrNull()
        assertNotNull("Native rename must refuse to recreate a known deleted chat", error)
        assertTrue("The refusal must explain in Russian", Regex("[А-Яа-яЁё]").containsMatchIn(error!!.message.orEmpty()))
        assertNull("Renaming is not an explicit Undo", repo.getConversationById(source.id))
    }
}
