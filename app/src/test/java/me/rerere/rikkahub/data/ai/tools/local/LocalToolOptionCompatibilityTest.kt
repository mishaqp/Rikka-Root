package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class LocalToolOptionCompatibilityTest {
    private val agentNames = listOf("battery", "audio_info", "telephony_info", "wifi_info", "sensors",
        "storage_info", "toast", "notification", "share")
    private val packageFOptions = linkedMapOf(
        "termux" to LocalToolOption.Termux,
        "ssh" to LocalToolOption.Ssh,
        "mcp_control" to LocalToolOption.McpControl,
        "external_automation" to LocalToolOption.ExternalAutomation,
    )

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

    @Test fun packageFRestoresExactlyTheAgentBackupNames() {
        packageFOptions.forEach { (name, expected) ->
            val encoded = "{\"type\":\"$name\"}"
            assertEquals(expected, Json.decodeFromString<LocalToolOption>(encoded))
            assertEquals(encoded, Json.encodeToString<LocalToolOption>(expected))
        }
    }

    @Test fun packageFIsOffForNewAndRestoredAssistants() {
        for (assistant in listOf(Assistant(), Json.decodeFromString<Assistant>("{}"))) {
            assertTrue(assistant.localTools.none { it in packageFOptions.values })
            assertEquals(listOf(LocalToolOption.TimeInfo), assistant.localTools)
        }
    }
}
