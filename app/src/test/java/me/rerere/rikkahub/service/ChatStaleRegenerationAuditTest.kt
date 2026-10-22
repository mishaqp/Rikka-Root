package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatStaleRegenerationAuditTest : ChatAuditServiceFixture() {
    @Test(timeout = 30_000) fun missingUserRetryTargetNeverClearsRemainingHistoryOrCallsGeneration() = runBlocking {
        val source = seed()
        val stale = source.currentMessages.first()
        service.deleteMessage(source.id, stale.id)
        val before = requireNotNull(repo.getConversationById(source.id))
        service.regenerateAtMessage(source.id, stale)
        waitGeneration(source.id)
        assertEquals("Stale Retry must leave every remaining branch intact", before.messageNodes, repo.getConversationById(source.id)!!.messageNodes)
        assertTrue("Missing target must never reach generation", generationInputs.isEmpty())
        assertTrue(service.errors.value.any { Regex("[А-Яа-яЁё]").containsMatchIn(it.error.message.orEmpty()) })
    }
    @Test(timeout = 30_000) fun retryResolvesCanonicalExistingUserByIdInsteadOfObjectEquality() = runBlocking {
        val source = seed()
        val canonical = source.currentMessages.first()
        val staleObject = canonical.copy(parts = listOf(UIMessagePart.Text("Старый UI snapshot")))
        service.regenerateAtMessage(source.id, staleObject)
        waitGeneration(source.id)
        assertEquals(listOf(canonical.toText()), generationInputs.single().filter { it.role == me.rerere.ai.core.MessageRole.USER }.map { it.toText() })
        assertEquals(canonical.id, repo.getConversationById(source.id)!!.currentMessages.first().id)
        assertEquals(canonical.parts, repo.getConversationById(source.id)!!.currentMessages.first().parts)
    }
}
