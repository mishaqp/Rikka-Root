package me.rerere.rikkahub.workflow.db

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads only ownership-bearing fields; a UUID in prose, prompts or commands is not a binding. */
internal fun workflowReferencesAssistant(raw: String, assistantId: String): Boolean {
    val definition = try {
        Json.parseToJsonElement(raw) as? JsonObject ?: return false
    } catch (_: SerializationException) {
        recoverReferenceFields(raw)
    }
    return referencesAssistant(definition, assistantId)
}

private fun referencesAssistant(definition: JsonObject, assistantId: String): Boolean {
    if ((definition["authoring_assistant_id"] as? JsonPrimitive)?.contentOrNull == assistantId) return true
    return (definition["actions"] as? JsonArray).orEmpty().any { action ->
        val call = action as? JsonObject ?: return@any false
        if ((call["tool"] as? JsonPrimitive)?.contentOrNull != "manage_assistants") return@any false
        val args = call["args"] as? JsonObject ?: return@any false
        (args["action"] as? JsonPrimitive)?.contentOrNull in assistantTargetActions &&
            (args["assistant_id"] as? JsonPrimitive)?.contentOrNull == assistantId
    }
}

/** Retain complete top-level reference subtrees even if another field is damaged. Strings
 * are skipped whole, so prose and unrelated tools cannot masquerade as ownership fields.
 * Recovered actions pass the same semantic matcher as a complete workflow.
 */
private fun recoverReferenceFields(raw: String): JsonObject {
    val fields = linkedMapOf<String, JsonElement>()
    var index = raw.afterWhitespace(0)
    if (raw.getOrNull(index) != '{') return JsonObject(fields)
    val brackets = ArrayDeque<Char>()
    while (index < raw.length) {
        when (val ch = raw[index]) {
            '"' -> {
                val end = quotedStringEnd(raw, index) ?: break
                if (brackets.size == 1 && brackets.last() == '{') {
                    val key = try {
                        (Json.parseToJsonElement(raw.substring(index, end)) as? JsonPrimitive)?.contentOrNull
                    } catch (_: SerializationException) { null }
                    val colon = raw.afterWhitespace(end)
                    if (key != null && key in referenceFields && raw.getOrNull(colon) == ':') {
                        val start = raw.afterWhitespace(colon + 1)
                        val valueEnd = if (key == "authoring_assistant_id") quotedStringEnd(raw, start)
                            else if (raw.getOrNull(start) == '[') containerEnd(raw, start) else null
                        var value = valueEnd?.let {
                            try { Json.parseToJsonElement(raw.substring(start, it)) }
                            catch (_: SerializationException) { null }
                        }
                        if (value == null && key == "actions" && raw.getOrNull(start) == '[') {
                            value = completeActionPrefix(raw, start)
                        }
                        if (value != null) fields[key] = value
                    }
                }
                index = end
            }
            '{', '[' -> { brackets.addLast(ch); index++ }
            '}', ']' -> {
                if (brackets.lastOrNull() != if (ch == '}') '{' else '[') break
                brackets.removeLast()
                index++
                if (brackets.isEmpty()) break
            }
            else -> index++
        }
    }
    return JsonObject(fields)
}

/** An unfinished array can still contain complete verified action objects. Stop at the
 * first damaged element; never scan past invalid strings or trust unfinished tool/args.
 */
private fun completeActionPrefix(raw: String, start: Int): JsonArray {
    val actions = mutableListOf<JsonElement>()
    var index = raw.afterWhitespace(start + 1)
    while (raw.getOrNull(index) == '{') {
        val end = containerEnd(raw, index) ?: break
        val action = try { Json.parseToJsonElement(raw.substring(index, end)) as? JsonObject }
            catch (_: SerializationException) { null }
        if (action == null) break
        actions += action
        index = raw.afterWhitespace(end)
        if (raw.getOrNull(index) != ',') break
        index = raw.afterWhitespace(index + 1)
    }
    return JsonArray(actions)
}

private fun containerEnd(raw: String, start: Int): Int? {
    val brackets = ArrayDeque<Char>()
    var index = start
    while (index < raw.length) {
        when (val ch = raw[index]) {
            '"' -> index = quotedStringEnd(raw, index) ?: return null
            '{', '[' -> { brackets.addLast(ch); index++ }
            '}', ']' -> {
                if (brackets.lastOrNull() != if (ch == '}') '{' else '[') return null
                brackets.removeLast()
                index++
                if (brackets.isEmpty()) return index
            }
            else -> index++
        }
    }
    return null
}

private fun quotedStringEnd(raw: String, start: Int): Int? {
    if (raw.getOrNull(start) != '"') return null
    var index = start + 1
    while (index < raw.length) {
        when (raw[index]) {
            '\\' -> index += 2
            '"' -> return index + 1
            else -> index++
        }
    }
    return null
}

private fun String.afterWhitespace(start: Int): Int {
    var index = start
    while (getOrNull(index)?.isWhitespace() == true) index++
    return index
}

private val assistantTargetActions = setOf("get", "update", "duplicate", "switch", "delete")
private val referenceFields = setOf("authoring_assistant_id", "actions")
