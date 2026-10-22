package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ClaudeThinkingPart3Test {
    private val provider = ClaudeProvider(OkHttpClient())

    @Test fun legacyClaudeFourAndFourFiveUseSupportedBudgetThinking() {
        for (id in listOf("claude-opus-4-20250514", "claude-sonnet-4-20250514", "claude-opus-4-5-20251101", "claude-haiku-4-5-20251001")) {
            val request = request(id, ReasoningLevel.AUTO)
            val thinking = request["thinking"]!!.jsonObject
            assertEquals(id, "enabled", thinking["type"]!!.jsonPrimitive.content)
            val budget = thinking["budget_tokens"]?.jsonPrimitive?.intOrNull
            assertTrue("$id needs a positive supported budget: $thinking", budget != null && budget >= 1024)
            assertTrue("$id thinking must fit output budget", budget!! < request["max_tokens"]!!.jsonPrimitive.int)
            assertFalse(id, "display" in thinking)
        }
    }
    @Test fun adaptiveModelsAndOffKeepTheirSupportedContract() {
        for (id in listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-7")) {
            val thinking = request(id, ReasoningLevel.AUTO)["thinking"]!!.jsonObject
            assertEquals(id, "adaptive", thinking["type"]!!.jsonPrimitive.content)
            assertFalse("budget_tokens" in thinking)
            assertEquals("disabled", request(id, ReasoningLevel.OFF)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }
    @Test fun opaqueReasoningMustNotInsertBlankLinesIntoVisibleText() {
        val hidden = UIMessagePart.Reasoning("", kotlin.time.Clock.System.now(), null,
            ClaudeReasoningMetadata(redactedData = "opaque").toMetadata())
        val message = UIMessage.assistant("Первый").copy(parts = listOf(
            hidden, UIMessagePart.Text("Первый"), hidden, UIMessagePart.Text("Второй"), hidden,
        ))
        assertEquals("Первый\nВторой", message.toText())
        assertEquals(3, message.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
    }
    @Test fun nonstreamRedactedThinkingSurvivesExactToolRoundtrip() {
        val parsed = provider.parseMessage(original)
        assertRoundtrip(parsed)
    }
    @Test fun streamingRedactedThinkingSurvivesExactToolRoundtrip() {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(Model(modelId = "claude-opus-4-6"))
        var messages = listOf(UIMessage.user("Погода"))
        fun event(type: String, value: JsonObject) {
            decoder.accept(SseEvent(event = type, data = value.toString())).chunks.forEach { messages = handler.handle(messages, it) }
        }
        original.forEachIndexed { index, element ->
            val start = if (index == 0) buildJsonObject { put("type", "thinking"); put("thinking", "") } else element
            event("content_block_start", buildJsonObject { put("index", index); put("content_block", start) })
            // Actual SSE starts with empty thinking; readable text/signature arrive as deltas.
            if (element.jsonObject["type"]!!.jsonPrimitive.content == "thinking") {
                event("content_block_delta", buildJsonObject { put("index", index); put("delta", buildJsonObject {
                    put("type", "thinking_delta"); put("thinking", "Обычное рассуждение")
                }) })
                event("content_block_delta", buildJsonObject { put("index", index); put("delta", buildJsonObject {
                    put("type", "signature_delta"); put("signature", "ordinary-signature")
                }) })
            }
            event("content_block_stop", buildJsonObject { put("index", index) })
        }
        event("message_stop", buildJsonObject { put("type", "message_stop") })
        assertRoundtrip(messages.last())
    }

    private fun assertRoundtrip(message: UIMessage) {
        val answered = message.copy(parts = message.parts.map { if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("Солнечно"))) else it })
        val wire = messages(listOf(UIMessage.user("Погода"), answered))
        val blocks = wire[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "redacted_thinking", "tool_use"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(original[0], blocks[0])
        assertEquals(original[1], blocks[1])
        assertEquals(original[2], blocks[2])
        assertEquals("tool_result", wire[2].jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }
    private fun request(id: String, level: ReasoningLevel): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest", ProviderSetting.Claude::class.java,
            List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        return method.invoke(provider, ProviderSetting.Claude(), listOf(UIMessage.user("Проверка")),
            TextGenerationParams(model = Model(modelId = id, abilities = listOf(ModelAbility.REASONING)), reasoningLevel = level, maxTokens = 8192), false) as JsonObject
    }
    private fun messages(values: List<UIMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessages", List::class.java, Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java).apply { isAccessible = true }
        return method.invoke(provider, values, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
    }
    private val original = json.parseToJsonElement("""[
      {"type":"thinking","thinking":"Обычное рассуждение","signature":"ordinary-signature"},
      {"type":"redacted_thinking","data":"ZW5jcnlwdGVkLXRob3VnaHQ="},
      {"type":"tool_use","id":"call","name":"weather","input":{"city":"Москва"}}
    ]""").jsonArray
}
