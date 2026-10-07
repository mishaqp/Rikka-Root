package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CodexProviderSettingTest {
    @Test
    fun `Codex serialization preserves provider headers and models without credentials`() {
        val json = Json { encodeDefaults = true }
        val setting: ProviderSetting = ProviderSetting.Codex(
            customHeaders = listOf(CustomHeader("X-Custom", "kept")),
            models = listOf(Model(modelId = "gpt-5.4-codex")),
        )
        val encoded = json.encodeToString<ProviderSetting>(setting)
        assertTrue(encoded.contains("\"type\":\"codex\""))
        assertFalse(encoded.contains("accessToken"))
        assertFalse(encoded.contains("refreshToken"))
        assertEquals(setting, json.decodeFromString<ProviderSetting>(encoded))
    }

    @Test
    fun `Codex copy follows canonical provider header contract`() {
        val original = ProviderSetting.Codex(customHeaders = listOf(CustomHeader("A", "1")))
        val headers = listOf(CustomHeader("B", "2"))
        val copied = original.copyProvider(customHeaders = headers, name = "ChatGPT") as ProviderSetting.Codex
        assertEquals(original.id, copied.id)
        assertEquals(headers, copied.customHeaders)
        assertEquals("ChatGPT", copied.name)
        assertFalse(copied.enabled)
    }
}
