package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class KimiProviderSettingTest {
    @Test
    fun `Kimi account provider round trips without credentials in settings`() {
        val json = Json { encodeDefaults = true }
        val setting = json.decodeFromString<ProviderSetting>("""{"type":"kimi_code","name":"Kimi Code","models":[{"modelId":"kimi-for-coding"}]}""")
        assertEquals("Kimi Code", setting.name)
        assertFalse(setting.enabled)
        assertTrue(setting.builtIn)
        val encoded = json.encodeToString<ProviderSetting>(setting)
        assertTrue(encoded.contains("\"type\":\"kimi_code\""))
        assertFalse(encoded.contains("accessToken"))
        assertFalse(encoded.contains("refreshToken"))
        assertFalse(encoded.contains("apiKey"))
        assertEquals(setting, json.decodeFromString<ProviderSetting>(encoded))
    }
}
