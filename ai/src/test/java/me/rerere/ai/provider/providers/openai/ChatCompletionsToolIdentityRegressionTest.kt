package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsToolIdentityRegressionTest {
    @Test
    fun `синхронные вызовы без id получают уникальные непустые id`() {
        val tools = parseTools("""{"tool_calls":[
            {"function":{"name":"first","arguments":"{}"}},
            {"id":"","function":{"name":"second","arguments":"{}"}}
        ]}""")
        assertTrue(tools.all { it.toolCallId.isNotBlank() })
        assertEquals(2, tools.map { it.toolCallId }.distinct().size)
        val next = parseTools("""{"tool_calls":[{"function":{"name":"first","arguments":"{}"}}]}""").single()
        assertTrue(tools.none { it.toolCallId == next.toolCallId })
    }

    @Test
    fun `синхронные arguments в виде объекта принимаются как JSON`() {
        val tools = parseTools("""{"tool_calls":[{"id":"call","function":{"name":"test","arguments":{"value":42}}}]}""")
        assertEquals("call", tools.single().toolCallId)
        assertEquals("""{"value":42}""", tools.single().input)
    }

    @Test
    fun `параллельные потоки без provider id не переиспользуют id вызовов`() {
        val first = streamStart(ChatCompletionsStreamDecoder())
        val second = streamStart(ChatCompletionsStreamDecoder())
        assertTrue(first.id.isNotBlank())
        assertTrue(second.id.isNotBlank())
        assertTrue(first.id != second.id)
    }

    @Test
    fun `пустой id продолжения сохраняет существующий id по индексу`() {
        val decoder = ChatCompletionsStreamDecoder()
        val initial = decoder.accept(event("""{"index":0,"id":"call-first","function":{"name":"first","arguments":"{"}}""")).chunks
        decoder.accept(event("""{"index":1,"id":"call-second","function":{"name":"second","arguments":"{}"}}"""))
        val continuation = decoder.accept(event("""{"index":0,"id":"","function":{"arguments":"}"}}""")).chunks
        assertEquals("call-first", initial.filterIsInstance<StreamChunk.ToolCallStart>().single().id)
        assertTrue(continuation.none { it is StreamChunk.ToolCallStart })
        assertEquals("call-first", continuation.filterIsInstance<StreamChunk.ToolCallDelta>().single().id)
    }

    @Test
    fun `потоковые arguments в виде объекта принимаются как JSON`() {
        val chunks = ChatCompletionsStreamDecoder().accept(event("""{"index":0,"id":"call","function":{"name":"test","arguments":{"value":42}}}""")).chunks
        assertEquals("""{"value":42}""", chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta)
    }

    @Test
    fun `пустой response id не затирает существующий`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"id":"response-original","choices":[{"delta":{"content":"Первый"}}]}"""))
        decoder.accept(SseEvent(data = """{"id":"","choices":[{"delta":{"content":"Второй"}}]}"""))
        assertEquals("response-original", decoder.onClosed().filterIsInstance<StreamChunk.Finish>().single().responseId)
    }

    private fun streamStart(decoder: ChatCompletionsStreamDecoder): StreamChunk.ToolCallStart =
        decoder.accept(event("""{"index":0,"function":{"name":"test","arguments":"{}"}}"""))
            .chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()

    private fun event(tool: String): SseEvent = SseEvent(data = """{"choices":[{"delta":{"tool_calls":[$tool]}}]}""")

    private fun parseTools(payload: String): List<UIMessagePart.Tool> {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
            .apply { isAccessible = true }
        val message = method.invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), json.parseToJsonElement(payload)) as UIMessage
        return message.parts.filterIsInstance<UIMessagePart.Tool>()
    }
}
