package me.rerere.rikkahub.costguards

import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TokenBudgetTrackerTest {
    private fun message(input: Int, output: Int, total: Int = 0) =
        UIMessage.assistant("").copy(usage = TokenUsage(input, output, totalTokens = total))
    @Test fun `selected branch aggregates Long safely and skips invalid selection`() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(message(1, 2), message(Int.MAX_VALUE, Int.MAX_VALUE)), selectIndex = 1),
            MessageNode(messages = listOf(message(4, 5))),
            MessageNode(messages = listOf(message(100, 100)), selectIndex = 2)))
        val totals = TokenBudgetTracker.aggregate(conversation)
        assertEquals(4294967303L, totals.totalTokens)
        assertEquals(4294967294L, totals.perMessageMax)
        assertEquals(2, totals.messageCount)
        assertEquals(Long.MAX_VALUE, TokenBudgetTracker.saturatedAdd(Long.MAX_VALUE - 1, 10))
    }
    @Test fun `cap disabled is inert even when fields configured`() {
        val ledger = TokenBudgetLedger(false, 1, 2, listOf(message(10, 10)))
        ledger.ensureCanContinue()
        val request = ledger.reserve(Long.MAX_VALUE, 9000)
        assertEquals(9000, request.maxTokens)
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET, ledger.snapshot().status)
    }
    @Test fun `soft warns and hard stops at equality including invalid zero caps`() {
        val ledger = TokenBudgetLedger(true, 30, 100, listOf(message(15, 15)))
        assertEquals(TokenBudgetTracker.BudgetStatus.WARN, ledger.snapshot().status)
        val request = ledger.reserve(20, 200)
        assertEquals(50, request.maxTokens)
        request.observe(TokenUsage(20, 50))
        assertThrows(TokenBudgetExceededException::class.java) { ledger.ensureCanContinue() }
        assertThrows(TokenBudgetExceededException::class.java) { ledger.reserve(0, 1) }
        assertThrows(TokenBudgetExceededException::class.java) { TokenBudgetLedger(true, hardCap = 0).reserve(0, 1) }
    }
    @Test fun `stream snapshots never refund usage and close never resets parent`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val request = ledger.reserve(10, 40)
        request.observe(TokenUsage(10, 20))
        request.observe(TokenUsage(1, 1))
        request.close()
        request.close()
        assertEquals(30L, ledger.snapshot().spentTokens)
        assertEquals(0L, ledger.snapshot().reservedTokens)
        val child = ledger.reserve(10, 100)
        assertEquals(60, child.maxTokens)
        child.observe(TokenUsage(10, 65))
        assertEquals(105L, ledger.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.ensureCanContinue() }
    }
    @Test fun `concurrent children atomically reserve parent capacity`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit<TokenBudgetLedger.Reservation?> {
                gate.await()
                try { ledger.reserve(10, 30) } catch (_: TokenBudgetExceededException) { null }
            } }
            gate.countDown()
            val requests = futures.mapNotNull { it.get() }
            assertTrue(requests.size in 2..3)
            assertEquals(100L, ledger.snapshot().reservedTokens)
            requests.forEach { it.close() }
            assertEquals(0L, ledger.snapshot().reservedTokens)
        } finally { pool.shutdownNow() }
    }
    @Test fun `launched request without late metrics charges reservation and cannot retry`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val request = ledger.reserve(20, null)
        request.markSubmitted()
        request.close()
        assertEquals(100L, ledger.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.reserve(1, 1) }
    }
    @Test fun `failed stream with only partial usage retains unknown output reservation`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val request = ledger.reserve(20, null)
        request.markSubmitted()
        request.observe(TokenUsage(promptTokens = 20))
        request.close()
        assertEquals(100L, ledger.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.reserve(1, 1) }
    }
    @Test fun `successful provider releases unused reservation without resetting observed totals`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val request = ledger.reserve(20, null)
        request.markSubmitted()
        request.observe(TokenUsage(20, 15))
        request.markCompleted()
        request.close()
        assertEquals(35L, ledger.snapshot().spentTokens)
        assertEquals(65L, ledger.snapshot().remainingTokens)
    }
    @Test fun `split streaming input and output snapshots preserve totals`() {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val request = ledger.reserve(20, 50)
        request.observe(TokenUsage(promptTokens = 20))
        request.observe(TokenUsage(completionTokens = 40))
        request.observe(TokenUsage(totalTokens = 90))
        assertEquals(90L, ledger.snapshot().spentTokens)
        request.close()
        assertEquals(90L, ledger.snapshot().spentTokens)
    }
    @Test fun `input estimate overflow blocks request before it trips budget`() {
        val ledger = TokenBudgetLedger(true, hardCap = Int.MAX_VALUE)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.reserve(Long.MAX_VALUE, Int.MAX_VALUE) }
        assertEquals(0L, ledger.snapshot().reservedTokens)
    }
    @Test fun `custom bodies cannot override provider output limits or whole configs`() {
        val bodies = listOf(CustomBody("max_tokens", JsonPrimitive(999)),
            CustomBody("max_completion_tokens", JsonPrimitive(999)),
            CustomBody("max_output_tokens", JsonPrimitive(999)),
            CustomBody("generationConfig", buildJsonObject { put("maxOutputTokens", 999); put("temperature", 0.2) }),
            CustomBody("generation_config", JsonPrimitive("replace")))
        assertEquals(bodies, boundedCustomBodies(bodies, null))
        val bounded = boundedCustomBodies(bodies, 25).associate { it.key to it.value }
        assertEquals(25, bounded.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(25, bounded.getValue("max_completion_tokens").jsonPrimitive.int)
        assertEquals(25, bounded.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals(25, bounded.getValue("generationConfig").jsonObject.getValue("maxOutputTokens").jsonPrimitive.int)
        assertTrue(bounded.getValue("generation_config") is kotlinx.serialization.json.JsonObject)
    }
}


class TokenBudgetStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun assistant(cap: Int = 500) = Assistant(tokenBudgetHardCap = cap,
        localTools = listOf(LocalToolOption.CostGuards))
    @Test fun `approval resume returns same ledger and cap changes never reset spent`() {
        val store = TokenBudgetStore(folder.newFolder())
        val caller = assistant()
        val id = Uuid.random()
        val ledger = store.getLedger(caller, id, emptyList())
        val request = ledger.reserve(10, 30)
        request.markSubmitted()
        request.observe(TokenUsage(10, 20))
        request.markCompleted()
        request.close()
        val resumed = store.getLedger(caller.copy(tokenBudgetHardCap = 20), id, emptyList())
        assertSame(ledger, resumed)
        assertEquals(30L, resumed.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { resumed.ensureCanContinue() }
    }
    @Test fun `restart restores child and retry spend even if parent history undercounts`() {
        val directory = folder.newFolder()
        val caller = assistant()
        val id = Uuid.random()
        val ledger = TokenBudgetStore(directory).getLedger(caller, id, emptyList())
        val child = ledger.reserve(10, 100)
        child.markSubmitted()
        child.observe(TokenUsage(10, 80))
        child.markCompleted()
        child.close()
        val tinyHistory = listOf(UIMessage.assistant("").copy(usage = TokenUsage(1, 1)))
        val restored = TokenBudgetStore(directory).getLedger(caller, id, tinyHistory)
        assertEquals(90L, restored.snapshot().spentTokens)
        val otherOwner = TokenBudgetStore(directory).getLedger(assistant(), id, emptyList())
        assertEquals(0L, otherOwner.snapshot().spentTokens)
        assertTrue(directory.listFiles()!!.all { file -> file.readText().matches(Regex("[0-9\\n]+")) })
    }
    @Test fun `interrupted submitted reservations recover conservatively before next bill`() {
        val directory = folder.newFolder()
        val caller = assistant(100)
        val id = Uuid.random()
        val request = TokenBudgetStore(directory).getLedger(caller, id, emptyList()).reserve(10, null)
        request.markSubmitted()
        request.observe(TokenUsage(promptTokens = 10))
        val restored = TokenBudgetStore(directory).getLedger(caller, id, emptyList())
        assertEquals(100L, restored.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { restored.reserve(1, 1) }
    }
    @Test fun `failed durable preflight aborts before provider could bill and future use stays blocked`() {
        val caller = assistant()
        val store = TokenBudgetStore(folder.newFolder()) { _, _ -> throw IOException("disk full") }
        val ledger = store.getLedger(caller, Uuid.random(), emptyList())
        var billed = false
        assertThrows(IOException::class.java) {
            val request = ledger.reserve(10, 20)
            try { request.markSubmitted(); billed = true } finally { request.close() }
        }
        assertFalse(billed)
        assertThrows(IOException::class.java) { ledger.ensureCanContinue() }
    }
    @Test fun `lowering hard cap cannot launch a reservation admitted under older cap`() {
        val directory = folder.newFolder()
        val caller = assistant(100)
        val id = Uuid.random()
        val store = TokenBudgetStore(directory)
        val ledger = store.getLedger(caller, id, emptyList())
        val request = ledger.reserve(10, 80)
        assertSame(ledger, store.getLedger(caller.copy(tokenBudgetHardCap = 20), id, emptyList()))
        assertThrows(TokenBudgetExceededException::class.java) { request.markSubmitted() }
        request.close()
    }
    @Test fun `interrupted unbounded request cannot evade a hard cap enabled later`() {
        val directory = folder.newFolder()
        val caller = assistant().copy(tokenBudgetHardCap = null, tokenBudgetSoftCap = 20)
        val id = Uuid.random()
        val ledger = TokenBudgetStore(directory).getLedger(caller, id, emptyList())
        val request = ledger.reserve(10, null)
        request.markSubmitted()
        val restored = TokenBudgetStore(directory).getLedger(caller.copy(tokenBudgetHardCap = 100), id, emptyList())
        assertEquals(Long.MAX_VALUE, restored.snapshot().spentTokens)
        assertThrows(TokenBudgetExceededException::class.java) { restored.ensureCanContinue() }
    }
    @Test fun `disabled option avoids corrupt record IO and enabling fails closed`() {
        val directory = folder.newFolder()
        val caller = assistant(100)
        val id = Uuid.random()
        File(directory, "${caller.id}_$id.tokens").writeText("corrupt")
        val store = TokenBudgetStore(directory)
        val disabled = store.getLedger(caller.copy(localTools = emptyList()), id, emptyList())
        disabled.ensureCanContinue()
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET, disabled.snapshot().status)
        assertThrows(IOException::class.java) { store.getLedger(caller, id, emptyList()) }
    }
    @Test fun `enabling loads durable history into same previously disabled ledger`() {
        val directory = folder.newFolder()
        val caller = assistant(100)
        val id = Uuid.random()
        File(directory, "${caller.id}_$id.tokens").writeText("1\n70\n0\n")
        val store = TokenBudgetStore(directory)
        val disabled = store.getLedger(caller.copy(localTools = emptyList()), id, emptyList())
        assertEquals(0L, disabled.snapshot().spentTokens)
        val enabled = store.getLedger(caller, id, emptyList())
        assertSame(disabled, enabled)
        assertEquals(70L, enabled.snapshot().spentTokens)
    }
    @Test fun `disabled background reservation lifecycle performs no durable writes`() {
        val caller = assistant().copy(localTools = emptyList())
        var writes = 0
        val store = TokenBudgetStore(folder.newFolder()) { _, _ -> writes++; throw IOException("disk full") }
        val ledger = store.getLedger(caller, Uuid.random(), emptyList())
        ledger.ensureCanContinue()
        val request = ledger.reserve(Long.MAX_VALUE, 30)
        request.markSubmitted()
        request.observe(TokenUsage(10, 20))
        request.markCompleted()
        request.close()
        assertEquals(0, writes)
        assertEquals(0L, ledger.snapshot().spentTokens)
        assertEquals(30, request.maxTokens)
    }
    @Test fun `past enabled write failure does not block new disabled requests`() {
        val caller = assistant()
        val id = Uuid.random()
        val store = TokenBudgetStore(folder.newFolder()) { _, _ -> throw IOException("disk full") }
        val ledger = store.getLedger(caller, id, emptyList())
        val enabledRequest = ledger.reserve(10, 20)
        assertThrows(IOException::class.java) { enabledRequest.markSubmitted() }
        assertThrows(IOException::class.java) { enabledRequest.close() }
        assertSame(ledger, store.getLedger(caller.copy(localTools = emptyList()), id, emptyList()))
        ledger.ensureCanContinue()
        val disabledRequest = ledger.reserve(10, 20)
        disabledRequest.markSubmitted()
        disabledRequest.markCompleted()
        disabledRequest.close()
    }
    @Test fun `in-flight enabled accounting survives disabling feature for new requests`() {
        val caller = assistant()
        val id = Uuid.random()
        val directory = folder.newFolder()
        val store = TokenBudgetStore(directory)
        val ledger = store.getLedger(caller, id, emptyList())
        val request = ledger.reserve(10, 30)
        request.markSubmitted()
        store.getLedger(caller.copy(localTools = emptyList()), id, emptyList())
        request.observe(TokenUsage(10, 20))
        request.markCompleted()
        request.close()
        assertEquals(30L, TokenBudgetStore(directory).getLedger(caller, id, emptyList()).snapshot().spentTokens)
    }
    @Test fun `cleanup preserves primary exception and avoids self suppression`() {
        var writes = 0
        val ledger = TokenBudgetLedger(true, hardCap = 100, durableCallback = { _, _ ->
            if (++writes > 1) throw IOException("disk full")
        })
        val request = ledger.reserve(10, 30)
        request.markSubmitted()
        val cancelled = kotlinx.coroutines.CancellationException("stopped")
        assertFalse(request.closePreserving(cancelled))
        assertTrue(cancelled.suppressed.single() is TokenBudgetPersistenceException)
        assertEquals(0L, ledger.snapshot().reservedTokens)
        val original = ledger.runCatching { ensureCanContinue() }.exceptionOrNull()!!
        assertTrue(request.closePreserving(original)) // already closed; no duplicate/self suppression
    }
    @Test fun `live source cap reduction stops existing ledger without getLedger again`() {
        val caller = assistant(100)
        var latest: Assistant? = caller
        val ledger = TokenBudgetStore(folder.newFolder(), assistantSource = { latest })
            .getLedger(caller, Uuid.random(), emptyList())
        val request = ledger.reserve(10, 80)
        latest = caller.copy(tokenBudgetHardCap = 20)
        assertThrows(TokenBudgetExceededException::class.java) { request.markSubmitted() }
        request.close()
        val measured = ledger.reserve(1, 10)
        measured.observe(TokenUsage(1, 10))
        measured.close()
        latest = caller.copy(tokenBudgetHardCap = 10)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.ensureCanContinue() }
    }
    @Test fun `live disable is inert and re-enable keeps existing spend`() {
        val caller = assistant(100)
        var latest: Assistant? = caller
        val ledger = TokenBudgetStore(folder.newFolder(), assistantSource = { latest })
            .getLedger(caller, Uuid.random(), emptyList())
        val request = ledger.reserve(10, 30)
        request.markSubmitted()
        request.observe(TokenUsage(10, 20))
        request.markCompleted()
        request.close()
        latest = caller.copy(localTools = emptyList())
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET, ledger.snapshot().status)
        val disabled = ledger.reserve(Long.MAX_VALUE, 999)
        disabled.markSubmitted()
        disabled.close()
        latest = caller.copy(tokenBudgetHardCap = 20)
        assertThrows(TokenBudgetExceededException::class.java) { ledger.ensureCanContinue() }
        assertEquals(30L, ledger.snapshot().spentTokens)
    }
    @Test fun `live first enable lazily restores durable spend without another store lookup`() {
        val directory = folder.newFolder()
        val caller = assistant(100).copy(localTools = emptyList())
        val id = Uuid.random()
        File(directory, "${caller.id}_$id.tokens").writeText("1\n70\n0\n")
        var latest: Assistant? = caller
        val ledger = TokenBudgetStore(directory, assistantSource = { latest }).getLedger(caller, id, emptyList())
        assertEquals(0L, ledger.snapshot().spentTokens)
        latest = caller.copy(localTools = listOf(LocalToolOption.CostGuards))
        val request = ledger.reserve(10, 100)
        assertEquals(20, request.maxTokens)
        assertEquals(70L, ledger.snapshot().spentTokens)
        request.close()
    }
    @Test fun `live missing or wrong owner fails closed before billing`() {
        val caller = assistant(100)
        var latest: Assistant? = caller
        val ledger = TokenBudgetStore(folder.newFolder(), assistantSource = { latest })
            .getLedger(caller, Uuid.random(), emptyList())
        latest = null
        assertThrows(IllegalStateException::class.java) { ledger.ensureCanContinue() }
        assertThrows(IllegalStateException::class.java) { ledger.snapshot() }
        latest = assistant(100)
        assertThrows(IllegalStateException::class.java) { ledger.reserve(1, 1) }
    }
    @Test fun `concurrent completed children persist without stale write regression`() {
        val directory = folder.newFolder()
        val caller = assistant(1000)
        val id = Uuid.random()
        val ledger = TokenBudgetStore(directory).getLedger(caller, id, emptyList())
        val pool = Executors.newFixedThreadPool(5)
        try {
            val futures = (1..5).map { pool.submit {
                val request = ledger.reserve(10, 50)
                request.markSubmitted()
                request.observe(TokenUsage(10, 40))
                request.markCompleted()
                request.close()
            } }
            futures.forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(250L, TokenBudgetStore(directory).getLedger(caller, id, emptyList()).snapshot().spentTokens)
    }
}
