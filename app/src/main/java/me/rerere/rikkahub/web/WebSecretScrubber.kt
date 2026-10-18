package me.rerere.rikkahub.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The web UI never edits keys or passwords, so settings sent to browsers carry none:
 * provider API keys, backup credentials, proxy and web-server passwords (the JWT secret),
 * and values of custom auth headers are blanked. Numbers and booleans are kept.
 */
internal object WebSecretScrubber {
    private val SECRET_NAME_PARTS = listOf("apikey", "password", "secret", "token", "accesskey", "privatekey", "credential")
    private val AUTH_HEADER_PARTS = listOf("authorization", "api-key", "apikey", "token", "secret", "cookie")

    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> scrubObject(element)
        is JsonArray -> JsonArray(element.map(::scrub))
        else -> element
    }

    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT).replace("_", "").replace("-", "")
        return SECRET_NAME_PARTS.any { it in lower }
    }

    private fun scrubObject(obj: JsonObject): JsonObject {
        // {name: "Authorization", value: "Bearer …"} pairs used for custom headers.
        val headerName = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase(Locale.ROOT)
        val authHeader = headerName != null && "value" in obj && AUTH_HEADER_PARTS.any { it in headerName }
        return JsonObject(obj.mapValues { (key, value) ->
            when {
                value is JsonPrimitive && value.isString && value.content.isNotEmpty() &&
                    (isSecretName(key) || (authHeader && key == "value")) -> JsonPrimitive("")
                else -> scrub(value)
            }
        })
    }
}
