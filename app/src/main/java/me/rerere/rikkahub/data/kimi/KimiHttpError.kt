package me.rerere.rikkahub.data.kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.reliability.SecretRedactor
import okhttp3.Response

/** Generation errors may explain a rejected request, but must never expose account credentials. */
internal fun kimiGenerationError(response: Response, tokens: KimiTokens, json: Json): KimiApiException {
    val detail = runCatching {
        val data = json.parseToJsonElement(response.peekBody(16 * 1024L).string()) as? JsonObject
            ?: return@runCatching null
        val error = data["error"] as? JsonObject
        val message = error?.text("message") ?: data.text("message") ?: data.text("detail") ?: data.text("error")
            ?: return@runCatching null
        var safe = message
        listOf(tokens.accessToken, tokens.refreshToken).filter(String::isNotEmpty).sortedByDescending(String::length).forEach {
            safe = safe.replace(it, "[redacted]")
        }
        SecretRedactor.redact(safe).replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(2_048).takeIf(String::isNotBlank)
    }.getOrNull()
    return KimiApiException(response.code, detail = detail)
}
