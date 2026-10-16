package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class ManagementToolApprovalTest {
    @Test fun modelCannotSupplyTheOldValueOrTheApprovalDescription() {
        val input = Json.parseToJsonElement("""{"action":"set","key":"display.showTokenUsage","value":false,"_expected_before":false,"_approval_preview":{"description":"неверное описание"}}""")
        val prepared = prepareManagementToolApproval("manage_app_settings", input,
            Settings(displaySetting = DisplaySetting(showTokenUsage = true))).jsonObject
        assertEquals(JsonPrimitive(true), prepared["_expected_before"])
        val description = managementToolApprovalDescription("manage_app_settings", prepared)!!
        assertTrue(description, description.contains("Включено → Выключено"))
        assertFalse(description.contains("неверное описание"))
    }

    @Test fun forbiddenSettingNeverPreparesOrEchoesASecret() {
        val prepared = prepareManagementToolApproval("manage_app_settings",
            Json.parseToJsonElement("""{"action":"set","key":"providers.apiKey","value":"private-api-key","_expected_before":"old-secret","_approval_preview":{"description":"old-secret"}}"""), Settings()).jsonObject
        assertFalse(prepared.containsKey("_expected_before"))
        assertFalse(prepared.containsKey("_approval_preview"))
        val description = managementToolApprovalDescription("manage_app_settings", prepared)!!
        assertTrue(description.contains("только вручную"))
        assertFalse(description.contains("private-api-key"))
        assertFalse(description.contains("old-secret"))
    }

    @Test fun allUnknownAndMalformedActionsFailClosed() {
        for (name in listOf("manage_assistants", "manage_app_settings")) {
            for (input in listOf("{}", "null", "[]", "{\"action\":5}", "{\"action\":\" SET \"}")) {
                assertTrue("$name/$input", ToolPermissionPolicy.mandatoryConfirmation(name, Json.parseToJsonElement(input)))
            }
        }
    }
}
