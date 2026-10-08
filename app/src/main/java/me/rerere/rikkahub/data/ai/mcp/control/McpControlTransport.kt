// Mandatory SSRF integration of Agent MCP control into Rikka-Root's upstream 2.5.6 transports.
package me.rerere.rikkahub.data.ai.mcp.control

import okhttp3.OkHttpClient

/** Manual configurations keep their existing client. Controlled ones guard every request,
 * including SSE endpoints supplied by a remote server, and every actual DNS lookup. */
internal fun guardedMcpHttpClient(base: OkHttpClient, publicAddressOnly: Boolean): OkHttpClient {
    if (!publicAddressOnly) return base
    return base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(McpUrlGuard.guardedDns(base.dns))
        .addInterceptor { chain ->
            McpUrlGuard.validateTarget(chain.request().url.toString())
            chain.proceed(chain.request())
        }
        .build()
}
