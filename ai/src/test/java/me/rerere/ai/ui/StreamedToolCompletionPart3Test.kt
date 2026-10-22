package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamedToolCompletionPart3Test {
    private val json = Json { encodeDefaults = true }
    private val start = listOf(
        StreamChunk.ToolCallStart("call", "read_only"),
        StreamChunk.ToolCallDelta("call", inputDelta = "{}"),
    )

    @Test fun blockEndWithoutWholeResponseKeepsDurableToolIncomplete() {
        val tool = streamed(start + StreamChunk.ToolCallEnd("call")).last().getTools().single()
        val restored = json.decodeFromString<UIMessagePart.Tool>(json.encodeToString(tool))
        assertEquals("{}", restored.input)
        assertFalse(restored.isExecuted)
        assertEquals(false, complete(restored))
    }

    @Test fun onlySuccessfulWholeFinishCompletesDurableTool() {
        val tool = streamed(start + StreamChunk.Finish("tool_calls")).last().getTools().single()
        assertEquals(true, complete(tool))
        assertFalse(tool.isExecuted)
    }

    @Test fun incompleteTerminalAndTokenLimitKeepDurableToolIncomplete() {
        for (reason in listOf("incomplete", "incomplete:max_output_tokens", "length", "max_tokens", "MAX_TOKENS")) {
            val tool = streamed(start + StreamChunk.Finish(reason)).last().getTools().single()
            assertEquals(reason, false, complete(tool))
        }
    }

    @Test fun laterResponseFinishCannotCompleteEarlierInterruptedTool() {
        val partial = streamed(start)
        val handler = StreamChunkHandler(Model(modelId = "test"))
        val updated = handler.handle(partial, StreamChunk.TextStart("new"))
        val finished = handler.handle(updated, StreamChunk.Finish("stop"))
        assertEquals(false, complete(finished.last().getTools().single()))
    }

    @Test fun mergingFullToolWithInterruptedToolDoesNotEraseIncompleteEvidence() {
        val partial = streamed(start).last().getTools().single()
        val full = UIMessagePart.Tool("call", "", "")
        assertEquals(false, complete(full.merge(partial)))
        assertEquals(false, complete(partial.merge(full)))
    }

    @Test fun legacyStoredToolRemainsResumableWithDefaultCompatibility() {
        val restored = json.decodeFromString<UIMessagePart.Tool>(
            """{"toolCallId":"legacy","toolName":"read_only","input":"{}","approvalState":{"type":"approved"}}"""
        )
        assertTrue(restored.canResumeExecution)
        assertEquals(true, complete(restored))
    }

    @Test fun legacyAutoStateNeverAcquiresAStoredManualApproval() {
        val restored = json.decodeFromString<UIMessagePart.Tool>(
            """{"toolCallId":"legacy-auto","toolName":"read_only","input":"{}"}"""
        )
        assertFalse(restored.canResumeExecution)
        assertEquals(true, complete(restored))
    }

    private fun complete(tool: UIMessagePart.Tool): Boolean? =
        json.parseToJsonElement(json.encodeToString(tool)).jsonObject["provider_response_complete"]?.jsonPrimitive?.booleanOrNull

    private fun streamed(chunks: List<StreamChunk>): List<UIMessage> {
        val handler = StreamChunkHandler(Model(modelId = "test"))
        return chunks.fold(listOf(UIMessage.user("Проверка"))) { messages, chunk -> handler.handle(messages, chunk) }
    }
}
