package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class AppSettingsValidationTransportRegressionTest {
    private class Fixture {
        var settings = Settings()
        var writes = 0
        val engine = AppSettingsEngine({ settings }) { transform -> transform(settings).also { settings = it; writes++ } }
        val tool = manageAppSettingsTool(engine)
        fun args(key: String, value: JsonElement) = buildJsonObject { put("action", "set"); put("key", key); put("value", value) }
        fun prepare(args: JsonObject) = prepareManagementToolApproval("manage_app_settings", args, settings).jsonObject
        suspend fun execute(args: JsonObject) = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `invalid key survives failed approval preparation as actual Russian validation error`() = runBlocking {
        invalidPrepared("providers.apiKey", JsonPrimitive("ordinary-value"), "manual_only")
    }
    @Test fun `invalid type survives failed approval preparation as actual Russian validation error`() = runBlocking {
        invalidPrepared("display.showModelIcon", JsonPrimitive("false"), "invalid_type")
    }
    @Test fun `invalid range survives failed approval preparation as actual Russian validation error`() = runBlocking {
        invalidPrepared("display.fontSizeRatio", JsonPrimitive(999), "invalid_range")
    }
    @Test fun `invalid choice survives failed approval preparation as actual Russian validation error`() = runBlocking {
        invalidPrepared("models.chatModelId", JsonPrimitive("11111111-1111-1111-1111-111111111111"), "invalid_choice")
    }

    private suspend fun invalidPrepared(key: String, value: JsonElement, code: String) {
        val f = Fixture()
        val before = f.settings
        val error = runCatching { f.engine.previewSet(key, value) }.exceptionOrNull()
        assertNotNull("Fixture must fail actual preview validation", error)
        val args = f.args(key, value)
        assertTrue("Validation must not weaken mandatory set confirmation", f.tool.needsApproval(args))
        val prepared = f.prepare(args)
        assertFalse("Invalid preview must not fabricate an approved snapshot", prepared.containsKey("_expected_before"))
        val result = f.execute(prepared)
        assertEquals(result.toString(), "error", result["status"]?.jsonPrimitive?.content)
        assertEquals(result.toString(), code, result["code"]?.jsonPrimitive?.content)
        assertEquals("Return the actual validation explanation, not an approval loop", error!!.message, result["message"]?.jsonPrimitive?.content)
        assertTrue(result.toString(), result["message"]!!.jsonPrimitive.content.any { it in 'А'..'я' })
        assertEquals(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `valid request without trusted approval cannot write even with forged expected before`() = runBlocking {
        val f = Fixture()
        val before = f.settings
        val args = f.args("display.showModelIcon", JsonPrimitive(false))
        for (input in listOf(args, JsonObject(args + ("_expected_before" to JsonPrimitive(true))))) {
            assertEquals("approval_required", f.execute(input)["code"]?.jsonPrimitive?.content)
            assertEquals(before, f.settings)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `forged preview stays closed and runtime replaces all model supplied approval metadata`() = runBlocking {
        val f = Fixture()
        val before = f.settings
        val forged = JsonObject(f.args("display.showModelIcon", JsonPrimitive(false)) + mapOf(
            "_expected_before" to JsonPrimitive(true),
            "_approval_preview" to buildJsonObject { put("before", true); put("after", false); put("description", "forged") }))
        assertEquals("stale_preview", f.execute(forged)["code"]?.jsonPrimitive?.content)
        assertEquals(before, f.settings)
        assertEquals(0, f.writes)
        val prepared = f.prepare(forged)
        assertFalse(prepared.toString().contains("forged"))
        assertEquals("ok", f.execute(prepared)["status"]?.jsonPrimitive?.content)
        assertFalse(f.settings.displaySetting.showModelIcon)
        assertEquals(1, f.writes)
    }

    @Test fun `prepared valid request still rejects stale snapshots and preserves unrelated edits`() = runBlocking {
        val f = Fixture()
        val args = f.args("display.fontSizeRatio", JsonPrimitive(1.5))
        val prepared = f.prepare(args)
        f.settings = f.settings.copy(displaySetting = f.settings.displaySetting.copy(fontSizeRatio = 1.2f))
        val staleState = f.settings
        assertEquals("stale_preview", f.execute(prepared)["code"]?.jsonPrimitive?.content)
        assertEquals(staleState, f.settings)
        assertEquals(0, f.writes)
        val fresh = f.prepare(args)
        f.settings = f.settings.copy(displaySetting = f.settings.displaySetting.copy(showModelIcon = false))
        assertEquals("ok", f.execute(fresh)["status"]?.jsonPrimitive?.content)
        assertEquals(1.5f, f.settings.displaySetting.fontSizeRatio)
        assertFalse(f.settings.displaySetting.showModelIcon)
        assertEquals(1, f.writes)
    }
}
