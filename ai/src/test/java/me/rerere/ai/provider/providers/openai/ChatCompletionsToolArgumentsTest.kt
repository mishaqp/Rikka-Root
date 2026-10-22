package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.prepareArguments
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic protocol fixtures, not a captured response or evidence about a particular provider. */
class ChatCompletionsToolArgumentsTest {
    private val definition = Tool(
        name = "root_exec",
        description = "Argument preservation fixture",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("command") { put("type", "string") }
                    putJsonObject("timeout_ms") { put("type", "integer") }
                },
                required = listOf("command"),
            )
        },
        execute = { error("This fixture must never execute shell commands") },
    )

    @Test
    fun `space and newline only argument deltas survive decoding merging and partial message persistence`() {
        val fragments = listOf(
            "{", "\n", "  \"command\":", " ", "\"sleep", " ", "40", "\\", "n",
            "chmod", " ", "755", " ", "/data/local/tmp/task.sh", "\\n",
            "status", " ", "2", ">", "/dev/null", "\\n",
            "ls", " ", "/data/adb/ksu", " ", "2", ">", "/dev/null", "\"", ",", "\n",
            "  \"timeout_ms\":", " ", "\"45000\"", "\n", "}",
        )
        val replay = replay(fragments.mapIndexed { index, fragment ->
            event(toolDelta(0, fragment, start = index == 0))
        })
        val tool = replay.message.getTools().single()

        assertEquals(fragments, replay.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta })
        assertEquals(fragments.joinToString(""), tool.input)
        val canonical = definition.prepareArguments(tool.inputAsJson())
        assertEquals(
            "sleep 40\nchmod 755 /data/local/tmp/task.sh\nstatus 2>/dev/null\nls /data/adb/ksu 2>/dev/null",
            canonical.getValue("command").jsonPrimitive.content,
        )
        assertEquals(JsonPrimitive(45000), canonical["timeout_ms"])
    }

    @Test
    fun `batched JSON lines preserve whitespace inside argument strings through payload fallback`() {
        val fragments = listOf("{", "\n", "\"command\":", " ", "\"sleep", " ", "40\"", "\n", "}")
        val payload = fragments.mapIndexed { index, fragment ->
            event(toolDelta(0, fragment, start = index == 0)).data
        }.joinToString("\n", prefix = " \n", postfix = "\n ")
        val replay = replay(listOf(SseEvent(data = payload)))
        val tool = replay.message.getTools().single()

        assertEquals(fragments, replay.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta })
        assertEquals(fragments.joinToString(""), tool.input)
        assertEquals("sleep 40", definition.prepareArguments(tool.inputAsJson()).getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `quotes escapes redirects and significant whitespace survive every argument character boundary`() {
        val commands = listOf(
            "sleep 40",
            "chmod 755 '/data/local/tmp/a b.sh'",
            "status 2>/dev/null",
            "ls /data/adb/ksu 2>/dev/null",
            "  " + """printf '%s\n' "a  b" 'c d' a\ b""" + "  ",
            """echo "quoted \"value\"" >> '/data/local/tmp/log file' 2>&1""",
            "printf 'first\\nsecond'\n\tsleep 40\r\n",
        )
        for (command in commands) {
            val input = buildJsonObject { put("command", command) }.toString()
            val replay = replay(input.mapIndexed { index, character ->
                event(toolDelta(0, character.toString(), start = index == 0))
            })
            val tool = replay.message.getTools().single()

            assertEquals(input, tool.input)
            assertEquals(command, tool.inputAsJson().jsonObject.getValue("command").jsonPrimitive.content)
            assertEquals(command, definition.prepareArguments(tool.inputAsJson()).getValue("command").jsonPrimitive.content)
        }
    }

    @Test
    fun `already adjacent command tokens are preserved without inventing separators`() {
        for (command in listOf("sleep40", "chmod755", "status2>/dev/null", "ls /data/adb/ksu2>/dev/null")) {
            val input = buildJsonObject { put("command", command) }.toString()
            val tool = replay(input.mapIndexed { index, character ->
                event(toolDelta(0, character.toString(), start = index == 0))
            }).message.getTools().single()

            assertEquals(input, tool.input)
            assertEquals(command, definition.prepareArguments(tool.inputAsJson()).getValue("command").jsonPrimitive.content)
        }
    }

    @Test
    fun `interleaved indexed calls keep whitespace escapes and redirects attached to their own arguments`() {
        val commands = listOf("sleep 40\nstatus 2>/dev/null", """printf '%s\n' "a  b" > '/data/local/tmp/log file'""")
        val inputs = commands.map { command -> "\n " + buildJsonObject { put("command", command) } + " \n" }
        val events = mutableListOf(event(toolDelta(0, "", start = true), toolDelta(1, "", start = true)))
        for (offset in 0 until inputs.maxOf { it.length }) {
            // Array order changes while explicit tool indices retain ownership of each stream.
            val indices = if (offset % 2 == 0) listOf(1, 0) else listOf(0, 1)
            val deltas = indices.mapNotNull { index ->
                inputs[index].getOrNull(offset)?.let { toolDelta(index, it.toString()) }
            }
            events += event(*deltas.toTypedArray())
        }
        val replay = replay(events)
        val tools = replay.message.getTools()

        assertEquals(listOf("call_0", "call_1"), tools.map { it.toolCallId })
        assertEquals(listOf("root_exec", "root_exec"), tools.map { it.toolName })
        assertEquals(inputs, tools.map { it.input })
        assertEquals(commands, tools.map { definition.prepareArguments(it.inputAsJson()).getValue("command").jsonPrimitive.content })
        for ((index, input) in inputs.withIndex()) {
            assertEquals(
                input.map { it.toString() },
                replay.chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
                    .filter { it.id == "call_$index" }.map { it.inputDelta },
            )
        }
    }

    private fun toolDelta(index: Int, input: String, start: Boolean = false): JsonObject = buildJsonObject {
        put("index", index)
        if (start) {
            put("id", "call_$index")
            put("type", "function")
        }
        putJsonObject("function") {
            if (start) put("name", "root_exec")
            put("arguments", input)
        }
    }

    private fun event(vararg deltas: JsonObject): SseEvent = SseEvent(data = buildJsonObject {
        put("id", "synthetic-response")
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                putJsonObject("delta") { put("tool_calls", JsonArray(deltas.toList())) }
            }
        }
    }.toString())

    private fun replay(events: List<SseEvent>): Replay {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = (events + SseEvent(data = "[DONE]")).flatMap { decoder.accept(it).chunks } + decoder.onClosed()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("Preserve tool arguments"))
        val prefixes = mutableMapOf<String, String>()
        for (chunk in chunks) {
            messages = handler.handle(messages, chunk)
            if (chunk is StreamChunk.ToolCallDelta) {
                prefixes[chunk.id] = prefixes[chunk.id].orEmpty() + chunk.inputDelta
                assertEquals(prefixes[chunk.id], messages.last().getTools().single { it.toolCallId == chunk.id }.input)
            }
            // Persist even an incomplete JSON string or escape, then continue with the same handler.
            val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(messages))
            assertEquals(messages, restored)
            messages = restored
        }
        assertTrue(messages.last().getTools().all { it.providerResponseComplete })
        return Replay(messages.last(), chunks)
    }

    private data class Replay(val message: UIMessage, val chunks: List<StreamChunk>)
}
