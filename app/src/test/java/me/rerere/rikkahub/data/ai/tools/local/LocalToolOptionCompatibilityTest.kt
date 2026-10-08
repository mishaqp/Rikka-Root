package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class LocalToolOptionCompatibilityTest {
    private val agentNames = listOf("battery", "audio_info", "telephony_info", "wifi_info", "sensors",
        "storage_info", "toast", "notification", "share")

    @Test fun agentBackupOptionsRoundTripWithoutRenaming() {
        for (name in agentNames) {
            val source = "{\"type\":\"$name\"}"
            val option = Json.decodeFromString<LocalToolOption>(source)
            assertEquals(source, Json.encodeToString<LocalToolOption>(option))
        }
    }

    @Test fun newAndLegacyAssistantsDoNotEnablePackageA() {
        val defaults = Assistant().localTools
        val restored = Json.decodeFromString<Assistant>("{}").localTools
        assertEquals(listOf(LocalToolOption.TimeInfo), defaults)
        assertEquals(defaults, restored)
        for (name in agentNames) {
            assertFalse(Json.decodeFromString<LocalToolOption>("{\"type\":\"$name\"}") in restored)
        }
    }
}
