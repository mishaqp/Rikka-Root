package me.rerere.rikkahub.data.ai.tools.local

import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ToolMediaPlaybackService
import me.rerere.rikkahub.service.ToolMediaStatus
import org.junit.Assert.*
import org.junit.Test

class MediaPlayerToolMessagesTest {
    @Test fun noSessionStatusKeepsAllStateFieldsAndExplainsHowToStartPlayback() = runBlocking {
        mockkObject(ToolMediaPlaybackService.Companion)
        try {
            coEvery { ToolMediaPlaybackService.status() } returns ToolMediaStatus()
            val parts = getMediaStatusTool().execute(buildJsonObject {})
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertFalse(result.getValue("success").jsonPrimitive.boolean)
            assertEquals("no_session", result.getValue("state").jsonPrimitive.content)
            assertFalse(result.getValue("playing").jsonPrimitive.boolean)
            assertFalse(result.getValue("session_active").jsonPrimitive.boolean)
            assertEquals(0L, result.getValue("position_ms").jsonPrimitive.long)
            assertEquals(0L, result.getValue("duration_ms").jsonPrimitive.long)
            val message = result.getValue("message").jsonPrimitive.content
            assertTrue(message, message.contains("медиасеанс", ignoreCase = true))
            assertTrue(message, message.contains("play_media"))
            assertFalse(result.containsKey("error"))
            assertFalse(result.containsKey("source"))
        } finally {
            unmockkObject(ToolMediaPlaybackService.Companion)
        }
    }
}
