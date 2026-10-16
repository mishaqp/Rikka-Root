package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.AesGcmCronPayloadCipher
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

class SubAgentProcessDeathTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = SubAgentOwner("assistant", "chat")
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val cipher = AesGcmCronPayloadCipher { key }

    private fun registry(directory: File, process: String) = SubAgentRegistry(directory, cipher, process)

    @Test fun restartedRegistryClosesActiveRunsPreservesCompletedResultsAndNeverReplays() = runBlocking {
        val directory = temp.newFolder()
        val previous = registry(directory, "previous-process")
        // Distinct fixture times make chronological order independent of the clock and directory order.
        val startedAt = 1_700_000_000_000L
        val running = SubAgentRun("active", owner, "Задача", "model", startedAtMs = startedAt)
        assertTrue(previous.reserve(running, 3))
        assertTrue(previous.attach(owner, running.id, Job()))
        val completed = SubAgentRun("done", owner, "Готово", "model", startedAtMs = startedAt + 1L)
        assertTrue(previous.reserve(completed, 3))
        previous.finish(owner, completed.id) { it.copy(status = SubAgentStatus.SUCCEEDED,
            result = "Полезный итог 🔒 private-value", tokensOut = 50) }

        val current = registry(directory, "new-process")
        val restored = current.get(owner, running.id)
        assertNotNull("Interrupted subagent must remain observable after process death", restored)
        assertEquals(SubAgentStatus.INDETERMINATE, restored!!.status)
        assertEquals("process_interrupted", restored.errorCode)
        assertNotNull(restored.finishedAtMs)
        assertTrue(current.list(owner, activeOnly = true).isEmpty())
        assertEquals("Полезный итог 🔒 private-value", current.get(owner, completed.id)!!.result)
        assertEquals(50L, current.get(owner, completed.id)!!.tokensOut)
        assertNull(current.get(owner.copy(conversationId = "foreign"), running.id))
        val output = subagentGetTool(current, owner).execute(buildJsonObject { put("id", running.id) })
        val message = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("Прервано: приложение было закрыто"))
        assertEquals(current.list(owner), registry(directory, "third-process").list(owner))
        assertTrue("New snapshots must not contain plaintext results or labels", directory.walkTopDown().filter { it.isFile }
            .none { it.readText().contains("private-value") || it.readText().contains("Задача") })
    }

    @Test fun anotherInitializationInTheSameProcessKeepsActualActiveRuns() {
        val directory = temp.newFolder()
        val first = registry(directory, "same-process")
        assertTrue(first.reserve(SubAgentRun("active", owner, "Задача", "model"), 3))
        assertTrue(first.attach(owner, "active", Job()))
        val again = registry(directory, "same-process")
        assertEquals(SubAgentStatus.RUNNING, again.get(owner, "active")?.status)
    }

    @Test fun fullLengthUnicodeResultSurvivesEncryptionWithoutTruncation() {
        val directory = temp.newFolder()
        val first = registry(directory, "previous")
        val result = "🔒данные".repeat(10_000).take(65_536)
        first.reserve(SubAgentRun("unicode", owner, "Итог", "model"), 1)
        first.finish(owner, "unicode") { it.copy(status = SubAgentStatus.SUCCEEDED, result = result) }
        assertTrue("Every character of the full Unicode result must survive", result == registry(directory, "new").get(owner, "unicode")?.result)
    }
    @Test fun transientTerminalWriteFailureRetriesOriginalResultWithoutReplayingTask() {
        val directory = temp.newFolder()
        val delegate = cipher
        val flakyCipher = object : CronPayloadCipher {
            var failNextSeal = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failNextSeal) {
                    failNextSeal = false
                    throw java.io.IOException("injected one-shot terminal write failure")
                }
                return delegate.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = delegate.open(jobId, encrypted)
        }
        val first = SubAgentRegistry(directory, flakyCipher, "previous-process")
        val run = SubAgentRun("terminal-write-failure", owner, "Готово", "model")
        assertTrue(first.reserve(run, 1))
        assertTrue(first.attach(owner, run.id, Job()))
        // The external task has already completed. Only persistence fails; never execute it again.
        flakyCipher.failNextSeal = true
        try {
            first.finish(owner, run.id) { it.copy(status = SubAgentStatus.SUCCEEDED,
                result = "original completed result", tokensIn = 25L, tokensOut = 50L, tripCount = 2) }
        } catch (_: Exception) {
            // Same fallback as SubAgentEngine: a storage failure must not replace the completed result.
            first.finish(owner, run.id) { it.copy(status = SubAgentStatus.FAILED, errorCode = "run_failed") }
        }
        val restarted = SubAgentRegistry(directory, flakyCipher, "new-process")
        val restored = restarted.get(owner, run.id)
        assertNotNull(restored)
        assertEquals(SubAgentStatus.SUCCEEDED, restored!!.status)
        assertEquals("original completed result", restored.result)
        assertEquals(25L, restored.tokensIn)
        assertEquals(50L, restored.tokensOut)
        assertEquals(2, restored.tripCount)
    }

    @Test fun transientReadFailureRemainsRetryableAndDoesNotHideHistoryOrBypassOwnerAndCapChecks() {
        val directory = temp.newFolder()
        val previous = registry(directory, "same-process")
        assertTrue(previous.reserve(SubAgentRun("active", owner, "Работа", "model"), 2))
        assertTrue(previous.attach(owner, "active", Job()))
        assertTrue(previous.reserve(SubAgentRun("completed", owner, "Итог", "model"), 2))
        previous.finish(owner, "completed") { it.copy(status = SubAgentStatus.SUCCEEDED,
            result = "Сохранённый результат", tokensOut = 31L) }
        val originalBytes = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val flakyCipher = object : CronPayloadCipher {
            var failNextOpen = true
            override fun seal(jobId: String, plaintext: String): String = cipher.seal(jobId, plaintext)
            override fun open(jobId: String, encrypted: String): String {
                if (failNextOpen) {
                    failNextOpen = false
                    throw java.io.IOException("injected temporary KeyStore read failure")
                }
                return cipher.open(jobId, encrypted)
            }
        }
        val restored = SubAgentRegistry(directory, flakyCipher, "same-process")
        val error = assertThrows(java.io.IOException::class.java) { restored.list(owner) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Не удалось"))
        assertEquals(originalBytes, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(setOf("active", "completed"), restored.list(owner).map { it.id }.toSet())
        assertEquals(SubAgentStatus.RUNNING, restored.get(owner, "active")?.status)
        assertEquals("Сохранённый результат", restored.get(owner, "completed")?.result)
        assertEquals(31L, restored.get(owner, "completed")?.tokensOut)
        assertNull(restored.get(owner.copy(conversationId = "other"), "active"))
        assertFalse(restored.cancel(owner.copy(assistantId = "other"), "active"))
        assertFalse("An unreadable snapshot must not release its concurrency slot",
            restored.reserve(SubAgentRun("new", owner, "Новая", "model"), 1))
        assertEquals(originalBytes, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun failedDirectoryListingDoesNotBecomePermanentEmptyHistory() {
        val path = temp.newFile("subagent-history")
        val restored = registry(path, "new-process")
        val error = assertThrows(java.io.IOException::class.java) { restored.list(owner) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Не удалось"))
        assertTrue(path.delete())
        assertTrue(path.mkdir())
        val writer = registry(path, "new-process")
        assertTrue(writer.reserve(SubAgentRun("restored", owner, "Задача", "model"), 1))
        assertNotNull("A later successful listing must retry the same registry", restored.get(owner, "restored"))
    }

    @Test fun permanentTerminalWriteFailurePreservesEncryptedSnapshotAndKnownResultForLaterStorageOnlyRetry() {
        val directory = temp.newFolder()
        val failingCipher = object : CronPayloadCipher {
            var failSealing = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failSealing) throw java.io.IOException("injected permanent write failure")
                return cipher.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = cipher.open(jobId, encrypted)
        }
        val current = SubAgentRegistry(directory, failingCipher, "same-process")
        val run = SubAgentRun("persist-failed", owner, "Задача", "model")
        assertTrue(current.reserve(run, 1))
        assertTrue(current.attach(owner, run.id, Job()))
        val originalBytes = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        failingCipher.failSealing = true
        val error = assertThrows(java.io.IOException::class.java) {
            current.finish(owner, run.id) { it.copy(status = SubAgentStatus.SUCCEEDED,
                result = "Известный законченный результат", tokensOut = 27L) }
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Не удалось"))
        assertThrows(java.io.IOException::class.java) {
            current.finish(owner, run.id) { fail("Never replace a known result with fallback failure"); it }
        }
        assertEquals(originalBytes, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(SubAgentStatus.SUCCEEDED, current.get(owner, run.id)?.status)
        assertEquals("Известный законченный результат", current.get(owner, run.id)?.result)
        val visible = encodeRun(requireNotNull(current.get(owner, run.id)))
        assertEquals("result_storage_unavailable", visible["error"]?.jsonPrimitive?.content)
        assertTrue(visible["message"].toString(), visible["message"]?.jsonPrimitive?.content.orEmpty().contains("сохранить"))
        assertEquals("result_storage_unavailable", current.list(owner).single().errorCode)
        assertEquals("result_storage_unavailable", current.snapshot().single().errorCode)
        failingCipher.failSealing = false
        current.finish(owner, run.id) { fail("Storage retry must not rerun terminal computation"); it }
        val restarted = registry(directory, "new-process").get(owner, run.id)
        assertEquals(SubAgentStatus.SUCCEEDED, restarted?.status)
        assertEquals("Известный законченный результат", restarted?.result)
        assertEquals(27L, restarted?.tokensOut)
        assertNull("A successful retry must clear only the temporary storage warning", restarted?.errorCode)
        assertNull(current.get(owner, run.id)?.errorCode)
        assertTrue(directory.listFiles()!!.none { it.readText().contains("Известный") })
    }

    @Test fun corruptSnapshotFailsClosedWithoutHidingOrOverwritingHealthyHistory() {
        val directory = temp.newFolder()
        val writer = registry(directory, "same-process")
        assertTrue(writer.reserve(SubAgentRun("healthy", owner, "Задача", "model"), 1))
        val corrupt = File(directory, "corrupt.run").apply { writeText("v1\ninvalid-encrypted-payload") }
        val originalBytes = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val restored = registry(directory, "same-process")
        assertThrows(java.io.IOException::class.java) { restored.get(owner, "healthy") }
        assertThrows(java.io.IOException::class.java) {
            restored.reserve(SubAgentRun("unexpected", owner, "Новая", "model"), 1)
        }
        assertEquals(originalBytes, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertTrue(corrupt.delete())
        assertEquals(SubAgentStatus.PENDING, restored.get(owner, "healthy")?.status)
        assertFalse(restored.reserve(SubAgentRun("new", owner, "Новая", "model"), 1))
    }

    @Test fun cancellationWriteFailureRetriesTerminalReceiptWithoutStartingOrReplayingWork() {
        val directory = temp.newFolder()
        val flakyCipher = object : CronPayloadCipher {
            var failNextSeal = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failNextSeal) {
                    failNextSeal = false
                    throw java.io.IOException("injected cancellation persistence failure")
                }
                return cipher.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = cipher.open(jobId, encrypted)
        }
        val current = SubAgentRegistry(directory, flakyCipher, "previous")
        assertTrue(current.reserve(SubAgentRun("cancelled", owner, "Задача", "model"), 1))
        flakyCipher.failNextSeal = true
        assertThrows(java.io.IOException::class.java) { current.cancel(owner, "cancelled") }
        assertTrue("Retrying cancellation must retry its storage receipt", current.cancel(owner, "cancelled"))
        val restored = registry(directory, "new").get(owner, "cancelled")
        assertEquals(SubAgentStatus.CANCELLED, restored?.status)
        assertFalse(current.cancel(owner, "cancelled"))
    }

    @Test fun laterStatusReadRetriesOnlyPersistenceOfKnownTerminalResultAfterStorageRecovers() =
        assertReadRetriesOnlyPersistence { get(owner, "read-retry") }

    @Test fun laterListRetriesOnlyPersistenceOfKnownTerminalResultAfterStorageRecovers() =
        assertReadRetriesOnlyPersistence { list(owner) }

    @Test fun laterSnapshotRetriesOnlyPersistenceOfKnownTerminalResultAfterStorageRecovers() =
        assertReadRetriesOnlyPersistence { snapshot() }

    private fun assertReadRetriesOnlyPersistence(read: SubAgentRegistry.() -> Any?) {
        val directory = temp.newFolder()
        val failingCipher = object : CronPayloadCipher {
            var failSealing = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failSealing) throw java.io.IOException("injected temporary write outage")
                return cipher.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = cipher.open(jobId, encrypted)
        }
        val current = SubAgentRegistry(directory, failingCipher, "same-process")
        assertTrue(current.reserve(SubAgentRun("read-retry", owner, "Задача", "model"), 1))
        var terminalComputations = 0
        failingCipher.failSealing = true
        assertThrows(java.io.IOException::class.java) {
            current.finish(owner, "read-retry") {
                terminalComputations++
                it.copy(status = SubAgentStatus.SUCCEEDED, result = "Уже выполнено", tokensOut = 12L)
            }
        }
        failingCipher.failSealing = false
        current.read()
        val restarted = registry(directory, "new-process").get(owner, "read-retry")
        assertEquals(SubAgentStatus.SUCCEEDED, restarted?.status)
        assertEquals("Уже выполнено", restarted?.result)
        assertEquals(12L, restarted?.tokensOut)
        assertNull(restarted?.errorCode)
        assertEquals("Reading status must never repeat external work or terminal computation", 1, terminalComputations)
    }

    @Test fun historyEvictionPreservesKnownTerminalResultWhoseWriteIsStillPending() {
        val directory = temp.newFolder()
        val failingCipher = object : CronPayloadCipher {
            var failSealing = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failSealing) throw java.io.IOException("injected original result write failure")
                return cipher.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = cipher.open(jobId, encrypted)
        }
        val current = SubAgentRegistry(directory, failingCipher, "same-process")
        assertTrue(current.reserve(SubAgentRun("oldest-pending", owner, "Задача", "model", startedAtMs = 1L), 1))
        failingCipher.failSealing = true
        assertThrows(java.io.IOException::class.java) {
            current.finish(owner, "oldest-pending") { it.copy(status = SubAgentStatus.SUCCEEDED,
                result = "Не потерять вычисленный итог", tokensOut = 19L) }
        }
        failingCipher.failSealing = false
        repeat(99) { index ->
            val id = "history-$index"
            assertTrue(current.reserve(SubAgentRun(id, owner, "Итог", "model", startedAtMs = index + 2L), 1))
            current.finish(owner, id) { it.copy(status = SubAgentStatus.SUCCEEDED) }
        }
        assertTrue(current.reserve(SubAgentRun("new-run", owner, "Новая", "model"), 1))
        val pending = current.get(owner, "oldest-pending")
        assertNotNull("History pruning must not discard a known result that is not durable yet", pending)
        assertEquals("Не потерять вычисленный итог", pending?.result)
        assertEquals(19L, pending?.tokensOut)
        assertEquals(100, current.list(owner).size)
        assertEquals(100, directory.listFiles()!!.count { it.name.endsWith(".run") })
    }

    @Test fun fullHistoryOfUnpersistedResultsRejectsReservationWithoutCreatingAnOrphanSnapshot() {
        val directory = temp.newFolder()
        val failingCipher = object : CronPayloadCipher {
            var failSealing = false
            override fun seal(jobId: String, plaintext: String): String {
                if (failSealing) throw java.io.IOException("injected original result write failure")
                return cipher.seal(jobId, plaintext)
            }
            override fun open(jobId: String, encrypted: String): String = cipher.open(jobId, encrypted)
        }
        val current = SubAgentRegistry(directory, failingCipher, "same-process")
        repeat(100) { index ->
            val id = "pending-$index"
            failingCipher.failSealing = false
            assertTrue(current.reserve(SubAgentRun(id, owner, "Задача", "model"), 1))
            failingCipher.failSealing = true
            assertThrows(java.io.IOException::class.java) {
                current.finish(owner, id) { it.copy(status = SubAgentStatus.SUCCEEDED, result = "Итог $index") }
            }
        }
        val originalBytes = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        failingCipher.failSealing = false
        assertFalse("Reservation must not evict any of the known results still awaiting durable storage",
            current.reserve(SubAgentRun("unexpected-reservation", owner, "Новая", "model"), 1))
        assertEquals(originalBytes, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun malformedEncryptedSnapshotDoesNotLeakDecryptedResultIntoErrorDetails() {
        val directory = temp.newFolder()
        val privateResult = "private-subagent-result-marker"
        val malformedJson = "{\"run\":{\"result\":\"$privateResult\"}}"
        val encoded = java.util.Base64.getEncoder().encodeToString(malformedJson.toByteArray(Charsets.UTF_8))
        File(directory, "corrupt.run").writeText("v1\n" + cipher.seal("subagent/corrupt.run/0", encoded))
        val failure = assertThrows(java.io.IOException::class.java) {
            registry(directory, "new-process").list(owner)
        }
        assertNull("Failure details must not attach a deserialization error containing decrypted task content", failure.cause)
        assertFalse(failure.stackTraceToString().contains(privateResult))
    }
}
