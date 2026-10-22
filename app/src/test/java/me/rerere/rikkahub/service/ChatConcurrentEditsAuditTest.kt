package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatConcurrentEditsAuditTest : ChatAuditServiceFixture() {
    @Test(timeout = 30_000) fun parallelAcceptedEditsPreserveBothNewBranchVersionsInRoom() = runBlocking {
        withTimeout(10_000) {
            val source = seed()
            val target = source.currentMessages.first()
            val gate = Gate().also { dao.existsGate = it }
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                service.editMessage(source.id, target.id, listOf(UIMessagePart.Text("Правка A")))
            }
            gate.entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                service.editMessage(source.id, target.id, listOf(UIMessagePart.Text("Правка B")))
            }
            assertFalse("Second edit waits for the first persistence boundary", second.isCompleted)
            gate.release.complete(Unit)
            first.await(); second.await()
            val live = service.getConversationFlow(source.id).value
            val saved = requireNotNull(repo.getConversationById(source.id))
            for (conversation in listOf(live, saved)) {
                val node = conversation.messageNodes.first { node -> node.messages.any { it.id == target.id } }
                assertEquals("Both accepted edits must survive as distinct versions", listOf(target.toText(), "Правка A", "Правка B"), node.messages.map { it.toText() })
                assertEquals("Правка B", node.currentMessage.toText())
            }
        }
    }
}
