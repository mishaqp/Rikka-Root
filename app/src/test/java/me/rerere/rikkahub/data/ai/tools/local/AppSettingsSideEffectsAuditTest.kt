package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AppSettingsSideEffectsAuditTest {
    private fun assertChange(preview: AppSettingsChangePreview, key: String, before: JsonElement, after: JsonElement) {
        val changes = preview.toJson()["changes"] as? JsonArray
        val change = changes?.map { it.jsonObject }?.singleOrNull { it["key"] == JsonPrimitive(key) }
        assertNotNull("Approval must disclose the actual change to $key", change)
        assertEquals(before, change!!["before"])
        assertEquals(after, change["after"])
    }

    @Test fun `navigation preview discloses coupled jumper change`() {
        val settings = Settings(displaySetting = DisplaySetting(messageNavButtonsMode = MessageNavButtonsMode.ALWAYS, showMessageJumper = true))
        val preview = previewAppSettingsChange(settings, "display.messageNavButtonsMode", JsonPrimitive("never"))
        assertChange(preview, "display.messageNavButtonsMode", JsonPrimitive("always"), JsonPrimitive("never"))
        assertChange(preview, "display.showMessageJumper", JsonPrimitive(true), JsonPrimitive(false))
        assertTrue("Visible approval must mention the secondary setting", preview.description.contains("Кнопки перехода между сообщениями"))
    }

    @Test fun `tool collapse preview discloses inherited thinking reset`() {
        val settings = Settings(displaySetting = DisplaySetting(autoCollapseToolSteps = false, collapseThinkingSteps = false))
        val preview = previewAppSettingsChange(settings, "display.autoCollapseToolSteps", JsonPrimitive(true))
        assertChange(preview, "display.collapseThinkingSteps", JsonPrimitive(false), JsonNull)
        assertTrue(preview.description.contains("Сворачивать этапы рассуждений"))
    }

    @Test fun `side effect changed after approval is stale and never written`() = runBlocking {
        var settings = Settings(displaySetting = DisplaySetting(autoCollapseToolSteps = false, collapseThinkingSteps = false))
        var writes = 0
        val engine = AppSettingsEngine({ settings }) { transform -> settings = transform(settings); writes++; settings }
        val preview = engine.previewSet("display.autoCollapseToolSteps", JsonPrimitive(true))
        settings = settings.copy(displaySetting = settings.displaySetting.copy(collapseThinkingSteps = true))
        val result = engine.applyApproved(preview)
        assertEquals("stale_preview", result["code"]?.jsonPrimitive?.content)
        assertEquals(0, writes)
        assertEquals(true, settings.displaySetting.collapseThinkingSteps)
        assertFalse(settings.displaySetting.autoCollapseToolSteps)
    }

    @Test fun `tool execution checks the frozen side effect snapshot from approval`() = runBlocking {
        var settings = Settings(displaySetting = DisplaySetting(autoCollapseToolSteps = false, collapseThinkingSteps = false))
        var writes = 0
        val engine = AppSettingsEngine({ settings }) { transform -> settings = transform(settings); writes++; settings }
        val preview = engine.previewSet("display.autoCollapseToolSteps", JsonPrimitive(true))
        val args = buildJsonObject {
            put("action", "set"); put("key", preview.key); put("value", preview.after)
            put("_expected_before", preview.before); put("_approval_preview", preview.toJson())
        }
        settings = settings.copy(displaySetting = settings.displaySetting.copy(collapseThinkingSteps = true))
        val text = manageAppSettingsTool(engine).execute(args).single() as UIMessagePart.Text
        val result = Json.parseToJsonElement(text.text).jsonObject
        assertEquals("stale_preview", result["code"]?.jsonPrimitive?.content)
        assertEquals(0, writes)
        assertEquals(true, settings.displaySetting.collapseThinkingSteps)
    }
}
