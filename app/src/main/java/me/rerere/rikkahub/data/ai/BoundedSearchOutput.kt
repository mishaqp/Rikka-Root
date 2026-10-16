package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Keep structured citation identities intact while fitting the serialized search result in context. */
internal fun boundedSearchOutput(text: String, maxChars: Int): String? {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val originalItems = root["items"] as? JsonArray ?: return null
    val identityFields = setOf("id", "url", "index", "title", "publishedDate")
    fun clip(value: JsonElement, limit: Int): JsonElement = when (value) {
        is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(limit)) else value
        is JsonArray -> JsonArray(if (limit == 0) emptyList() else value.map { clip(it, limit) })
        is JsonObject -> JsonObject(value.mapValues { (_, child) -> clip(child, limit) })
    }
    fun render(count: Int, limit: Int, keepMetadata: Boolean): String {
        val fields = if (keepMetadata) root.toMutableMap() else mutableMapOf()
        if (keepMetadata) root["answer"]?.let { fields["answer"] = clip(it, limit) }
        fields["items"] = JsonArray(originalItems.take(count).map { item ->
            if (item is JsonObject) JsonObject(item.mapValues { (name, value) ->
                if (name in identityFields) value else clip(value, limit)
            }) else clip(item, limit)
        })
        fields["truncated"] = JsonPrimitive(true)
        fields["truncation_note"] = JsonPrimitive("Вывод поиска сокращён. Идентификаторы и адреса показанных источников сохранены.")
        if (count < originalItems.size) fields["omitted_items"] = JsonPrimitive(originalItems.size - count)
        return JsonObject(fields).toString()
    }

    var count = originalItems.size
    var keepMetadata = true
    var result = render(count, 0, keepMetadata)
    if (result.length > maxChars) {
        // Oversized metadata or citation identities cannot be cut into invalid URLs.
        keepMetadata = false
        var low = 0
        var high = count
        count = 0
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (render(middle, 0, false).length <= maxChars) {
                count = middle
                low = middle + 1
            } else high = middle - 1
        }
        result = render(count, 0, false)
    }
    if (result.length > maxChars) return null
    var low = 1
    var high = minOf(text.length, maxChars)
    while (low <= high) {
        val middle = low + (high - low) / 2
        val candidate = render(count, middle, keepMetadata)
        if (candidate.length <= maxChars) {
            result = candidate
            low = middle + 1
        } else high = middle - 1
    }
    return result
}
