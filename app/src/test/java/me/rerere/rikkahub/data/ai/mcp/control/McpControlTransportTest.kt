package me.rerere.rikkahub.data.ai.mcp.control

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class McpControlTransportTest {
    @Test fun `manual servers retain original DNS redirects and client instance`() {
        val base = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        assertSame(base, guardedMcpHttpClient(base, publicAddressOnly = false))
        assertTrue(base.followRedirects)
        assertTrue(base.followSslRedirects)
    }

    @Test fun `controlled transport disables redirects and guards actual DNS lookups`() {
        val base = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val client = guardedMcpHttpClient(base, publicAddressOnly = true)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(base.interceptors.size + 1, client.interceptors.size)
        assertTrue(runCatching { client.dns.lookup("remote.example") }.isFailure)
    }

    @Test fun `server supplied SSE endpoint cannot target local address and may keep session query`() {
        assertTrue(runCatching { McpUrlGuard.validateTarget("http://127.0.0.1/post?sessionId=1") }.isFailure)
        assertTrue(runCatching {
            McpUrlGuard.validateTarget("https://example.com/post?sessionId=1") { listOf(InetAddress.getByName("8.8.8.8")) }
        }.isSuccess)
        assertTrue(runCatching {
            McpUrlGuard.validateTarget("https://alias.example/post?sessionId=1") { listOf(InetAddress.getByName("192.168.1.1")) }
        }.isFailure)
    }
}
