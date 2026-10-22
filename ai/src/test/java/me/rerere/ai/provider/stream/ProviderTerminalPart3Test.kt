package me.rerere.ai.provider.stream

import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.google.InteractionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderTerminalPart3Test {
    @Test fun chatEofWithoutFinishReasonFails() = unexpected(chat(), """{"choices":[{"delta":{"content":"Часть ответа"}}]}""")
    @Test fun responsesEofWithoutTerminalFails() = unexpected(ResponseApiStreamDecoder(), """{"type":"response.output_text.delta","item_id":"m","delta":"Часть ответа"}""")
    @Test fun googleEofWithoutFinishReasonFails() = unexpected(google(), """{"candidates":[{"content":{"parts":[{"text":"Часть ответа"}]}}]}""")
    @Test fun claudeEofWithoutMessageStopFails() {
        val decoder = ClaudeStreamDecoder()
        decoder.accept(SseEvent(event = "content_block_start", data = """{"index":0,"content_block":{"type":"text","text":""}}"""))
        unexpected(decoder, """{"index":0,"delta":{"type":"text_delta","text":"Часть ответа"}}""", "content_block_delta")
    }
    @Test fun interactionsEofWithoutTerminalFails() = unexpected(InteractionsStreamDecoder(),
        """{"event_type":"step.start","index":0,"step":{"type":"model_output","content":[{"type":"text","text":"Часть ответа"}]}}""")

    @Test fun chatCompleteToolJsonWithoutResponseTerminalFails() = unexpected(chat(),
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"read_only","arguments":"{}"}}]}}]}""")

    @Test fun chatDoneAndFinishReasonOnlyEofRemainSupported() {
        for (terminal in listOf("[DONE]", """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")) {
            val decoder = chat()
            val chunks = decoder.accept(SseEvent(data = terminal)).chunks + decoder.onClosed()
            assertEquals(1, chunks.count { it is StreamChunk.Finish })
        }
    }
    @Test fun googleFinishReasonOnlyEofRemainsSupported() {
        val decoder = google()
        val chunks = decoder.accept(SseEvent(data = """{"candidates":[{"finishReason":"STOP"}]}""")).chunks + decoder.onClosed()
        assertEquals("STOP", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }
    @Test fun claudeMessageStopRemainsSupported() {
        val decoder = ClaudeStreamDecoder()
        val result = decoder.accept(SseEvent(event = "message_stop", data = """{"type":"message_stop"}"""))
        assertTrue(result.completed)
        assertEquals(1, result.chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
    }
    @Test fun claudeStopReasonWithoutWholeMessageStopIsStillIncomplete() {
        val decoder = ClaudeStreamDecoder()
        decoder.accept(SseEvent(event = "message_delta", data = """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))
        val failure = runCatching { decoder.onClosed() }.exceptionOrNull()
        assertTrue("Claude message_delta is not message_stop; got $failure", failure is IOException)
    }
    @Test fun responsesCompletedAndIncompleteRemainSupported() {
        for (status in listOf("completed", "incomplete")) {
            val decoder = ResponseApiStreamDecoder()
            val result = decoder.accept(SseEvent(data = """{"type":"response.$status","response":{"id":"r","status":"$status"}}"""))
            assertTrue(result.completed)
            assertEquals(status, result.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
            assertTrue(decoder.onClosed().isEmpty())
        }
    }
    @Test fun interactionsCompletedRemainsSupported() {
        val decoder = InteractionsStreamDecoder()
        val result = decoder.accept(SseEvent(data = """{"event_type":"interaction.completed","interaction":{"id":"r","status":"completed"}}"""))
        assertTrue(result.completed)
        assertTrue(decoder.onClosed().isEmpty())
    }

    private fun unexpected(decoder: StreamChunkDecoder, data: String, event: String? = null) {
        assertFalse(decoder.accept(SseEvent(event = event, data = data)).completed)
        val failure = runCatching { decoder.onClosed() }.exceptionOrNull()
        assertTrue("Premature EOF must be an IOException; got $failure", failure is IOException)
        assertTrue(failure!!.message.orEmpty(), Regex("[А-Яа-я]").containsMatchIn(failure.message.orEmpty()))
    }
    private fun chat() = ChatCompletionsStreamDecoder()
    private fun google() = GoogleStreamDecoder("response", "test")
}
