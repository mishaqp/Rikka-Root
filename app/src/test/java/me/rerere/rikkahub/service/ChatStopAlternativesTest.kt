package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.Uuid

/** Real generation cancellation, reply selection and Room persistence; no shell commands run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatStopAlternativesTest : ChatAuditServiceFixture() {
    @Test(timeout = 30_000)
    fun stopClosesStreamingToolAfterSelectingAnOlderReplyAndPersistsItsCancellation() = runBlocking {
        val completed = completedReply()
        val source = seed(listOf(UIMessage.user("Вопрос").toMessageNode(), completed.toMessageNode()))
        val pending = pendingReply("streamed-call")
        beginPendingRegeneration(source, pending)
        val node = service.getConversationFlow(source.id).value.messageNodes.last()
        assertEquals(listOf(completed.id, pending.id), node.messages.map { it.id })
        service.selectMessageNode(source.id, node.id, 0)
        assertFalse(service.getConversationFlow(source.id).value.messageNodes.last().messages[1].getTools().single().isExecuted)

        service.stopGeneration(source.id)

        val stopped = service.getConversationFlow(source.id).value.messageNodes.last()
        assertEquals(0, stopped.selectIndex)
        assertEquals(completed, stopped.messages[0])
        assertCancelled(pending, stopped.messages[1])
        assertEquals(stopped, repo.getConversationById(source.id)!!.messageNodes.last())
        service.selectMessageNode(source.id, node.id, 1)
        assertCancelled(pending, service.getConversationFlow(source.id).value.currentMessages.last())
        assertEquals(1, generationInputs.size)
    }

    @Test(timeout = 30_000)
    fun stopClosesSelectedAndSavedPendingRepliesWithoutChangingCompletedResults() = runBlocking {
        val hidden = pendingReply("saved-call")
        val completed = completedReply()
        val source = seed(listOf(UIMessage.user("Вопрос").toMessageNode(),
            MessageNode(messages = listOf(hidden, completed), selectIndex = 1)))
        val pending = pendingReply("current-call")
        beginPendingRegeneration(source, pending)
        val before = service.getConversationFlow(source.id).value.messageNodes.last()
        assertEquals(2, before.selectIndex)
        assertEquals(listOf(hidden.id, completed.id, pending.id), before.messages.map { it.id })

        service.stopGeneration(source.id)

        val stopped = service.getConversationFlow(source.id).value.messageNodes.last()
        assertEquals(before.selectIndex, stopped.selectIndex)
        assertEquals(before.id, stopped.id)
        assertCancelled(hidden, stopped.messages[0])
        assertEquals(completed, stopped.messages[1])
        assertCancelled(pending, stopped.messages[2])
        assertEquals(stopped, repo.getConversationById(source.id)!!.messageNodes.last())
        assertEquals(1, generationInputs.size)
    }

    @Test(timeout = 30_000)
    fun stopWithoutAnActiveGenerationLeavesSavedAlternativesUntouched() = runBlocking {
        val source = seed(listOf(UIMessage.user("Вопрос").toMessageNode(),
            MessageNode(messages = listOf(pendingReply("saved-call"), completedReply()), selectIndex = 1)))

        service.stopGeneration(source.id)

        assertEquals(source.messageNodes, service.getConversationFlow(source.id).value.messageNodes)
        assertEquals(source.messageNodes, repo.getConversationById(source.id)!!.messageNodes)
        assertTrue(generationInputs.isEmpty())
    }

    @Test(timeout = 30_000)
    fun sendingTheNextMessageAlsoClosesHiddenPendingRepliesWithoutRunningThem() = runBlocking {
        val pending = pendingReply("saved-call")
        val completed = completedReply()
        val source = seed(listOf(UIMessage.user("Вопрос").toMessageNode(),
            MessageNode(messages = listOf(pending, completed), selectIndex = 1)))

        service.sendMessage(source.id, listOf(UIMessagePart.Text("Следующий вопрос")), answer = false)
        waitGeneration(source.id)

        val saved = repo.getConversationById(source.id)!!
        val node = saved.messageNodes[1]
        assertEquals(1, node.selectIndex)
        assertCancelled(pending, node.messages[0])
        assertEquals(completed, node.messages[1])
        assertEquals("Следующий вопрос", saved.currentMessages.last().toText())
        assertTrue(generationInputs.isEmpty())
    }

    @Test(timeout = 30_000)
    fun cleanupPreservesASelectionCommittedWhileItWaitsForPersistence() = runBlocking {
        withTimeout(10_000) {
            val completed = completedReply()
            val pending = pendingReply("saved-call")
            val source = seed(listOf(UIMessage.user("Вопрос").toMessageNode(),
                MessageNode(messages = listOf(completed, pending), selectIndex = 0)))
            val node = source.messageNodes.last()
            val gate = Gate().also { dao.existsGate = it }
            try {
                val selecting = async(start = CoroutineStart.UNDISPATCHED) {
                    service.selectMessageNode(source.id, node.id, 1)
                }
                gate.entered.await()
                val finishing = async(start = CoroutineStart.UNDISPATCHED) {
                    finishPendingAtPersistenceBoundary(source.id)
                }
                assertFalse("Cleanup must wait for the selection's persistence lock", finishing.isCompleted)
                gate.release.complete(Unit)
                selecting.await()
                finishing.await()

                for (conversation in listOf(service.getConversationFlow(source.id).value,
                    requireNotNull(repo.getConversationById(source.id)))) {
                    val saved = conversation.messageNodes.last()
                    assertEquals("Cleanup must retain the concurrently committed selection", 1, saved.selectIndex)
                    assertEquals(completed, saved.messages[0])
                    assertCancelled(pending, saved.messages[1])
                }
            } finally { gate.release.complete(Unit) }
        }
    }

    // Isolate post-join persistence deterministically; the public Stop/send wiring is tested above.
    private suspend fun finishPendingAtPersistenceBoundary(id: Uuid) = suspendCoroutine<Unit> { continuation ->
        val method = ChatService::class.java.getDeclaredMethod("finishInterruptedPendingTools",
            Uuid::class.java, Continuation::class.java).apply { isAccessible = true }
        try {
            if (method.invoke(service, id, continuation) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        }
    }

    private suspend fun beginPendingRegeneration(source: Conversation, reply: UIMessage) {
        val entered = CompletableDeferred<Unit>()
        generationBody = { input ->
            emit(GenerationChunk.Messages(input + reply))
            entered.complete(Unit)
            awaitCancellation()
        }
        service.regenerateAtMessage(source.id, source.currentMessages.last())
        withTimeout(10_000) { entered.await() }
    }

    private fun pendingReply(id: String) = UIMessage.assistant("Ожидание инструмента").copy(parts = listOf(
        UIMessagePart.Text("Ожидание инструмента"),
        UIMessagePart.Tool(id, "root_exec", """{"command":"printf '[%s]\\n' 'a b' 2>/dev/null"}""",
            approvalState = ToolApprovalState.Pending),
    ))

    private fun completedReply() = UIMessage.assistant("Сохранённый результат").copy(parts = listOf(
        UIMessagePart.Text("Сохранённый результат"),
        UIMessagePart.Tool("completed-call", "root_exec", "{}",
            output = listOf(UIMessagePart.Text("""{"stdout":"OK","stderr":"warning","exit_code":0}""")),
            approvalState = ToolApprovalState.Approved),
    ))

    private fun assertCancelled(before: UIMessage, after: UIMessage) {
        assertEquals(before.id, after.id)
        val original = before.getTools().single()
        val tool = after.getTools().single()
        assertTrue("Stopped saved tool must not remain pending", tool.isExecuted)
        assertEquals(original.toolCallId, tool.toolCallId)
        assertEquals(original.toolName, tool.toolName)
        assertEquals(original.input, tool.input)
        assertEquals(original.approvalState, tool.approvalState)
        val result = Json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("cancelled", result.jsonObject.getValue("status").jsonPrimitive.content)
    }
}
