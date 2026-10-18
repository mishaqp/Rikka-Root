package me.rerere.ai.provider.stream

import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamDecoderRegressionTest {
    @Test
    fun `chat completions images from separate events stay separate and keep their mime type`() {
        val decoder = ChatCompletionsStreamDecoder()
        val events = listOf("image/jpeg" to "AAAA", "image/webp" to "BBBB").map { (mime, data) ->
            SseEvent(data = """{"id":"r1","choices":[{"index":0,"delta":{"images":[""" +
                """{"type":"image_url","image_url":{"url":"data:$mime;base64,$data"}}]}}]}""")
        }
        val images = replay(events.flatMap { decoder.accept(it).chunks } + decoder.onClosed())
            .filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf("data:image/jpeg;base64,AAAA", "data:image/webp;base64,BBBB"), images.map { it.url })
    }

    @Test
    fun `google images from separate events stay separate`() {
        val decoder = GoogleStreamDecoder("response-1", "gemini")
        val events = listOf("AAAA", "BBBB").map { data ->
            SseEvent(data = """{"candidates":[{"content":{"role":"model","parts":[""" +
                """{"inlineData":{"mimeType":"image/png","data":"$data"}}]}}]}""")
        }
        val images = replay(events.flatMap { decoder.accept(it).chunks } + decoder.onClosed())
            .filterIsInstance<UIMessagePart.Image>()
        assertEquals(listOf("data:image/png;base64,AAAA", "data:image/png;base64,BBBB"), images.map { it.url })
    }

    @Test
    fun `tool calls without index but with different ids are not merged`() {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = listOf(
            """{"id":"call_a","function":{"name":"first","arguments":"{\"a\":"}}""",
            """{"function":{"arguments":"1}"}}""",
            """{"id":"call_b","function":{"name":"second","arguments":"{\"b\":"}}""",
            """{"function":{"arguments":"2}"}}""",
        ).flatMap { tool ->
            decoder.accept(SseEvent(data = """{"id":"r1","choices":[{"index":0,"delta":{"tool_calls":[$tool]}}]}""")).chunks
        } + decoder.onClosed()
        val tools = replay(chunks).filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call_a", "call_b"), tools.map { it.toolCallId })
        assertEquals(listOf("first", "second"), tools.map { it.toolName })
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), tools.map { it.input })
    }

    @Test
    fun `multi-line JSON event is decoded as one payload`() {
        val data = """
            {
              "id": "r1",
              "choices": [{"index": 0, "delta": {"content": "Привет"}}]
            }
        """.trimIndent()
        val chunks = ChatCompletionsStreamDecoder().accept(SseEvent(data = data)).chunks
        assertEquals("Привет", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `several JSON lines in one event are still decoded line by line`() {
        val data = listOf("Один", " два").joinToString("\n") {
            """{"id":"r1","choices":[{"index":0,"delta":{"content":"$it"}}]}"""
        }
        val chunks = ChatCompletionsStreamDecoder().accept(SseEvent(data = data)).chunks
        assertEquals("Один два", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }

    private fun replay(chunks: List<StreamChunk>): List<UIMessagePart> {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("go"))
        chunks.forEach { messages = handler.handle(messages, it) }
        return messages.last().parts
    }
}
