package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatActiveMessageDeleteAuditTest : ChatAuditServiceFixture() {
    @Test(timeout = 30_000) fun publicDeleteRejectsActiveGenerationBeforeAnyHistoryMutation() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            generationBody = { input ->
                val partial = UIMessage.assistant("Часть ответа")
                emit(GenerationChunk.Messages(input + partial)); entered.complete(Unit)
                release.await(); emit(GenerationChunk.Messages(input + partial))
            }
            val source = seed()
            service.regenerateAtMessage(source.id, source.currentMessages.first())
            entered.await()
            val before = service.getConversationFlow(source.id).value
            try {
                val error = runCatching { service.deleteMessage(source.id, source.currentMessages.first().id) }.exceptionOrNull()
                assertNotNull("The service boundary used by Web must reject deletion while streaming", error)
                assertTrue("The refusal must explain in Russian", Regex("[А-Яа-яЁё]").containsMatchIn(error!!.message.orEmpty()))
                assertEquals(before.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
                assertEquals(before.messageNodes, repo.getConversationById(source.id)!!.messageNodes)
            } finally { release.complete(Unit); waitGeneration(source.id) }
        }
    }
    @Test(timeout = 30_000) fun retryStartingDuringDeletionCommitCannotReinsertTheDeletedQuestion() = runBlocking {
        withTimeout(10_000) {
            val source = seed()
            val question = source.currentMessages.first()
            val gate = Gate().also { dao.existsGate = it }
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { service.deleteMessage(source.id, question.id) }
            gate.entered.await()
            // Deletion has checked for active generation but has not published its change.
            service.regenerateAtMessage(source.id, question)
            gate.release.complete(Unit)
            deletion.await()
            waitGeneration(source.id)
            assertFalse("A newer Retry must resolve its target only after deletion commits", repo.getConversationById(source.id)!!.messageNodes.any { node -> node.messages.any { it.id == question.id } })
            assertTrue("An already deleted retry target must not reach generation", generationInputs.isEmpty())
            assertTrue(service.errors.value.any { Regex("[А-Яа-яЁё]").containsMatchIn(it.error.message.orEmpty()) })
        }
    }
    @Test(timeout = 30_000) fun stoppingRetryRegisteredDuringDeletionNeverStartsItOrRestoresOldHistory() = runBlocking {
        withTimeout(10_000) {
            val source = seed()
            val question = source.currentMessages.first()
            val gate = Gate().also { dao.existsGate = it }
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { service.deleteMessage(source.id, question.id) }
            gate.entered.await()
            service.regenerateAtMessage(source.id, question)
            assertNotNull("A pending Retry must remain visible to UI and Stop", service.getGenerationJobStateFlow(source.id).first())
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { service.stopGeneration(source.id) }
            gate.release.complete(Unit)
            deletion.await(); stopping.await(); waitGeneration(source.id)
            assertTrue("Stop before the deferred body starts must never call generation", generationInputs.isEmpty())
            assertFalse("Cancelling the deferred Retry cannot publish its old snapshot", repo.getConversationById(source.id)!!.messageNodes.any { node -> node.messages.any { it.id == question.id } })
            assertNull(service.getGenerationJobStateFlow(source.id).first())
        }
    }
    @Test(timeout = 30_000) fun deletionAfterStoppedGenerationRemainsAvailable() = runBlocking {
        val source = seed()
        service.stopGeneration(source.id)
        service.deleteMessage(source.id, source.currentMessages.first().id)
        assertFalse(repo.getConversationById(source.id)!!.messageNodes.any { node -> node.messages.any { it.id == source.currentMessages.first().id } })
    }
}
