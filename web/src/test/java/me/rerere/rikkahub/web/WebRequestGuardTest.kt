package me.rerere.rikkahub.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRequestGuardTest {
    @Test fun `own address and same-origin browser requests are allowed`() {
        for (host in listOf("192.168.1.5:8080", "127.0.0.1:8080", "localhost:8080", "[::1]:8080", "rikka.local:8080")) {
            assertTrue(host, isAllowedWebRequest(host, null))
            assertTrue(host, isAllowedWebRequest(host, "http://$host"))
        }
    }

    @Test fun `another website open in the browser is refused`() {
        assertFalse(isAllowedWebRequest("127.0.0.1:8080", "https://evil.example"))
        assertFalse(isAllowedWebRequest("192.168.1.5:8080", "http://192.168.1.5:9999"))
        assertFalse(isAllowedWebRequest("127.0.0.1:8080", "null"))
    }

    @Test fun `dns rebinding host names and missing host are refused`() {
        assertFalse(isAllowedWebRequest("evil.example:8080", "http://evil.example:8080"))
        assertFalse(isAllowedWebRequest("evil.example:8080", null))
        assertFalse(isAllowedWebRequest(null, null))
        assertFalse(isAllowedWebRequest("", null))
    }
}
