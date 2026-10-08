package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test

class NativeIntentToolsTest {
    @Test fun onlyDeliberatePublicSchemesCanBeOpened() {
        listOf("https://example.com/a?q=б", "http://example.com", "mailto:user@example.com", "tel:+123", "geo:0,0?q=Москва", "market://details?id=org.example").forEach { assertEquals(it, safeNativeUrl(it)) }
        listOf("intent://x#Intent;end", "javascript:alert(1)", "file:///data/a", "content://private/x", "https://user:password@example.com", "https://", "https://example.com\n").forEach { invalid { safeNativeUrl(it) } }
    }
    @Test fun explicitComponentsCannotSmuggleIntentOrShellData() {
        assertEquals("org.example.Main", nativeActivityName("org.example", ".Main"))
        assertEquals("org.example.Main", nativeActivityName("org.example", "org.example.Main"))
        listOf("", "org.example;id", "org/example", "org.example\n").forEach { invalid { nativePackageName(it) } }
        listOf(".Main;id", "org.example/Main", "../Main", "x\n").forEach { invalid { nativeActivityName("org.example", it) } }
    }
    @Test fun reversedCalendarRangeIsRejected() {
        validateCalendarTimes(100, 200)
        validateCalendarTimes(null, 200)
        invalid { validateCalendarTimes(200, 100) }
        invalid { validateCalendarTimes(-1, 200) }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Unsafe argument accepted") } catch (_: IllegalArgumentException) { }
    }
}
