package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.AesGcmCronPayloadCipher
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

class MessageQueueProcessDeathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val conversationId = Uuid.random()
    private val cipher: CronPayloadCipher = AesGcmCronPayloadCipher { SecretKeySpec(ByteArray(32) { 17 }, "AES") }

    private fun restored(directory: File, savedIds: Set<Uuid> = emptySet()): MessageQueue {
        return MessageQueue().apply { restoreFromStore(MessageQueueStore(directory, cipher), conversationId, savedIds) }
    }

    @Test fun pendingInputsSurviveANewQueuePausedWithIdsOrderFlagsAndAttachments() {
        val directory = temporary.newFolder()
        val original = restored(directory)
        val reply = CompletableDeferred<String?>()
        val parts = listOf(UIMessagePart.Text("secret pending question"), UIMessagePart.Image("file:///private/photo.png"))
        original.enqueue(parts, reply = reply)
        original.enqueue(listOf(UIMessagePart.Text("later")), answer = false)
        val accepted = original.state.value.messages
        original.beginEdit(accepted.first().id)
        val recovered = restored(directory)
        assertEquals(accepted.map { it.id }, recovered.state.value.messages.map { it.id })
        assertEquals(accepted.map { it.parts }, recovered.state.value.messages.map { it.parts })
        assertEquals(listOf(true, false), recovered.state.value.messages.map { it.answer })
        assertTrue(recovered.state.value.paused)
        assertTrue(recovered.state.value.messages.none { it.isEditing })
        assertNull(recovered.takeNext())
        assertSame(reply, original.state.value.messages.first().reply)
        assertTrue(recovered.state.value.messages.all { it.reply == null })
    }

    @Test fun inFlightInputIsRestoredUntilItsOriginalIdIsAlreadyInDurableChatHistory() {
        val directory = temporary.newFolder()
        val original = restored(directory)
        original.enqueue(listOf(UIMessagePart.Text("first")))
        original.enqueue(listOf(UIMessagePart.Text("second")))
        val ids = original.state.value.messages.map { it.id }
        val first = original.takeNextForSubmission {}
        assertEquals(ids.first(), first?.id)
        assertEquals(ids, restored(directory).state.value.messages.map { it.id })
        assertEquals(listOf(ids.last()), restored(directory, setOf(ids.first())).state.value.messages.map { it.id })
    }

    @Test fun removedAndEditedInputsPersistAndUnicodeTextIsEncryptedWithoutTruncation() {
        val directory = temporary.newFolder()
        val original = restored(directory)
        original.enqueue(listOf(UIMessagePart.Text("remove")))
        original.enqueue(listOf(UIMessagePart.Text("edit")))
        val ids = original.state.value.messages.map { it.id }
        original.remove(ids.first())
        val longText = "секретный вопрос 🎏\n".repeat(12_000)
        original.finishEdit(ids.last(), listOf(UIMessagePart.Text(longText)))
        val recovered = restored(directory)
        assertEquals(listOf(ids.last()), recovered.state.value.messages.map { it.id })
        assertEquals(longText, (recovered.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
        val bytes = directory.walkTopDown().filter { it.isFile }.flatMap { it.readText().asSequence() }.joinToString("")
        assertFalse(bytes.contains("секретный"))
        assertFalse(bytes.contains("private/photo"))
        assertTrue(directory.listFiles().orEmpty().isNotEmpty())
    }

    @Test fun failedDurableWriteCannotPublishAnAcceptedInputOrReplaceThePreviousQueue() {
        val directory = temporary.newFolder()
        val original = restored(directory)
        // A directory becoming unavailable is a real disk failure, not a mocked observer.
        assertTrue(directory.delete())
        directory.writeText("not a directory")
        assertThrows(java.io.IOException::class.java) {
            original.enqueue(listOf(UIMessagePart.Text("must not be reported as accepted")))
        }
        assertTrue(original.state.value.messages.isEmpty())
        assertEquals("not a directory", directory.readText())
    }

    @Test fun damagedOrSubstitutedCiphertextNeverOverwritesInputAndKeepsCleanupFailClosed() {
        val directory = temporary.newFolder()
        val original = restored(directory)
        original.enqueue(listOf(UIMessagePart.Text("private data 🎏".repeat(10_000))))
        val file = File(directory, "$conversationId.queue")
        val intact = file.readText()
        val lines = intact.lines()
        assertTrue(lines.size > 3)
        val changedCount = lines.toMutableList().apply {
            this[0] = this[0].substringBeforeLast(' ') + " " + (lines.size - 2)
            removeAt(lastIndex)
        }.joinToString("\n")
        val variants = listOf(lines.dropLast(1).joinToString("\n"), changedCount,
            lines.toMutableList().apply { val a = this[1]; this[1] = this[2]; this[2] = a }.joinToString("\n"))
        for (changed in variants) {
            file.writeText(changed)
            val store = MessageQueueStore(directory, cipher)
            val recovered = MessageQueue()
            assertThrows(java.io.IOException::class.java) {
                recovered.restoreFromStore(store, conversationId, emptySet())
            }
            assertTrue(store.hasUnreadableSnapshots)
            assertTrue(recovered.state.value.messages.isEmpty())
            assertEquals(changed, file.readText())
        }
        file.writeText(intact)
        val otherConversation = Uuid.random()
        file.copyTo(File(directory, "$otherConversation.queue"))
        val store = MessageQueueStore(directory, cipher)
        assertThrows(java.io.IOException::class.java) { store.readAll() }
        assertTrue(store.hasUnreadableSnapshots)
        assertEquals(intact, file.readText())
    }

    @Test fun unopenedConversationsKeepAttachmentReferencesAndOnlySavedInputsAreAcknowledged() {
        val directory = temporary.newFolder()
        val other = Uuid.random()
        val store = MessageQueueStore(directory, cipher)
        val first = MessageQueue().apply { restoreFromStore(store, conversationId, emptySet()) }
        val second = MessageQueue().apply { restoreFromStore(store, other, emptySet()) }
        first.enqueue(listOf(UIMessagePart.Image("file:///first.png")))
        second.enqueue(listOf(UIMessagePart.Document("file:///second.pdf", "second.pdf", "application/pdf")))
        val restarted = MessageQueueStore(directory, cipher)
        restarted.readAll()
        assertEquals(setOf("file:///first.png", "file:///second.pdf"), restarted.cachedAttachmentUrls)
        val restoredFirst = MessageQueue().apply { restoreFromStore(restarted, conversationId, emptySet()) }
        val id = restoredFirst.state.value.messages.single().id
        restoredFirst.resume()
        assertEquals(id, restoredFirst.takeNext()?.id)
        assertTrue(restarted.cachedAttachmentUrls.contains("file:///first.png"))
        restoredFirst.acknowledgeSubmission(id)
        assertEquals(setOf("file:///second.pdf"), restarted.cachedAttachmentUrls)
        val recoveredAgain = MessageQueue().apply { restoreFromStore(MessageQueueStore(directory, cipher), conversationId, emptySet()) }
        assertTrue(recoveredAgain.state.value.messages.isEmpty())
    }

    @Test fun durableMutationRunsOffTheCallerThreadPreservesOrderAndNeverBlocksPauseOnDisk() = runBlocking {
        withTimeout(10_000) {
            val directory = temporary.newFolder()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callingThread = Thread.currentThread()
            var firstWrite = true
            val slowCipher = object : CronPayloadCipher {
                override fun open(jobId: String, encrypted: String) = cipher.open(jobId, encrypted)
                override fun seal(jobId: String, plaintext: String): String {
                    assertNotSame(callingThread, Thread.currentThread())
                    if (firstWrite) {
                        firstWrite = false
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Pause blocked on a disk-write monitor" }
                    }
                    return cipher.seal(jobId, plaintext)
                }
            }
            val queue = MessageQueue()
            val store = MessageQueueStore(directory, slowCipher)
            queue.restoreFromStore(store, conversationId, emptySet())
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.withDurableMutation { queue.enqueue(listOf(UIMessagePart.Text("first"))) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.withDurableMutation { queue.enqueue(listOf(UIMessagePart.Text("second"))) }
            }
            queue.pause()
            release.countDown()
            joinAll(first, second)
            assertTrue(queue.state.value.paused)
            assertEquals(listOf("first", "second"), queue.state.value.messages.map { (it.parts.single() as UIMessagePart.Text).text })
        }
    }
}
