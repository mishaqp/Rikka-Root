// Adapted from ExTV/rikkahub-agent (AGPL v3).
package me.rerere.rikkahub.data.ai.mcp.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import okhttp3.Dns
import java.net.UnknownHostException

class McpUrlGuardTest {
    private val publicResolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("8.8.8.8")) }
    private fun check(url: String, headless: Boolean): McpUrlGuard.Result = McpUrlGuard.check(url, headless, publicResolver)


    @Test fun `https url accepted in any context`() {
        assertTrue(check("https://example.com/sse", headless = false) is McpUrlGuard.Result.Ok)
        assertTrue(check("https://example.com/sse", headless = true) is McpUrlGuard.Result.Ok)
    }

    @Test fun `http public host accepted in any context`() {
        assertTrue(check("http://example.com:9090/sse", headless = false) is McpUrlGuard.Result.Ok)
        assertTrue(check("http://example.com:9090/sse", headless = true) is McpUrlGuard.Result.Ok)
    }

    @Test fun `non-http schemes rejected`() {
        for (bad in listOf(
            "file:///etc/passwd",
            "ftp://example.com/path",
            "data:text/plain,abc",
            "javascript:alert(1)",
            "ws://example.com/sse",
            "tcp://example.com",
        )) {
            val r = check(bad, headless = false)
            assertTrue("expected reject for $bad", r is McpUrlGuard.Result.Reject)
            val rej = r as McpUrlGuard.Result.Reject
            assertEquals("unsupported_url_scheme", rej.error)
        }
    }

    @Test fun `loopback rejected in headless context`() {
        for (lp in listOf(
            "http://localhost:9090/sse",
            "http://127.0.0.1:9090/sse",
            "http://127.0.42.99:9090/sse",
            "http://[::1]:9090/sse",
        )) {
            val r = check(lp, headless = true)
            assertTrue("expected reject for $lp in headless", r is McpUrlGuard.Result.Reject)
            assertEquals("private_address_forbidden", (r as McpUrlGuard.Result.Reject).error)
        }
    }

    @Test fun `loopback rejected in interactive context too`() {
        for (lp in listOf(
            "http://localhost:9090/sse",
            "http://127.0.0.1:9090/sse",
            "http://127.0.42.99:9090/sse",
            "http://[::1]:9090/sse",
        )) {
            assertTrue("expected reject for $lp interactive", check(lp, headless = false) is McpUrlGuard.Result.Reject)
        }
    }

    @Test fun `empty url rejected`() {
        val r = check("", headless = false)
        assertEquals("invalid_url", (r as McpUrlGuard.Result.Reject).error)
    }

    @Test fun `malformed url rejected`() {
        val r = check("ht!tp:::nonsense", headless = false)
        assertTrue(r is McpUrlGuard.Result.Reject)
    }

    @Test fun `url missing host rejected`() {
        // "http:///path" has no authority — URI parses but host is null.
        val r = check("http:///nohost", headless = false)
        assertTrue(r is McpUrlGuard.Result.Reject)
    }

    @Test fun `isLoopback recognizes all forms`() {
        assertTrue(McpUrlGuard.isLoopback("localhost"))
        assertTrue(McpUrlGuard.isLoopback("127.0.0.1"))
        assertTrue(McpUrlGuard.isLoopback("127.255.255.255"))
        assertTrue(McpUrlGuard.isLoopback("::1"))
    }

    @Test fun `isLoopback does not over-match`() {
        for (host in listOf(
            "127.example.com",
            "example.com",
            "192.168.1.1",
            "10.0.0.1",
            "myserver.local",
            "127a.0.0.1",
        )) {
            assertEquals(false, McpUrlGuard.isLoopback(host))
        }
    }

    /**
     * Bypass forms identified by the Phase 10 audit pass — every one of these MUST be
     * caught by the loopback guard so the LLM can't ferry them past the headless block.
     */
    @Test fun `isLoopback catches audit-bypass forms`() {
        assertTrue("trailing dot",
            McpUrlGuard.isLoopback("localhost."))
        assertTrue("uppercase",
            McpUrlGuard.isLoopback("LOCALHOST"))
        assertTrue("any-local 0_0_0_0",
            McpUrlGuard.isLoopback("0.0.0.0"))
        assertTrue("long-form IPv6 loopback",
            McpUrlGuard.isLoopback("0:0:0:0:0:0:0:1"))
        assertTrue("IPv4-mapped IPv6 loopback",
            McpUrlGuard.isLoopback("::ffff:127.0.0.1"))
        assertTrue("bracketed long-form IPv6",
            McpUrlGuard.isLoopback("[0:0:0:0:0:0:0:1]"))
    }

    @Test fun `audit bypass urls rejected in headless context`() {
        for (lp in listOf(
            "http://localhost.:9090/sse",
            "http://0.0.0.0:9090/sse",
            "http://[0:0:0:0:0:0:0:1]:9090/sse",
            "http://[::ffff:127.0.0.1]:9090/sse",
        )) {
            val r = check(lp, headless = true)
            assertTrue(
                "expected reject for audit-bypass url $lp",
                r is McpUrlGuard.Result.Reject,
            )
        }
    }

    @Test fun `private and special IPv4 IPv6 addresses are rejected in every context`() {
        for (literal in listOf("10.1.2.3", "192.168.2.1", "172.16.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "127.1", "2130706433", "224.0.0.1", "255.255.255.255", "198.18.0.1",
            "[::]", "[::1]", "[fc00::1]", "[fd00::1]", "[fe80::1]", "[::ffff:192.168.1.1]",
            "[64:ff9b::a00:1]", "[2002:a00:1::]") ) {
            val raw = literal.removePrefix("[").removeSuffix("]")
            val resolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(raw)) }
            for (headless in listOf(false,true)) {
                assertTrue(literal, McpUrlGuard.check("https://$literal/mcp", headless, resolver) is McpUrlGuard.Result.Reject)
            }
        }
    }

    @Test fun `DNS aliases and mixed public private answers are rejected`() {
        val resolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")) }
        assertTrue(McpUrlGuard.check("https://public-looking.example/mcp", false, resolver) is McpUrlGuard.Result.Reject)
        assertTrue(McpUrlGuard.check("https://public-looking.example/mcp", true, resolver) is McpUrlGuard.Result.Reject)
    }

    @Test fun `unresolved hosts fail closed`() {
        assertTrue(McpUrlGuard.check("https://unknown.example/mcp", false) { throw UnknownHostException() } is McpUrlGuard.Result.Reject)
        assertTrue(McpUrlGuard.check("https://unknown.example/mcp", false) { emptyList() } is McpUrlGuard.Result.Reject)
    }

    @Test fun `credentials in URL and invalid ports rejected`() {
        for (url in listOf("https://user:password@example.com/mcp", "https://example.com/mcp?token=secret", "https://example.com/mcp#token", "https://example.com:99999/mcp")) {
            assertTrue(url, check(url, false) is McpUrlGuard.Result.Reject)
        }
    }

    @Test fun `actual DNS lookup rejects rebinding before transport`() {
        var local = false
        val dns = McpUrlGuard.guardedDns(Dns { listOf(InetAddress.getByName(if (local) "192.168.1.1" else "8.8.8.8")) })
        assertEquals("8.8.8.8", dns.lookup("same.example").single().hostAddress)
        local = true
        try {
            dns.lookup("same.example")
            throw AssertionError("private lookup must fail")
        } catch (_: UnknownHostException) { }
    }

    @Test fun `global unicast IPv6 allowed`() {
        assertTrue(McpUrlGuard.check("https://[2606:4700:4700::1111]/mcp", false) { listOf(InetAddress.getByName("2606:4700:4700::1111")) } is McpUrlGuard.Result.Ok)
    }
}
