package me.rerere.ai.core

import java.math.BigDecimal
import kotlinx.serialization.json.*

/** A recoverable model input error. Messages contain schema paths, never argument values. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** The same object must be used for permission checks, approval cards and execution. */
fun Tool.prepareArguments(input: JsonElement): JsonObject {
    if (input !is JsonObject) invalidArgument("аргументы", "ожидается JSON-объект")
    val schema = parametersOrEmpty() as InputSchema.Obj
    val root = buildJsonObject {
        put("type", "object")
        put("properties", schema.properties)
        schema.required?.let { required -> put("required", JsonArray(required.map(::JsonPrimitive))) }
    }
    return normalizeArgument(input, root, "аргументы", coerce = true) as JsonObject
}

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
private val argumentJson = Json

private fun invalidArgument(path: String, reason: String): Nothing = throw ToolArgumentException(
    "Некорректный параметр ${path.take(240)}: $reason. Исправьте параметры и повторите вызов."
)

private fun normalizeArgument(value: JsonElement, schema: JsonObject, path: String, coerce: Boolean): JsonElement {
    for (keyword in listOf("anyOf", "oneOf")) {
        val branches = schema[keyword] as? JsonArray ?: continue
        val base = JsonObject(schema - keyword)
        fun match(branch: JsonObject, allowCoercion: Boolean): JsonElement? = try {
            normalizeArgument(normalizeArgument(value, branch, path, allowCoercion), base, path, allowCoercion)
        } catch (_: ToolArgumentException) { null }
        // Bounds, patterns and references still belong to the leaf/provider validator.
        // They can distinguish overlapping native branches, so guessing cardinality would
        // reject valid unchanged input. Never select such a branch for coercion.
        val undecidableNative = branches.filterIsInstance<JsonObject>().any { branch ->
            !supportsArgumentAssertions(branch) && match(branch, false) != null
        }
        if (undecidableNative) return normalizeArgument(value, base, path, coerce = false)
        fun matches(allowCoercion: Boolean): List<JsonElement> = branches.mapNotNull { branch ->
            if (branch !is JsonObject || !supportsArgumentAssertions(branch)) null else match(branch, allowCoercion)
        }
        val native = matches(false)
        if (native.isEmpty() && coerce && branches.filterIsInstance<JsonObject>().any { branch ->
                !supportsArgumentAssertions(branch) && match(branch, true) != null
            }) invalidArgument(path, "нельзя однозначно преобразовать значение при неподдерживаемых ограничениях схемы; передайте значение нужного типа")
        val candidates = if (native.isNotEmpty() || !coerce) native else matches(true)
        if (candidates.isEmpty()) invalidArgument(path, "значение не соответствует допустимому типу")
        if (keyword == "oneOf" && candidates.size != 1) invalidArgument(path, "тип значения неоднозначен")
        val distinct = distinctArgumentValues(candidates)
        if (distinct.size != 1) invalidArgument(path, "преобразование значения неоднозначно")
        return distinct.single()
    }
    val type = schema["type"]
    if (type is JsonArray) {
        val types = type.filterIsInstance<JsonPrimitive>().map { it.content }.distinct()
        fun matches(allowCoercion: Boolean) = types.mapNotNull { candidate ->
            try { normalizeArgument(value, JsonObject(schema + ("type" to JsonPrimitive(candidate))), path, allowCoercion) }
            catch (_: ToolArgumentException) { null }
        }
        val native = matches(false)
        val candidates = distinctArgumentValues(if (native.isNotEmpty() || !coerce) native else matches(true))
        if (candidates.size != 1) invalidArgument(path, "значение не соответствует однозначному допустимому типу")
        return candidates.single()
    }
    val normalized = when ((type as? JsonPrimitive)?.contentOrNull) {
        "null" -> value.takeIf { it is JsonNull } ?: invalidArgument(path, "ожидается null")
        "string" -> value.takeIf { it is JsonPrimitive && it !is JsonNull && it.isString }
            ?: invalidArgument(path, "ожидается строка")
        "boolean" -> {
            val primitive = value as? JsonPrimitive ?: invalidArgument(path, "ожидается true или false")
            if (primitive is JsonNull) invalidArgument(path, "ожидается true или false")
            if (!primitive.isString && primitive.booleanOrNull != null) primitive
            else if (coerce && primitive.isString && primitive.content in setOf("true", "false"))
                JsonPrimitive(primitive.content == "true")
            else invalidArgument(path, "ожидается true или false")
        }
        "number", "integer" -> {
            val primitive = value as? JsonPrimitive ?: invalidArgument(path, "ожидается число")
            if (primitive is JsonNull || (primitive.isString && !coerce) || !jsonNumber.matches(primitive.content))
                invalidArgument(path, "ожидается число в формате JSON")
            val number = try { BigDecimal(primitive.content) } catch (_: NumberFormatException) {
                invalidArgument(path, "ожидается конечное число")
            }
            if (!number.toDouble().isFinite()) invalidArgument(path, "ожидается конечное число")
            if ((type as JsonPrimitive).content == "integer") {
                // Reject fractions before setScale inside toBigIntegerExact can expand a huge negative exponent.
                if (number.stripTrailingZeros().scale() > 0) invalidArgument(path, "ожидается целое число")
                val integer = try { number.toBigIntegerExact() } catch (_: ArithmeticException) {
                    invalidArgument(path, "ожидается целое число")
                }
                argumentJson.parseToJsonElement(integer.toString())
            } else argumentJson.parseToJsonElement(primitive.content)
        }
        "object" -> if (value is JsonObject) value else invalidArgument(path, "ожидается JSON-объект")
        "array" -> if (value is JsonArray) value else invalidArgument(path, "ожидается массив")
        else -> value // Unconstrained/freeform JSON stays unchanged.
    }
    val result = when (normalized) {
        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.forEach { field ->
                val name = (field as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (name !in normalized) invalidArgument("$path.$name", "обязательный параметр отсутствует")
            }
            JsonObject(normalized.mapValues { (name, child) ->
                val declared = properties[name] as? JsonObject
                when {
                    declared != null -> normalizeArgument(child, declared, "$path.$name", coerce)
                    schema["additionalProperties"] is JsonObject -> normalizeArgument(child,
                        schema.getValue("additionalProperties") as JsonObject, "$path.$name", coerce)
                    schema["additionalProperties"] == JsonPrimitive(false) -> invalidArgument("$path.$name", "неизвестный параметр")
                    else -> child
                }
            })
        }
        is JsonArray -> {
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema == null) normalized else JsonArray(normalized.mapIndexed { index, item ->
                normalizeArgument(item, itemSchema, "$path[$index]", coerce)
            })
        }
        else -> normalized
    }
    (schema["enum"] as? JsonArray)?.let { choices ->
        if (choices.none { sameArgumentValue(it, result) }) invalidArgument(path, "значение отсутствует в списке допустимых вариантов")
    }
    schema["const"]?.let { if (!sameArgumentValue(it, result)) invalidArgument(path, "ожидается фиксированное значение") }
    return result
}

