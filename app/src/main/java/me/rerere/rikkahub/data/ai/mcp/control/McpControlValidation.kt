// Adapted from ExTV/rikkahub-agent (AGPL v3); API 2.5.6, approvals, secure storage and Russian UI.
package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

/**
 * Argument-validation helpers for the mcp_* tools. Returns either [Ok] with a typed
 * payload, or [Reject] with a stable error code + human-readable detail. The error code
 * is what the LLM matches on; the detail is what the user sees in the approval prompt or
 * the LLM uses to pick a recovery path.
 *
 * Kept separate from McpControlTools so each tool's argument parser stays small and the
 * test surface stays focused.
 */
object McpControlValidation {

    const val MAX_NAME_LENGTH = 60
    const val MAX_HEADERS = 32

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Reject(val error: String, val detail: String) : Result<Nothing>()
    }

    /**
     * Reject names that are blank, oversize, or duplicate an existing server's name
     * (case-insensitive). When [excludingId] is non-null, that server's own current name
     * is permitted — used by mcp_update so renaming to the same name doesn't false-trip.
     */
    fun validateName(
        candidate: String,
        existingServers: List<McpServerConfig>,
        excludingId: String? = null,
    ): Result<String> {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return Result.Reject("invalid_name", "name обязателен и не должен быть пустым")
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return Result.Reject(
                "invalid_name",
                "name длиннее $MAX_NAME_LENGTH символов (${trimmed.length})"
            )
        }
        // Upstream 2.5.6 Root's existing registration and MCP settings accept only this
        // alphabet. Keep it here too, otherwise an accepted add breaks the next generation.
        if (!trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            return Result.Reject("invalid_name", "Название MCP должно содержать только латинские буквы и цифры, как в настройках MCP.")
        }
        val lowered = trimmed.lowercase()
        val collision = existingServers.firstOrNull { srv ->
            srv.commonOptions.name.trim().lowercase() == lowered &&
                srv.id.toString() != excludingId
        }
        if (collision != null) {
            return Result.Reject(
                "name_already_in_use",
                "name '${collision.commonOptions.name}' уже используется сервером ${collision.id}"
            )
        }
        return Result.Ok(trimmed)
    }

    /**
     * Reject header lists that exceed the soft cap, contain CR/LF (header-injection guard),
     * or have empty / non-token-char header names. Header VALUES are not scanned beyond
     * CR/LF — the bytes matter and over-sanitising would break legitimate auth schemes.
     */
    fun validateHeaders(headers: List<Pair<String, String>>): Result<List<Pair<String, String>>> {
        if (headers.size > MAX_HEADERS) {
            return Result.Reject(
                "too_many_headers",
                "Заголовков больше $MAX_HEADERS (${headers.size})"
            )
        }
        for ((name, value) in headers) {
            if (name.isBlank()) {
                return Result.Reject("invalid_header_name", "Имя заголовка не должно быть пустым")
            }
            if (!isValidHttpToken(name)) {
                return Result.Reject(
                    "invalid_header_name",
                    "Имя заголовка '$name' содержит символы, запрещённые RFC 7230"
                )
            }
            if (name.contains('\r') || name.contains('\n')) {
                return Result.Reject(
                    "invalid_header_name",
                    "Имя заголовка '$name' содержит CR или LF; инъекция заголовка запрещена"
                )
            }
            if (value.contains('\r') || value.contains('\n')) {
                return Result.Reject(
                    "invalid_header_value",
                    "Значение заголовка '$name' содержит CR или LF; инъекция заголовка запрещена"
                )
            }
        }
        return Result.Ok(headers)
    }

    /** Mandatory Root storage rule: models may pass aliases, never raw passwords/tokens. */
    fun validateSecureHeaders(headers: List<Pair<String, String>>): Result<List<Pair<String, String>>> {
        val syntax = validateHeaders(headers)
        if (syntax is Result.Reject) return syntax
        for ((name, value) in headers) {
            if (value.startsWith(McpControlSecretStore.REFERENCE_PREFIX) && !McpControlSecretStore.isReference(value)) {
                return Result.Reject("invalid_secret_reference", "Некорректная ссылка AndroidKeyStore на заголовок MCP.")
            }
            if (McpHeaderRedactor.isSensitive(name) && value.isNotEmpty() && !McpControlSecretStore.isReference(value)) {
                return Result.Reject("plaintext_secret_forbidden", "Не передавайте секретные заголовки в чат. Введите значение в защищённом поле настроек MCP и используйте ссылку AndroidKeyStore.")
            }
        }
        return Result.Ok(headers)
    }

    /**
     * RFC 7230 token chars: !#$%&'*+-.^_`|~ plus ALPHA and DIGIT. We're permissive here —
     * the goal is to reject obvious injection attempts like " " or ":" in header names,
     * not to be a strict RFC linter.
     */
    private fun isValidHttpToken(name: String): Boolean {
        if (name.isEmpty()) return false
        for (ch in name) {
            val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
            val isDigit = ch in '0'..'9'
            val isToken = ch in "!#$%&'*+-.^_`|~"
            if (!(isLetter || isDigit || isToken)) return false
        }
        return true
    }
}
