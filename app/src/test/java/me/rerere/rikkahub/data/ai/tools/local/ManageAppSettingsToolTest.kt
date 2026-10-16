package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class ManageAppSettingsToolTest {
    @Test fun `set always requests approval and missing approval snapshot cannot write`() = runBlocking {
        var settings = Settings()
        val engine = AppSettingsEngine({ settings }) { settings = it(settings); settings }
        val tool = manageAppSettingsTool(engine)
        val args = buildJsonObject { put("action", "set"); put("key", "display.showModelIcon"); put("value", false) }
        assertTrue(tool.needsApproval(args))
        assertTrue(tool.needsApproval(buildJsonObject { put("action", "unknown") }))
        assertTrue(tool.needsApproval(JsonNull))
        val blocked = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("approval_required", blocked["code"]!!.jsonPrimitive.content)
        assertTrue(settings.displaySetting.showModelIcon)
        val preview = engine.previewSet("display.showModelIcon", JsonPrimitive(false))
        val prepared = JsonObject(args + mapOf("_expected_before" to preview.before, "_approval_preview" to preview.toJson()))
        val saved = Json.parseToJsonElement((tool.execute(prepared).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ok", saved["status"]!!.jsonPrimitive.content)
        assertFalse(settings.displaySetting.showModelIcon)
    }

    @Test fun `get and list never require mutation approval or expose secret roots`() = runBlocking {
        val tool = manageAppSettingsTool(AppSettingsEngine({ Settings() }) { error("read wrote settings") })
        for (args in listOf(
            buildJsonObject { put("action", "list_sections") },
            buildJsonObject { put("action", "get"); put("section", "models") },
            buildJsonObject { put("action", "get"); put("section", "providers") },
        )) {
            assertFalse(tool.needsApproval(args))
            val response = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
            if (args["section"] == JsonPrimitive("providers")) {
                assertEquals(JsonPrimitive("error"), response["status"])
                assertEquals(JsonPrimitive("manual_only"), response["code"])
                assertEquals(JsonPrimitive("••••"), response["value"])
                assertEquals(JsonPrimitive("Это можно изменить только вручную в настройках"), response["message"])
            } else {
                assertEquals(JsonPrimitive("ok"), response["status"])
            }
        }
    }
}
