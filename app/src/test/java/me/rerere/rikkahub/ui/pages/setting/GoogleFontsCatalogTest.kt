package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.*
import org.junit.Test

class GoogleFontsCatalogTest {
    @Test fun `catalog imports regular native faces and preserves searchable character subsets`() {
        val fonts = GoogleFontsRepository.parseCatalog("""{"items":[
            {"family":"Zeta","files":{"regular":"http://fonts.gstatic.com/s/zeta/face.ttf"},"subsets":["latin","cyrillic"]},
            {"family":"Alpha","files":{"regular":"https://fonts.gstatic.com/s/alpha/face.ttf"},"subsets":["greek"]},
            {"family":"No Regular","files":{"700":"https://fonts.gstatic.com/s/bold/face.ttf"},"subsets":["latin"]},
            {"family":"Wrong Host","files":{"regular":"https://example.com/font.ttf"},"subsets":["latin"]},
            {"family":"Web Font","files":{"regular":"https://fonts.gstatic.com/font.woff2"},"subsets":["latin"]}
        ]}""")
        assertEquals(listOf("Alpha", "Zeta"), fonts.map { it.family })
        assertEquals(listOf("latin", "cyrillic"), fonts.last().subsets)
        assertEquals("https://fonts.gstatic.com/s/zeta/face.ttf", fonts.last().url)
        assertEquals("noto-sans", GoogleFontEntry("Noto Sans", fonts.first().url, emptyList()).packageName)
    }

    @Test fun `catalog rejects fonts that cannot enter the managed native font pipeline`() {
        for (url in listOf("http://fonts.gstatic.com/font.ttf", "https://fonts.gstatic.com:8443/font.ttf",
            "https://user:password@fonts.gstatic.com/font.ttf", "https://fonts.gstatic.com/font.otf",
            "https://fonts.gstatic.com.evil.test/font.ttf")) assertFalse(url, GoogleFontsRepository.isFontUrl(url))
        assertTrue(GoogleFontsRepository.validFontHeader(byteArrayOf(0, 1, 0, 0)))
        assertTrue(GoogleFontsRepository.validFontHeader("OTTO".toByteArray()))
        assertFalse(GoogleFontsRepository.validFontHeader("<html>".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { GoogleFontsRepository.parseCatalog("""{"items":[]}""") }
    }
}
