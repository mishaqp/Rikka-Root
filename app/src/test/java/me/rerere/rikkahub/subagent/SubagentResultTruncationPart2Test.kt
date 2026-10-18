package me.rerere.rikkahub.subagent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AesGcmCronPayloadCipher
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/** Actual Engine -> Registry -> encrypted file store -> model tool JSON; runner is the only external boundary. */
class SubagentResultTruncationPart2Test {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = AesGcmCronPayloadCipher { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private data class Receipt(val body: JsonObject, val reloaded: JsonObject, val directory: File, val owner: SubAgentOwner, val id: String)
    private suspend fun read(registry: SubAgentRegistry, owner: SubAgentOwner, id: String): JsonObject {
        val raw = (subagentGetTool(registry, owner).execute(buildJsonObject { put("id", id) }).single() as UIMessagePart.Text).text
        assertFalse(raw, raw.contains('\n'))
        return Json.parseToJsonElement(raw).jsonObject
    }
    private suspend fun dispatch(output: String?, noResult: Boolean = false, status: HeadlessTaskStatus = HeadlessTaskStatus.SUCCEEDED, errorCode: String? = null): Receipt {
        val directory = temporary.newFolder()
        val registry = SubAgentRegistry(directory, cipher, "original-process")
        val model = Model(modelId = "result-contract")
        val assistant = Assistant(chatModelId = model.id)
        val owner = SubAgentOwner(assistant.id.toString(), Uuid.random().toString())
        val settings = Settings(assistants = listOf(assistant), providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val runner = mockk<HeadlessTaskRunner>()
        coEvery { runner.run(any(), any()) } returns HeadlessTaskResult(status, output = output, errorCode = errorCode)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = SubAgentEngine(registry, runner, { settings }, scope, { true }, { 3 })
            val dispatched = engine.dispatch(SubAgentCaller(owner), SubAgentRequest("Read the fixture result", noResult = noResult))
            assertTrue(dispatched.toString(), dispatched is SubAgentEngine.DispatchResult.Ok)
            val id = (dispatched as SubAgentEngine.DispatchResult.Ok).run.id
            val body = read(registry, owner, id)
            val restarted = SubAgentRegistry(directory, cipher, "new-process")
            val restored = read(restarted, owner, id)
            assertNull(restarted.get(owner.copy(conversationId = Uuid.random().toString()), id))
            coVerify(exactly = 1) { runner.run(any(), any()) }
            return Receipt(body, restored, directory, owner, id)
        } finally { scope.cancel() }
    }
    private fun assertKnown(body: JsonObject, text: String, truncated: Boolean, total: Int) {
        assertEquals(JsonPrimitive("SUCCEEDED"), body["status"])
        assertEquals(body.toString().take(200), text, body["result"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(truncated), body["result_truncated"])
        assertEquals(JsonPrimitive(total), body["result_total_chars"])
        if (truncated) {
            val hint = body["message"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Clipped output needs a Russian explanation", hint.contains(Regex("[А-Яа-яЁё]")))
            assertFalse("Do not blindly replay a side-effecting subagent", hint.contains("повторите задачу", ignoreCase = true))
        }
    }

    @Test fun longResultReportsKnownTruncationAndSurvivesActualEncryptedReload() = runBlocking {
        val output = "PRIVATE-RESULT-" + "x".repeat(65537)
        val receipt = dispatch(output)
        for (body in listOf(receipt.body, receipt.reloaded)) assertKnown(body, output.take(65536), true, output.length)
        assertTrue(receipt.directory.listFiles()!!.all { !it.readText().contains("PRIVATE-RESULT-") })
    }

    @Test fun exactLimitIsKnownCompleteInsteadOfBeingGuessedTruncated() = runBlocking {
        val output = "a".repeat(65536)
        val receipt = dispatch(output)
        for (body in listOf(receipt.body, receipt.reloaded)) assertKnown(body, output, false, 65536)
    }

    @Test fun shortResultReportsItsActualCharacterLength() = runBlocking {
        val output = "Короткий USER result"
        val receipt = dispatch(output)
        for (body in listOf(receipt.body, receipt.reloaded)) assertKnown(body, output, false, output.length)
    }

    @Test fun utf16BoundaryNeverStoresOrReturnsHalfAnEmoji() = runBlocking {
        val prefix = "a".repeat(65535)
        val receipt = dispatch(prefix + "😀")
        for (body in listOf(receipt.body, receipt.reloaded)) {
            assertKnown(body, prefix, true, 65537)
            assertFalse(body.getValue("result").jsonPrimitive.content.last().isHighSurrogate())
        }
    }

    @Test fun noResultDoesNotExposeTheSuppressedBodyOrItsDerivedSizeAfterReload() = runBlocking {
        val receipt = dispatch("PRIVATE SUPPRESSED " + "x".repeat(70000), noResult = true)
        for (body in listOf(receipt.body, receipt.reloaded)) {
            assertEquals(JsonPrimitive(true), body["result_suppressed"])
            assertNull(body["result"])
            assertNull(body["result_truncated"])
            assertNull(body["result_total_chars"])
            assertFalse(body.toString().contains("PRIVATE SUPPRESSED"))
        }
        assertTrue(receipt.directory.listFiles()!!.all { !it.readText().contains("PRIVATE SUPPRESSED") })
    }

    @Test fun encryptedLegacyExactLengthRunKeepsUnknownTruncationRatherThanInventingMetadata() = runBlocking {
        val directory = temporary.newFolder()
        val owner = SubAgentOwner("legacy-assistant", "legacy-chat")
        val run = SubAgentRun("legacy-run", owner, "Legacy", "model")
        val output = "L".repeat(65536)
        val old = SubAgentRegistry(directory, cipher, "legacy-process")
        assertTrue(old.reserve(run, 1))
        old.finish(owner, run.id) { it.copy(status = SubAgentStatus.SUCCEEDED, result = output) }
        // Historical v1 fixture: real authenticated encryption, but no newly appended fields.
        val file = directory.listFiles()!!.single()
        val encoded = file.readLines().drop(1).mapIndexed { index, line -> cipher.open("subagent/${file.name}/$index", line) }.joinToString("")
        val envelope = Json.parseToJsonElement(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)).jsonObject
        val legacyRun = JsonObject(envelope.getValue("run").jsonObject - setOf("resultTruncated", "resultTotalChars", "result_truncated", "result_total_chars"))
        val legacyEnvelope = JsonObject(envelope + ("run" to legacyRun)).toString()
        val legacyEncoded = Base64.getEncoder().encodeToString(legacyEnvelope.toByteArray(Charsets.UTF_8))
        file.writeText(legacyEncoded.chunked(32768).mapIndexed { index, text -> cipher.seal("subagent/${file.name}/$index", text) }.joinToString("\n", prefix = "v1\n"))
        val body = read(SubAgentRegistry(directory, cipher, "new-process"), owner, run.id)
        assertEquals(output, body["result"]?.jsonPrimitive?.content)
        assertNull(body["result_truncated"]?.takeUnless { it == JsonNull })
        assertNull(body["result_total_chars"]?.takeUnless { it == JsonNull })
        assertFalse(file.readText().contains("Legacy"))
    }

    @Test fun failedPartialResultRetainsErrorAndIndependentTruncationHintAfterEncryptedReload() = runBlocking {
        val output = "PRIVATE-RESULT-" + "p".repeat(70000)
        val receipt = dispatch(output, status = HeadlessTaskStatus.FAILED, errorCode = "run_failed")
        for (body in listOf(receipt.body, receipt.reloaded)) {
            assertEquals(JsonPrimitive("FAILED"), body["status"])
            assertEquals(JsonPrimitive("run_failed"), body["error"])
            val errorMessage = body["message"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(errorMessage, errorMessage.contains("Не удалось выполнить задачу субагента"))
            assertEquals(output.take(65536), body["result"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive(true), body["result_truncated"])
            assertEquals(JsonPrimitive(output.length), body["result_total_chars"])
            val hint = body["truncation_message"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Missing independent Russian truncation hint", hint.contains(Regex("[А-Яа-яЁё]")))
            assertTrue(hint, hint.contains("subagent_get"))
            assertFalse(hint, hint.contains("повторите задачу", ignoreCase = true))
        }
        assertTrue(receipt.directory.listFiles()!!.all { !it.readText().contains("PRIVATE-RESULT-") })
    }
}
