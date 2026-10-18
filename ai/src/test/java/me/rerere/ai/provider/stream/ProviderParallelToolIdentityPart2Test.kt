package me.rerere.ai.provider.stream

import kotlinx.serialization.json.*
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.google.InteractionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

/** Different logical protocol indices must survive malformed duplicate provider IDs independently. */
class ProviderParallelToolIdentityPart2Test {
    @Test fun `Claude duplicate block ids retain separate indexed argument streams`() {
        val events = mutableListOf<SseEvent>()
        for ((index, label) in listOf("first", "second").withIndex()) {
            events += event("content_block_start") {
                put("index", index)
                putJsonObject("content_block") {
                    put("type", "tool_use"); put("id", "duplicate"); put("name", "${label}_tool")
                    putJsonObject("input") {}
                }
            }
        }
        for ((index, label) in listOf("first", "second").withIndex().reversed()) {
            events += event("content_block_delta") {
                put("index", index)
                putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", """{"value":"$label"}""") }
            }
            events += event("content_block_stop") { put("index", index) }
        }
        assertSeparate(ClaudeStreamDecoder(), events)
    }

    @Test fun `Google complete parallel functionCall parts with duplicate ids remain separate`() {
        val payload = buildJsonObject {
            putJsonArray("candidates") { addJsonObject {
                putJsonObject("content") {
                    put("role", "model")
                    putJsonArray("parts") { for (label in listOf("first", "second")) addJsonObject {
                        putJsonObject("functionCall") {
                            put("id", "duplicate"); put("name", "${label}_tool")
                            putJsonObject("args") { put("value", label) }
                        }
                    } }
                }
            } }
        }
        assertSeparate(GoogleStreamDecoder("response", "gemini"), listOf(SseEvent(data = payload.toString())))
    }

    @Test fun `Responses duplicate call ids keep distinct item indexed argument streams`() {
        val events = mutableListOf<SseEvent>()
        for ((index, label) in listOf("first", "second").withIndex()) {
            events += event("response.output_item.added") {
                put("output_index", index)
                putJsonObject("item") {
                    put("type", "function_call"); put("id", "item-$index"); put("call_id", "duplicate")
                    put("name", "${label}_tool"); put("arguments", "")
                }
            }
        }
        for ((index, label) in listOf("first", "second").withIndex().reversed()) {
            events += event("response.function_call_arguments.delta") {
                put("output_index", index); put("item_id", "item-$index"); put("delta", """{"value":"$label"}""")
            }
            events += event("response.function_call_arguments.done") {
                put("output_index", index); put("item_id", "item-$index"); put("arguments", """{"value":"$label"}""")
            }
        }
        assertSeparate(ResponseApiStreamDecoder(), events)
    }

    @Test fun `Responses optional item ids use distinct output indexes for duplicate call ids`() {
        val events = mutableListOf<SseEvent>()
        for ((index, label) in listOf("first", "second").withIndex()) {
            events += event("response.output_item.added") {
                put("output_index", index)
                putJsonObject("item") {
                    put("type", "function_call"); put("call_id", "duplicate")
                    put("name", "${label}_tool"); put("arguments", "")
                }
            }
        }
        for ((index, label) in listOf("first", "second").withIndex().reversed()) {
            events += event("response.function_call_arguments.delta") {
                put("output_index", index); put("call_id", "duplicate"); put("delta", """{"value":"$label"}""")
            }
            events += event("response.function_call_arguments.done") {
                put("output_index", index); put("call_id", "duplicate"); put("arguments", """{"value":"$label"}""")
            }
        }
        assertSeparate(ResponseApiStreamDecoder(), events)
    }

    @Test fun `Interactions duplicate ids keep separate indexed steps and argument fragments`() {
        val events = mutableListOf<SseEvent>()
        for ((index, label) in listOf("first", "second").withIndex()) {
            events += event("step.start") {
                put("event_type", "step.start"); put("index", index)
                putJsonObject("step") {
                    put("type", "function_call"); put("id", "duplicate"); put("name", "${label}_tool")
                    putJsonObject("arguments") {}
                }
            }
        }
        for ((index, label) in listOf("first", "second").withIndex().reversed()) {
            events += event("step.delta") {
                put("event_type", "step.delta"); put("index", index)
                putJsonObject("delta") { put("type", "arguments_delta"); put("arguments", """{"value":"$label"}""") }
            }
            events += event("step.stop") { put("event_type", "step.stop"); put("index", index) }
        }
        assertSeparate(InteractionsStreamDecoder("gemini"), events)
    }

    private fun assertSeparate(decoder: StreamChunkDecoder, events: List<SseEvent>) {
        val chunks = events.flatMap { decoder.accept(it).chunks } + decoder.onClosed()
        val handler = StreamChunkHandler()
        val messages = chunks.fold(listOf(UIMessage.user("Проверь"))) { history, chunk -> handler.handle(history, chunk) }
        val calls = messages.flatMap { it.getTools() }
        assertEquals(2, calls.size)
        assertEquals(listOf("first_tool", "second_tool"), calls.map { it.toolName })
        assertEquals(2, calls.map { it.toolCallId }.toSet().size)
        assertTrue(calls.all { it.toolCallId.isNotBlank() })
        assertEquals(listOf("first", "second"), calls.map { Json.parseToJsonElement(it.input).jsonObject.getValue("value").jsonPrimitive.content })
        assertEquals(2, chunks.filterIsInstance<StreamChunk.ToolCallStart>().size)
        for (call in calls) {
            assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallDelta>().any { it.id == call.toolCallId })
        }
    }

    private fun event(type: String, body: JsonObjectBuilder.() -> Unit) = SseEvent(event = type,
        data = buildJsonObject { put("type", type); body() }.toString())
}
