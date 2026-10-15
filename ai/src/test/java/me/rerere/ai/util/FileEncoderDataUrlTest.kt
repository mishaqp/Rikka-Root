package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class FileEncoderDataUrlTest {
    @Test
    fun `inline image omits data URL prefix when provider requests raw base64`() {
        val image = UIMessagePart.Image("data:image/png;base64,aGVsbG8=")

        val encoded = image.encodeBase64(withPrefix = false).getOrThrow()

        assertEquals("image/png", encoded.mimeType)
        assertEquals("aGVsbG8=", encoded.base64)
    }

    @Test
    fun `OpenAI image keeps complete data URL`() {
        val url = "data:image/png;base64,aGVsbG8="

        assertEquals(url, UIMessagePart.Image(url).encodeBase64().getOrThrow().base64)
    }
}
