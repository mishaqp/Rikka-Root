package me.rerere.rikkahub.data.ai.tools.local

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

    @Test fun `downloads and MCP results count as web content`() {
        assertTrue(WebContentGuard.isClientReader("download_file"))
        assertTrue(WebContentGuard.isClientReader("mcp__12345678_fetch__get"))
        assertFalse(WebContentGuard.isClientReader("read_file"))
    }
}
