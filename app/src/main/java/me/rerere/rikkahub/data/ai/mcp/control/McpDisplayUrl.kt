package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.tools.local.SensitiveContentDetector
import me.rerere.rikkahub.reliability.SecretRedactor
import java.net.URI

/** Legacy configs can contain credentials anywhere in a URL; never display them verbatim. */
internal fun redactedMcpUrl(value: String): String = runCatching {
    val uri = URI(value)
    require(uri.host != null && uri.scheme?.lowercase() in setOf("http", "https"))
    val safe = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    maskMcpCredentialShapes(safe) +
        (if (uri.rawQuery != null) "?[скрыто]" else "") +
        (if (uri.rawFragment != null) "#[скрыто]" else "")
}.getOrDefault("[некорректный URL MCP скрыт]")

/** Diagnostics can embed legacy URLs too, including values from transport exceptions. */
internal fun redactedMcpText(value: String): String = maskMcpCredentialShapes(
    Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).replace(value) { redactedMcpUrl(it.value) }
)

/** Transport diagnostics can contain arbitrary credential bytes and raw authentication headers. */
internal fun redactedMcpDiagnosticText(value: String): String = SecretRedactor.redact(redactedMcpText(value))

/** Recognizable credentials in text, never generic long identifiers or short provider labels. */
internal fun maskMcpCredentialShapes(value: String): String =
    MCP_TEXT_BEARER_CREDENTIAL_SHAPE.replace(
        MCP_KNOWN_CREDENTIAL_SHAPE.replace(SensitiveContentDetector.maskApiAndJwt(value), "••••")
    ) { "${it.groupValues[1]} ••••" }

/** HTTP authentication syntax is meaningful for a header value, not for a display name. */
internal fun maskMcpHeaderCredentialShapes(value: String): String =
    MCP_AUTH_CREDENTIAL_SHAPE.replace(maskMcpCredentialShapes(value)) { "${it.groupValues[1]} ••••" }

/** Text and aliases use the narrow shape classifier. */
internal fun isMcpKnownCredentialValue(value: String): Boolean = maskMcpCredentialShapes(value) != value

/** Retain short Bearer/Basic header protection for input, legacy cleanup and origin changes. */
internal fun isMcpKnownHeaderCredentialValue(value: String): Boolean = maskMcpHeaderCredentialShapes(value) != value

/** Display placeholders are never usable as replacement header values or vault secrets. */
internal fun isMcpRedactionPlaceholder(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.equals("MASK", ignoreCase = true) || trimmed == "***" || trimmed.startsWith("…") ||
        value.contains("••••") || MCP_REDACTION_PLACEHOLDER.containsMatchIn(value)
}

// Underscores decorate remote names and header values; unlike word boundaries they must delimit known keys.
private val MCP_KNOWN_CREDENTIAL_SHAPE = Regex(
    """(?<![A-Za-z0-9])(?:(?:tp-|gsk_|xai-)[A-Za-z0-9._-]{20,}|sk-(?:proj|svcacct)-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,})(?![A-Za-z0-9])"""
)
private val MCP_TEXT_BEARER_CREDENTIAL_SHAPE = Regex("""(?i)\b(Bearer)[ \t]+[A-Za-z0-9._~+/-]{20,}={0,2}(?![A-Za-z0-9._~+/-])""")
private val MCP_AUTH_CREDENTIAL_SHAPE = Regex("""(?i)^(\s*(?:Bearer|Basic))\s+\S+""")
private val MCP_REDACTION_PLACEHOLDER = Regex("""(?i)\[(?:redacted[^\]]*|masked|mask|скрыто)\]""")
