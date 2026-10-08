package me.rerere.rikkahub.costguards

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CostGuardToolsTest {
    @Test fun `lazy lookup binds caller and refuses foreign or malformed id without loading`() = runBlocking {
        val caller = Assistant(tokenBudgetSoftCap = 5, tokenBudgetHardCap = 10)
        val id = Uuid.random()
        var loads = 0
        val tool = checkTokenUsageTool(caller, id) {
            loads++
            assertEquals(id, it)
            Conversation(id = id, assistantId = caller.id, messageNodes = listOf(
                MessageNode(messages = listOf(UIMessage.assistant("").copy(usage = TokenUsage(3, 4))))))
        }
        assertEquals(0, loads)
        val payload = Json.parseToJsonElement((tool.execute(buildJsonObject {})[0] as UIMessagePart.Text).text).jsonObject
        assertEquals("7", payload.getValue("total_tokens").jsonPrimitive.contentOrNull)
        assertEquals(id.toString(), payload.getValue("conversation_id").jsonPrimitive.contentOrNull)
        for (other in listOf(Uuid.random().toString(), "malformed")) {
            val result = tool.execute(buildJsonObject { put("conversation_id", other) })
            assertTrue(Json.parseToJsonElement((result[0] as UIMessagePart.Text).text).jsonObject.containsKey("error"))
        }
        assertEquals(1, loads)
    }
    @Test fun `side context reports shared live ledger without querying parent Room transcript`() = runBlocking {
        val caller = Assistant()
        val ledger = TokenBudgetLedger(true, 5, 100)
        val child = ledger.reserve(3, 10)
        child.observe(TokenUsage(3, 4))
        child.close()
        val tool = checkTokenUsageTool(caller, null, tokenBudget = ledger) { error("must not load") }
        val payload = Json.parseToJsonElement((tool.execute(buildJsonObject {})[0] as UIMessagePart.Text).text).jsonObject
        assertEquals("7", payload.getValue("total_tokens").jsonPrimitive.contentOrNull)
        assertEquals("shared_run", payload.getValue("scope").jsonPrimitive.contentOrNull)
        assertEquals("WARN", payload.getValue("status").jsonPrimitive.contentOrNull)
    }
    @Test fun `removed live ledger caps never resurrect stale assistant caps`() = runBlocking {
        val caller = Assistant(tokenBudgetSoftCap = 5, tokenBudgetHardCap = 10)
        val ledger = TokenBudgetLedger(true, softCap = 5, hardCap = 10)
        ledger.updateConfiguration(true, null, null)
        val tool = checkTokenUsageTool(caller, null, tokenBudget = ledger) { error("must not load") }
        val payload = Json.parseToJsonElement((tool.execute(buildJsonObject {})[0] as UIMessagePart.Text).text).jsonObject
        assertFalse(payload.containsKey("soft_cap"))
        assertFalse(payload.containsKey("hard_cap"))
        assertEquals("NO_BUDGET", payload.getValue("status").jsonPrimitive.contentOrNull)
    }
    @Test fun `missing context and mismatched owner never expose conversation`() = runBlocking {
        val caller = Assistant()
        val missing = checkTokenUsageTool(caller, null) { error("must not load") }
        assertTrue((missing.execute(buildJsonObject {})[0] as UIMessagePart.Text).text.contains("error"))
        val id = Uuid.random()
        val foreign = checkTokenUsageTool(caller, id) { Conversation(id = id, assistantId = Uuid.random(), messageNodes = emptyList()) }
        assertTrue((foreign.execute(buildJsonObject {})[0] as UIMessagePart.Text).text.contains("error"))
    }
}
