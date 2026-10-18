package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.tools.WebContentGuard
import org.junit.Assert.*
import org.junit.Test

class GenerationLoopOutputEnvelopePart2Test {
    private val cap = 32 * 1024
    private fun tool(name: String, raw: String, id: String = "call") = UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = "{}",
        output = listOf(UIMessagePart.Text(raw)), approvalState = ToolApprovalState.Approved,
        metadata = buildJsonObject { put("fixture", "preserve") },
    )
    private fun history(vararg tools: UIMessagePart.Tool) = listOf(
        UIMessage.user("Read the requested data"), UIMessage.assistant("").copy(parts = tools.toList()),
    )
    private fun tools(messages: List<UIMessage>) = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
    private fun envelope(output: UIMessagePart.Tool, totalChars: Int): JsonObject {
        val text = output.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue("Serialized result must fit the existing cap: ${text.length}", text.length <= cap)
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        assertTrue("Generic overflow must be a JSON object, not a broken prefix: ${text.takeLast(100)}", parsed is JsonObject)
        val value = parsed as JsonObject
        assertEquals(JsonPrimitive(true), value["truncated"])
        assertEquals(JsonPrimitive(totalChars), value["total_chars"])
        assertTrue("preview must be an honest string fragment", (value["preview"] as? JsonPrimitive)?.isString == true)
        val message = value["message"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(message, Regex("[А-Яа-яЁё]").containsMatchIn(message))
        assertTrue("Must warn against blindly repeating effects: $message", message.contains("не повтор", ignoreCase = true))
        assertFalse("No workspace retrieval path may be invented", message.contains("/tool_outputs/"))
        return value
    }
    private fun assertWholeCharacters(text: String) {
        for (index in text.indices) {
            if (text[index].isHighSurrogate()) assertTrue("Unpaired high surrogate at $index", index + 1 < text.length && text[index + 1].isLowSurrogate())
            if (text[index].isLowSurrogate()) assertTrue("Unpaired low surrogate at $index", index > 0 && text[index - 1].isHighSurrogate())
        }
    }

    @Test fun oversizedSmsStyleJsonHasExplicitEnvelopeAndKeepsSavedOriginal() {
        val raw = buildJsonObject {
            put("messages", buildJsonArray { repeat(3) { add(buildJsonObject { put("body", "a".repeat(16384)) }) } })
            put("count", 3); put("truncated", false)
        }.toString()
        val original = tool("list_sms_inbox", raw)
        val saved = history(original)
        val projected = tools(boundToolOutputsForModel(saved)).single()
        val value = envelope(projected, raw.length)
        val preview = value.getValue("preview").jsonPrimitive.content
        assertTrue(preview.isNotEmpty()); assertTrue(raw.startsWith(preview)); assertTrue(preview.length < raw.length)
        assertEquals(listOf(original), tools(saved))
        assertEquals(raw, (tools(saved).single().output.single() as UIMessagePart.Text).text)
        assertEquals(original.copy(output = projected.output), projected)
    }

    @Test fun escapedTextFitsSerializedBudgetWithoutChangingOriginalPrefix() {
        val raw = "\"\\\n\t\r😀".repeat(10000)
        val original = tool("large_external_text", raw)
        val projected = tools(boundToolOutputsForModel(history(original))).single()
        val value = envelope(projected, raw.length)
        val preview = value.getValue("preview").jsonPrimitive.content
        assertTrue(preview.isNotEmpty()); assertTrue(raw.startsWith(preview))
        assertWholeCharacters(preview)
        assertEquals(raw, (original.output.single() as UIMessagePart.Text).text)
    }

    @Test fun supplementaryCharactersAreNotSplitAtAnyNearbyEnvelopeBudget() {
        for (offset in 0..4) {
            val raw = "a".repeat(offset) + "😀".repeat(25000)
            val value = envelope(tools(boundToolOutputsForModel(history(tool("unicode_output", raw)))).single(), raw.length)
            val preview = value.getValue("preview").jsonPrimitive.content
            assertTrue(raw.startsWith(preview)); assertWholeCharacters(preview)
        }
    }

    @Test fun repeatedLargeOutputRetainsWarningInsideSameSerializedBudget() {
        val raw = "\"quoted\" \\ path\n😀".repeat(5000)
        val short = tools(modelVisibleMessages(history(tool("http_request", "ok", "first"), tool("http_request", "ok", "second"))))
        val warning = short.last().output.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(warning.contains("повтор", ignoreCase = true))
        val saved = history(tool("http_request", raw, "first"), tool("http_request", raw, "second"))
        val projected = tools(modelVisibleMessages(saved))
        envelope(projected.first(), raw.length)
        val second = envelope(projected.last(), warning.length + 1 + raw.length)
        val preview = second.getValue("preview").jsonPrimitive.content
        assertTrue(preview, preview.startsWith(warning + "\n"))
        assertTrue((warning + "\n" + raw).startsWith(preview)); assertWholeCharacters(preview)
        assertTrue(tools(saved).all { it.output == listOf(UIMessagePart.Text(raw)) })
        assertEquals(listOf("first", "second"), projected.map { it.toolCallId })
    }

    @Test fun largeWebOutputKeepsMediaIdentityApprovalAndWebTaint() {
        val image = UIMessagePart.Image("https://example.test/image.png")
        val original = tool("http_request", "x".repeat(40000)).let { it.copy(output = it.output + image) }
        val saved = history(original)
        val projected = modelVisibleMessages(saved)
        val actual = tools(projected).single()
        envelope(actual, 40000)
        assertEquals(original.copy(output = actual.output), actual)
        assertEquals(listOf(image), actual.output.filterIsInstance<UIMessagePart.Image>())
        assertTrue(WebContentGuard.hasWebContent(saved)); assertTrue(WebContentGuard.hasWebContent(projected))
        assertEquals(listOf(original), tools(saved))
    }

    @Test fun shortJsonRemainsByteIdenticalWithoutAnEnvelope() {
        val raw = "{\"items\":[1,2],\"truncated\":false}"
        val original = tool("short_json", raw)
        assertEquals(listOf(original), tools(modelVisibleMessages(history(original))))
    }

    @Test fun structuredSearchKeepsItsExistingCitationSpecialization() {
        val raw = buildJsonObject {
            put("items", buildJsonArray { add(buildJsonObject {
                put("id", "source-1"); put("url", "https://example.test/full/path?q=1"); put("title", "Источник")
                put("text", "content ".repeat(10000))
            }) })
        }.toString()
        val original = tool("search_web", raw)
        val saved = history(original)
        val text = tools(boundToolOutputsForModel(saved)).single().output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.length <= cap)
        val value = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(true), value["truncated"])
        assertFalse("Search must retain its structured citation result", "preview" in value)
        val item = value.getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("source-1"), item["id"])
        assertEquals(JsonPrimitive("https://example.test/full/path?q=1"), item["url"])
        assertEquals(JsonPrimitive("Источник"), item["title"])
        assertEquals(listOf(original), tools(saved))
    }
}
