package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BoundedSearchOutputTest {
    private val escapedText = "Строка \"цитата\" \\ путь\n漢字 😀\t".repeat(2_000)

    // Native SearchResult fields, with the optional citation id/index carried by tool output.
    private fun item(index: Int, url: String = "https://example.org/source/$index"): JsonObject = buildJsonObject {
        put("id", "source-$index")
        put("index", index)
        put("title", "Источник $index — 漢字")
        put("url", url)
        put("publishedDate", "2026-10-16")
        put("text", escapedText)
        putJsonArray("highlights") { add(escapedText); add(escapedText) }
    }

    private fun bounded(input: JsonObject, maxChars: Int): JsonObject {
        val result = boundedSearchOutput(input.toString(), maxChars)
        assertNotNull("structured search result must fit by removing content or whole items", result)
        assertTrue("serialized JSON must fit $maxChars chars, got ${result!!.length}", result.length <= maxChars)
        val output = Json.parseToJsonElement(result).jsonObject
        assertEquals(JsonPrimitive(true), output["truncated"])
        assertTrue(output["truncation_note"]!!.jsonPrimitive.content.contains("сокращён"))
        return output
    }

    private fun assertIdentity(original: JsonObject, actual: JsonObject) {
        listOf("id", "index", "url", "title", "publishedDate").forEach { field ->
            assertEquals("citation field $field must remain exact", original[field], actual[field])
        }
    }

    @Test fun `large native search JSON stays valid and preserves normal source identities`() {
        val originals = (0..2).map(::item)
        val input = buildJsonObject {
            put("answer", escapedText)
            put("items", JsonArray(originals))
            put("images", JsonArray(emptyList()))
            put("retrievedAt", "2026-10-16T12:00:00Z")
        }
        val output = bounded(input, 2_000)
        val items = output["items"]!!.jsonArray
        assertEquals(originals.size, items.size)
        originals.zip(items).forEach { (original, actual) -> assertIdentity(original, actual.jsonObject) }
        assertTrue(output["answer"]!!.jsonPrimitive.content.length < escapedText.length)
        items.forEach { actual ->
            assertTrue(actual.jsonObject["text"]!!.jsonPrimitive.content.length < escapedText.length)
            assertTrue(actual.jsonObject["highlights"]!!.jsonArray.all { it.jsonPrimitive.content.length < escapedText.length })
        }
    }

    @Test fun `huge images and root metadata are removed as whole fields`() {
        val source = item(0)
        val input = buildJsonObject {
            put("answer", escapedText)
            put("items", JsonArray(listOf(source)))
            putJsonArray("images") { add("https://example.org/" + "image".repeat(10_000)) }
            put("retrievedAt", "2026-10-16T12:00:00Z")
            putJsonObject("providerMetadata") { put("raw", escapedText) }
        }
        val output = bounded(input, 1_000)
        assertFalse(output.containsKey("images"))
        assertFalse(output.containsKey("providerMetadata"))
        assertFalse(output.containsKey("retrievedAt"))
        assertEquals(1, output["items"]!!.jsonArray.size)
        assertIdentity(source, output["items"]!!.jsonArray.single().jsonObject)
    }

    @Test fun `oversized URL omits whole items instead of cutting citation URL`() {
        val originals = listOf(item(0), item(1, "https://example.org/" + "segment".repeat(1_000)), item(2))
        val output = bounded(buildJsonObject { put("items", JsonArray(originals)) }, 900)
        val items = output["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertIdentity(originals.first(), items.single().jsonObject)
        assertEquals(JsonPrimitive(2), output["omitted_items"])
    }

    @Test fun `too many items omit complete sources while keeping retained citation fields`() {
        val originals = (0 until 80).map(::item)
        val output = bounded(buildJsonObject { put("items", JsonArray(originals)) }, 1_400)
        val items = output["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        assertTrue(items.size < originals.size)
        assertEquals(JsonPrimitive(originals.size - items.size), output["omitted_items"])
        originals.zip(items).forEach { (original, actual) -> assertIdentity(original, actual.jsonObject) }
    }

    @Test fun `invalid or unstructured output returns null`() {
        listOf("not JSON", "{broken", "[]", "null", "7", "{}", "{\"items\":{}}", "{\"answer\":\"text\"}").forEach {
            assertNull(it, boundedSearchOutput(it, 1_000))
        }
    }
}
