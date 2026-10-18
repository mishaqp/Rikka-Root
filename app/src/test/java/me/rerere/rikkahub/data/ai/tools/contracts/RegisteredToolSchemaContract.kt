package me.rerere.rikkahub.data.ai.tools.contracts

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.core.parametersOrEmpty

/** Independent structural checks on the actual serialized model-facing schema. */
internal object RegisteredToolSchemaContract {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val russian = Regex("[А-Яа-яЁё]")
    private val name = Regex("[A-Za-z0-9_-]{1,64}")
    fun schema(tool: Tool): JsonObject = json.encodeToJsonElement(tool.parametersOrEmpty()).jsonObject
    fun hasRussian(text: String?): Boolean = !text.isNullOrBlank() && russian.containsMatchIn(text)

    fun names(tools: List<Tool>): List<String> = buildList {
        tools.filterNot { name.matches(it.name) }.forEach { add("${it.name}: имя не соответствует ${name.pattern}") }
        tools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (key, count) -> add("$key: зарегистрирован $count раз") }
    }

    fun schemaIssues(schema: JsonObject): List<String> = buildList {
        if (schema["type"] != JsonPrimitive("object")) add("$: корень должен иметь type=object")
        if (schema["properties"] !is JsonObject) add("$: отсутствует объект properties")
        inspect(schema, "$", schema, parameter = false, languageOnly = false, issues = this)
    }

    fun parameterLanguageIssues(schema: JsonObject): List<String> = buildList {
        inspect(schema, "$", schema, parameter = false, languageOnly = true, issues = this)
    }

    private fun inspect(
        element: JsonElement, path: String, root: JsonObject, parameter: Boolean,
        languageOnly: Boolean, issues: MutableList<String>,
    ) {
        fun issue(message: String) { issues += "$path: $message" }
        val node = element as? JsonObject
        if (node == null) {
            if (!languageOnly && element !is JsonPrimitive) issue("схема должна быть объектом")
            if (!languageOnly && (element as? JsonPrimitive)?.booleanOrNull == null) issue("ожидается объект или boolean schema")
            return
        }
        if (parameter && languageOnly && !hasRussian((node["description"] as? JsonPrimitive)?.contentOrNull)) {
            issue("нужно непустое русское описание параметра")
        }
        if (!languageOnly) {
            val typeNode = node["type"]
            val declared = when (typeNode) {
                is JsonPrimitive -> listOf(typeNode.takeIf { it.isString }?.content)
                is JsonArray -> typeNode.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                null -> emptyList()
                else -> listOf(null)
            }
            if (typeNode != null && (declared.isEmpty() || declared.any { it !in types } || declared.distinct().size != declared.size)) {
                issue("неверный JSON Schema type: $typeNode")
            }
            if (parameter && typeNode == null && node.keys.none { it in setOf("anyOf", "oneOf", "allOf", "\$ref") }) {
                issue("у параметра нет явного type, композиции или \$ref")
            }
            val properties = node["properties"] as? JsonObject
            if ("properties" in node && properties == null) issue("properties должен быть объектом")
            node["required"]?.let { required ->
                val array = required as? JsonArray
                val keys = array?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (keys == null || keys.any { it == null } || keys.distinct().size != keys.size) issue("required должен содержать уникальные строки")
                keys?.forEach { key -> if (key != null && key !in properties.orEmpty()) issue("required ссылается на отсутствующий параметр $key") }
            }
            if ("array" in declared && node["items"] == null && node["prefixItems"] == null) issue("у массива нет схемы items/prefixItems")
            node["enum"]?.let { values ->
                if (values !is JsonArray || values.isEmpty() || values.distinct().size != values.size) issue("enum должен быть непустым массивом уникальных значений")
                else if (declared.isNotEmpty()) values.forEach { value ->
                    if (declared.none { type -> accepts(type, value) }) issue("enum значение $value не соответствует $typeNode")
                }
            }
            node["\$ref"]?.let { reference ->
                val ref = (reference as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (ref == null || !ref.startsWith("#/")) issue("\$ref должен ссылаться на локальное определение")
                else {
                    val resolved = ref.removePrefix("#/").split('/').fold(root as JsonElement?) { current, part ->
                        (current as? JsonObject)?.get(part.replace("~1", "/").replace("~0", "~"))
                    }
                    if (resolved == null) issue("\$ref не разрешается: $ref")
                }
            }
        }
        (node["properties"] as? JsonObject)?.forEach { (key, child) ->
            inspect(child, "$path.properties.$key", root, parameter = true, languageOnly, issues)
        }
        listOf("anyOf", "oneOf", "allOf", "prefixItems").forEach { keyword ->
            node[keyword]?.let { value ->
                val array = value as? JsonArray
                if (!languageOnly && (array == null || array.isEmpty())) issue("$keyword должен быть непустым массивом схем")
                array?.forEachIndexed { index, child -> inspect(child, "$path.$keyword[$index]", root, false, languageOnly, issues) }
            }
        }
        listOf("items", "additionalProperties", "contains", "not", "if", "then", "else").forEach { keyword ->
            node[keyword]?.let { inspect(it, "$path.$keyword", root, false, languageOnly, issues) }
        }
        listOf("\$defs", "definitions", "patternProperties").forEach { keyword ->
            (node[keyword] as? JsonObject)?.forEach { (key, child) -> inspect(child, "$path.$keyword.$key", root, false, languageOnly, issues) }
        }
    }

    private fun accepts(type: String?, value: JsonElement): Boolean = when (type) {
        "null" -> value == JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        else -> false
    }

    /** Observe the provider contract without copying its keyword-filter implementation. */
    fun propertyProjection(schema: JsonObject): JsonElement = buildJsonObject {
        schema["type"]?.let { put("type", it) }
        schema["description"]?.let { put("description", it) }
        schema["required"]?.let { put("required", it) }
        (schema["properties"] as? JsonObject)?.let { properties ->
            put("properties", JsonObject(properties.mapValues { (_, value) -> propertyProjection(value.jsonObject) }))
        }
        (schema["items"] as? JsonObject)?.let { put("items", propertyProjection(it)) }
        listOf("anyOf", "oneOf", "allOf", "prefixItems").forEach { key ->
            (schema[key] as? JsonArray)?.let { items -> put(key, JsonArray(items.map { propertyProjection(it.jsonObject) })) }
        }
    }
}
