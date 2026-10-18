package me.rerere.rikkahub.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSecretScrubberTest {
    @Test fun `settings sent to the browser contain no keys or passwords`() {
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(name = "Мой провайдер", apiKey = "sk-secret-provider-key")),
            webServerAccessPassword = "web-password-123",
        )
        val scrubbed = WebSecretScrubber.scrub(JsonInstant.encodeToJsonElement(settings)).toString()
        assertFalse(scrubbed.contains("sk-secret-provider-key"))
        assertFalse(scrubbed.contains("web-password-123"))
        assertTrue("Ordinary values stay", scrubbed.contains("Мой провайдер"))
    }

    @Test fun `custom auth header values are blanked and other headers kept`() {
        val json = Json.parseToJsonElement(
            """{"customHeaders":[{"name":"Authorization","value":"Bearer abc"},{"name":"X-Title","value":"Rikka"}],"maxTokens":4096}""")
        val scrubbed = WebSecretScrubber.scrub(json).toString()
        assertFalse(scrubbed.contains("Bearer abc"))
        assertTrue(scrubbed.contains("Rikka"))
        assertTrue(scrubbed.contains("4096"))
    }
}