private fun sameArgumentValue(first: JsonElement, second: JsonElement): Boolean {
    if (first == second) return true
    if (first is JsonObject && second is JsonObject) return first.keys == second.keys &&
        first.all { (key, value) -> sameArgumentValue(value, second.getValue(key)) }
    if (first is JsonArray && second is JsonArray) return first.size == second.size &&
        first.indices.all { sameArgumentValue(first[it], second[it]) }
    if (first is JsonPrimitive && second is JsonPrimitive && !first.isString && !second.isString &&
        jsonNumber.matches(first.content) && jsonNumber.matches(second.content)) {
        return try { BigDecimal(first.content).compareTo(BigDecimal(second.content)) == 0 }
        catch (_: NumberFormatException) { false }
    }
    return false
}

private fun distinctArgumentValues(values: List<JsonElement>): List<JsonElement> = values.fold(emptyList()) { unique, value ->
    if (unique.any { sameArgumentValue(it, value) }) unique else unique + value
}

/** Only assertions implemented above may decide union validity; annotations do not decide it. */
private fun supportsArgumentAssertions(schema: JsonObject): Boolean {
    val assertions = setOf("type", "properties", "required", "items", "additionalProperties", "enum", "const", "anyOf", "oneOf")
    val annotations = setOf("title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly",
        "\$schema", "\$id", "\$comment", "\$defs", "definitions")
    if (schema.keys.any { it !in assertions && it !in annotations }) return false
    val properties = schema["properties"]
    if (properties != null && (properties !is JsonObject || properties.values.any {
            it !is JsonObject || !supportsArgumentAssertions(it)
        })) return false
    for (key in listOf("items", "additionalProperties")) {
        when (val child = schema[key]) {
            null -> Unit
            is JsonObject -> if (!supportsArgumentAssertions(child)) return false
            is JsonPrimitive -> if (key != "additionalProperties" || child.isString || child.booleanOrNull == null) return false
            else -> return false
        }
    }
    for (key in listOf("anyOf", "oneOf")) {
        val children = schema[key] ?: continue
        if (children !is JsonArray || children.any { it !is JsonObject || !supportsArgumentAssertions(it) }) return false
    }
    return true
}
