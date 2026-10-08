// Adapted from ExTV/rikkahub-agent (AGPL v3); API 2.5.6, approvals, secure storage and Russian UI.
package me.rerere.rikkahub.data.ai.mcp.control

import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Agent URL guard adapted to Root's explicit SSRF requirement: all mcp_control calls use
 * public addresses only. Resolve every host at validation and again at actual DNS lookup.
 * Controlled transports disable redirects; existing manually configured servers keep their
 * original client. headless remains in the signature for source compatibility.
 */
object McpUrlGuard {

    sealed class Result {
        data object Ok : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    /**
     * Validate a candidate MCP URL.
     *
     * @param url The URL string from the LLM tool argument.
     * @param headless Kept for source compatibility; Root blocks local addresses in every context.
     */
    @Suppress("UNUSED_PARAMETER")
    fun check(url: String, headless: Boolean, resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): Result =
        checkTarget(url, resolver, allowProtocolQuery = false)

    private fun checkTarget(url: String, resolver: (String) -> List<InetAddress>, allowProtocolQuery: Boolean): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Result.Reject("invalid_url", "URL пустой")
        }
        val parsed = try {
            URI(trimmed)
        } catch (t: Throwable) {
            return Result.Reject("invalid_url", "Не удалось разобрать URL (${t.javaClass.simpleName})")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Reject(
                "unsupported_url_scheme",
                "Поддерживаются только http и https; указано '${scheme ?: "нет схемы"}'"
            )
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) {
            return Result.Reject("invalid_url", "В URL отсутствует имя сервера")
        }
        // Root's explicit security requirement: MCP control must never target local services,
        // including interactive calls, DNS aliases and mapped IPv6 addresses.
        if (parsed.rawUserInfo != null || (!allowProtocolQuery && parsed.rawQuery != null) || parsed.rawFragment != null) {
            return Result.Reject("url_credentials_forbidden", "Не помещайте логин, пароль или токен в URL. Используйте защищённый заголовок из настроек MCP; query и fragment не поддерживаются.")
        }
        if (parsed.port != -1 && parsed.port !in 1..65535) {
            return Result.Reject("invalid_url", "Порт должен быть от 1 до 65535")
        }
        val normalized = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (isLoopback(normalized) || normalized.endsWith(".localhost") || normalized == "local" ||
            normalized.endsWith(".local") || normalized.endsWith(".internal") || normalized.contains('%')) {
            return Result.Reject(
                "private_address_forbidden",
                "Локальные адреса MCP запрещены; используйте публичный сервер"
            )
        }
        val addresses = try { resolver(normalized) } catch (_: Exception) {
            return Result.Reject("host_not_found", "Не удалось проверить DNS сервера MCP")
        }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            return Result.Reject("private_address_forbidden", "Сервер MCP должен разрешаться только в публичные IP-адреса; локальные, частные и служебные адреса запрещены")
        }
        return Result.Ok
    }

    /**
     * True if [host] is a loopback address. Catches the three forms the LLM is likely to emit:
     *  - The literal string `localhost`
     *  - IPv4 loopback range `127.0.0.0/8`
     *  - IPv6 loopback `::1` (with or without zone id)
     *
     * This literal helper performs no DNS lookup. check() and guardedDns() separately
     * resolve and validate hostnames to enforce Root's SSRF rule.
     */
    fun isLoopback(host: String): Boolean {
        // URI.host returns IPv6 hosts with brackets on some JDKs (`[::1]`) and without on
        // others (`::1`) — strip both forms before comparing so we don't depend on which
        // platform we're on. Also strip a trailing dot — `localhost.` resolves identical
        // to `localhost` but the regex below wouldn't match it without normalisation.
        val normalized = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "localhost") return true

        // For anything that LOOKS like an IP literal (only digits/dots, or contains a
        // colon), defer to InetAddress for canonical detection. Critically we only call
        // getByName on strings that are unambiguously IP literals — so no DNS resolution
        // happens. This catches the four documented audit-finding bypasses:
        //   - 0:0:0:0:0:0:0:1 (long-form IPv6 loopback)
        //   - ::ffff:127.0.0.1 (IPv4-mapped IPv6 loopback)
        //   - 0.0.0.0 (any-local — binds locally, treat as loopback for guard purposes)
        //   - 127.x.x.x in any decimal-octet form
        val looksLikeIpLiteral = normalized.contains(':') ||
            normalized.matches(Regex("""[0-9.]+"""))
        if (looksLikeIpLiteral) {
            val addr = runCatching { java.net.InetAddress.getByName(normalized) }.getOrNull()
            if (addr != null && (addr.isLoopbackAddress || addr.isAnyLocalAddress)) return true
        }
        return false
    }

    /** Guard at the actual OkHttp DNS lookup, so changing DNS cannot bypass add-time validation. */
    fun guardedDns(delegate: Dns = Dns.SYSTEM): Dns = Dns { hostname ->
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("MCP: локальный или служебный адрес запрещён")
        }
        addresses
    }

    /** Run before opening a controlled transport; its client must also disable all redirects. */
    fun validateTarget(url: String, resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) {
        val result = checkTarget(url, resolver, allowProtocolQuery = true)
        if (result is Result.Reject) throw IllegalArgumentException("${result.error}: ${result.detail}")
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 192 && b == 0) || (a == 192 && b == 2) ||
                (a == 198 && b in 18..19) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113))
        }
        // Only currently allocated IPv6 global-unicast space; ULA/link-local/NAT64 and
        // transition prefixes can otherwise map to an internal IPv4 destination.
        return bytes.size == 16 && (bytes[0] and 0xe0) == 0x20 &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0)
    }
}
