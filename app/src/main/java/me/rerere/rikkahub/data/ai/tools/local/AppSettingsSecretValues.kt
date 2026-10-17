package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder

internal val APP_SETTINGS_SECRET_MASK = JsonPrimitive("••••")
internal const val APP_SETTINGS_SECRET_EDIT_BLOCKED = "_secret_edit_blocked"

/** Deliberately matches the management credential-value rules, without LONG_BASE64. */
private fun appSettingsCredentialValue(value: String): Boolean {
    val normalized = value.trim()
    if (SensitiveContentDetector.scan(normalized).any {
        it == SensitiveContentDetector.Category.API_KEY || it == SensitiveContentDetector.Category.JWT
    }) return true
    if (Regex("""^(Bearer|Basic)\s+\S+""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return true
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*@").containsMatchIn(normalized)) return true
    val uri = runCatching { URI(normalized) }.getOrNull()
    if (uri?.rawUserInfo != null) return true
    val components = listOfNotNull(uri?.rawQuery,
        normalized.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() },
        normalized.substringAfter('#', "").takeIf { it.isNotEmpty() })
    return components.any { component -> component.split('&').any { part ->
        val key = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrDefault(part.substringBefore('='))
            .lowercase().filter(Char::isLetterOrDigit)
        key in setOf("auth", "pwd", "key", "sig", "signature", "accesskey") || key.endsWith("token") ||
            listOf("authorization", "authentication", "apikey", "secret", "password", "passwd", "credential", "cookie", "accesstoken", "refreshtoken", "authtoken", "privatekey", "bearer", "clientkey", "signature").any { it in key }
    } }
}

internal fun appSettingsContainsCredential(value: JsonElement): Boolean = when (value) {
    is JsonPrimitive -> value.isString && appSettingsCredentialValue(value.content)
    is JsonArray -> value.any(::appSettingsContainsCredential)
    is JsonObject -> value.values.any(::appSettingsContainsCredential)
    else -> false
}

/** Public tool values only: never pass a Settings/private assembly through this boundary. */
internal fun publicAppSettingValue(value: JsonElement): JsonElement = when (value) {
    is JsonPrimitive -> if (appSettingsContainsCredential(value)) APP_SETTINGS_SECRET_MASK else value
    is JsonArray -> JsonArray(value.map(::publicAppSettingValue))
    is JsonObject -> JsonObject(value.mapValues { (_, child) -> publicAppSettingValue(child) })
    else -> value
}

internal fun publicAppSettingText(value: String): String =
    if (appSettingsCredentialValue(value)) APP_SETTINGS_SECRET_MASK.content else value
