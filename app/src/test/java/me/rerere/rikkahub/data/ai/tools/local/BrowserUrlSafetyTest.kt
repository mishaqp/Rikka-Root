package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.WebContentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlSafetyTest {
    @Test fun `scheme-less hosts become https and other inputs are unchanged`() {
        assertEquals("https://github.com/owner/repo", normalizeBrowserToolUrl("github.com/owner/repo"))
        assertEquals("https://host:8080/path", normalizeBrowserToolUrl("host:8080/path"))
        assertEquals("https://localhost", normalizeBrowserToolUrl("localhost"))
        assertEquals("https://example.com", normalizeBrowserToolUrl("https://example.com"))
        assertEquals("about:blank", normalizeBrowserToolUrl("about:blank"))
        assertEquals("file:///etc/passwd", normalizeBrowserToolUrl("file:///etc/passwd"))
        assertEquals("javascript:alert(1)", normalizeBrowserToolUrl("javascript:alert(1)"))
    }

    @Test fun `phone and home network addresses are local`() {
        for (url in listOf("http://192.168.1.1/", "192.168.0.1", "http://127.0.0.1:8080", "http://10.0.0.5",
            "http://[::1]/", "http://localhost:3000", "http://router.lan/", "http://nas.local", "http://printer/",
            "http://172.20.1.1", "http://169.254.1.1")) assertTrue(url, isLocalNetworkBrowserUrl(url))
        for (url in listOf("https://github.com", "https://8.8.8.8/", "about:blank", "", null))
            assertFalse(url.toString(), isLocalNetworkBrowserUrl(url))
    }

    @Test fun `browser_open asks before local network pages only`() {
        assertTrue(ToolPermissionPolicy.sideEffect("browser_open", buildJsonObject { put("url", "http://192.168.1.1") }))
        assertFalse(ToolPermissionPolicy.sideEffect("browser_open", buildJsonObject { put("url", "https://example.com") }))
    }

    @Test fun `downloads and MCP results count as web content`() {
        assertTrue(WebContentGuard.isClientReader("download_file"))
        assertTrue(WebContentGuard.isClientReader("mcp__12345678_fetch__get"))
        assertFalse(WebContentGuard.isClientReader("read_file"))
    }
}
