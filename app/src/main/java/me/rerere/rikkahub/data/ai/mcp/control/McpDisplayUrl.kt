package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.reliability.SecretRedactor
import java.net.URI

/** Legacy configs can contain credentials anywhere in a URL; never display them verbatim. */
internal fun redactedMcpUrl(value: String): String = runCatching {
    val uri = URI(value)
    require(uri.host != null && uri.scheme?.lowercase() in setOf("http", "https"))
    val safe = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    SecretRedactor.redact(safe) +
        (if (uri.rawQuery != null) "?[скрыто]" else "") +
        (if (uri.rawFragment != null) "#[скрыто]" else "")
}.getOrDefault("[некорректный URL MCP скрыт]")

/** Diagnostics can embed legacy URLs too, including values from transport exceptions. */
internal fun redactedMcpText(value: String): String = SecretRedactor.redact(
    Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).replace(value) { redactedMcpUrl(it.value) }
)
