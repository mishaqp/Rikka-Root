package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class AppSettingsSecretsAuditTest {
    private val apiKey = "sk-" + "a".repeat(32)
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.c2lnbmF0dXJlLXNlY3JldA"
    private val mask = JsonPrimitive("••••")

    private class Store(var current: Settings) {
        var writes = 0
        val engine = AppSettingsEngine({ current }) { transform ->
            current = transform(current)
            writes++
            current
        }
    }

    private fun assertNoSecrets(value: Any?) {
        val text = value.toString()
        assertFalse("API key escaped the management boundary", text.contains(apiKey))
        assertFalse("JWT escaped the management boundary", text.contains(jwt))
    }

    @Test fun `get masks credentials in allowed prompt fields and labels`() {
        val store = Store(Settings(titlePrompt = "Instruction $apiKey", suggestionPrompt = jwt))
        for (key in listOf("models.titlePrompt", "models.suggestionPrompt")) {
            val response = store.engine.get(key = key)
            assertNoSecrets(response)
            assertEquals(mask, response["setting"]!!.jsonObject["value"])
            assertEquals(mask, response["setting"]!!.jsonObject["value_label"])
        }
        assertNoSecrets(store.engine.get(section = "models"))
        assertEquals("Instruction $apiKey", store.current.titlePrompt)
        assertEquals(0, store.writes)
    }

    @Test fun `masked prompt preview and runtime expected before never contain raw credentials`() {
        val settings = Settings(titlePrompt = apiKey)
        val preview = previewAppSettingsChange(settings, "models.titlePrompt", mask)
        assertEquals(mask, preview.before)
        assertEquals(mask, preview.after)
        assertNoSecrets(preview.toJson())
        assertNoSecrets(preview.description)
        val prepared = prepareManagementToolApproval("manage_app_settings", buildJsonObject {
            put("action", "set"); put("key", "models.titlePrompt"); put("value", mask)
        }, settings).jsonObject
        assertEquals(mask, prepared["_expected_before"])
        assertNoSecrets(prepared)
    }

    @Test fun `creating or replacing or clearing secret prompts is manual only`() = runBlocking {
        val plain = Store(Settings(titlePrompt = "ordinary"))
        for (credential in listOf(apiKey, jwt, "Bearer opaque-value", "https://host.test/path?api_key=opaque-value")) {
            val result = plain.engine.set("models.titlePrompt", JsonPrimitive(credential), JsonPrimitive("ordinary"))
            assertEquals("manual_only", result["code"]?.jsonPrimitive?.content)
            assertNoSecrets(result)
        }
        assertEquals("ordinary", plain.current.titlePrompt)
        assertEquals(0, plain.writes)
        for (original in listOf(apiKey, jwt)) {
            val store = Store(Settings(titlePrompt = original))
            for (replacement in listOf(JsonPrimitive("changed"), JsonPrimitive(""), JsonNull)) {
                val result = store.engine.set("models.titlePrompt", replacement, mask)
                assertEquals("manual_only", result["code"]?.jsonPrimitive?.content)
                assertNoSecrets(result)
                assertEquals(original, store.current.titlePrompt)
            }
            assertEquals(0, store.writes)
        }
    }

    @Test fun `rejected credential create request cannot leak through runtime approval or become a masked write`() = runBlocking {
        val store = Store(Settings(titlePrompt = "ordinary"))
        val prepared = prepareManagementToolApproval("manage_app_settings", buildJsonObject {
            put("action", "set"); put("key", "models.titlePrompt"); put("value", apiKey)
        }, store.current).jsonObject
        assertNoSecrets(prepared)
        val text = manageAppSettingsTool(store.engine).execute(prepared).single() as me.rerere.ai.ui.UIMessagePart.Text
        val result = Json.parseToJsonElement(text.text).jsonObject
        assertEquals("manual_only", result["code"]?.jsonPrimitive?.content)
        assertEquals("ordinary", store.current.titlePrompt)
        assertEquals(0, store.writes)
    }

    @Test fun `masked roundtrip preserves the current raw secret without writing the mask`() = runBlocking {
        val store = Store(Settings(titlePrompt = apiKey))
        val preview = store.engine.previewSet("models.titlePrompt", mask)
        val result = store.engine.set(preview.key, preview.after, preview.before, preview.toJson())
        assertEquals("ok", result["status"]?.jsonPrimitive?.content)
        assertEquals(apiKey, store.current.titlePrompt)
        assertEquals(0, store.writes)
        assertNoSecrets(result)
    }

    @Test fun `unrelated and coupled display updates preserve hidden values exactly`() = runBlocking {
        val store = Store(Settings(titlePrompt = apiKey, displaySetting = DisplaySetting(
            userNickname = jwt, showTokenUsage = true, autoCollapseToolSteps = false, collapseThinkingSteps = false,
        )))
        for (key in listOf("display.showTokenUsage", "display.autoCollapseToolSteps")) {
            val value = JsonPrimitive(key != "display.showTokenUsage")
            val preview = store.engine.previewSet(key, value)
            val result = store.engine.set(key, value, preview.before, preview.toJson())
            assertEquals("ok", result["status"]?.jsonPrimitive?.content)
            assertNoSecrets(result)
            assertEquals(apiKey, store.current.titlePrompt)
            assertEquals(jwt, store.current.displaySetting.userNickname)
        }
        assertFalse(store.current.displaySetting.showTokenUsage)
        assertNull(store.current.displaySetting.collapseThinkingSteps)
    }

    @Test fun `credential appearing between preflight and transaction cannot be overwritten`() = runBlocking {
        var settings = Settings(titlePrompt = "ordinary")
        var writes = 0
        val engine = AppSettingsEngine({ settings }) { transform ->
            settings = settings.copy(titlePrompt = apiKey)
            settings = transform(settings)
            writes++
            settings
        }
        val preview = engine.previewSet("models.titlePrompt", JsonPrimitive("updated"))
        val result = engine.set(preview.key, preview.after, preview.before, preview.toJson())
        assertEquals("manual_only", result["code"]?.jsonPrimitive?.content)
        assertEquals(apiKey, settings.titlePrompt)
        assertEquals(0, writes)
        assertNoSecrets(result)
    }

    @Test fun `secret rotation during masked approval preserves the latest secret`() = runBlocking {
        val store = Store(Settings(titlePrompt = apiKey))
        val preview = store.engine.previewSet("models.titlePrompt", mask)
        store.current = store.current.copy(titlePrompt = jwt)
        val result = store.engine.set(preview.key, preview.after, preview.before, preview.toJson())
        assertEquals("ok", result["status"]?.jsonPrimitive?.content)
        assertEquals(jwt, store.current.titlePrompt)
        assertEquals(0, store.writes)
        assertNoSecrets(result)
    }

    @Test fun `catalog and selected labels mask credentials without damaging model IDs UUIDs or schema keys`() = runBlocking {
        val ordinaryModelId = "vendor-model-" + "a".repeat(64)
        val model = Model(modelId = ordinaryModelId, displayName = apiKey)
        val secretId = Model(modelId = jwt, displayName = "")
        val provider = ProviderSetting.OpenAI(name = jwt, models = listOf(model, secretId))
        val store = Store(Settings(providers = listOf(provider), chatModelId = model.id))
        val section = store.engine.get(section = "models")
        assertNoSecrets(section)
        val listed = section["available_models"]!!.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive(model.id.toString()), listed["id"])
        assertEquals(JsonPrimitive(ordinaryModelId), listed["model_id"])
        assertEquals(mask, listed["provider_name"])
        val selected = store.engine.get(key = "models.chatModelId")["setting"]!!.jsonObject
        assertEquals(JsonPrimitive("models.chatModelId"), selected["key"])
        assertEquals(JsonPrimitive(model.id.toString()), selected["value"])
        assertTrue(selected["choices"]!!.jsonArray.contains(JsonPrimitive(model.id.toString())))
        assertNoSecrets(selected)
        val preview = store.engine.previewSet("models.fastModelId", JsonPrimitive(model.id.toString()))
        assertNoSecrets(preview.toJson())
        val result = store.engine.set(preview.key, preview.after, preview.before, preview.toJson())
        assertEquals("ok", result["status"]?.jsonPrimitive?.content)
        assertEquals(model.id, store.current.fastModelId)
        assertEquals(apiKey, store.current.providers.first().models.first().displayName)
        assertEquals(jwt, store.current.providers.first().name)
    }

    @Test fun `ordinary prompts remain editable and long ordinary identifiers are not credentials`() = runBlocking {
        val ordinary = "model_reference_" + "a".repeat(64)
        val store = Store(Settings(titlePrompt = "initial"))
        val preview = store.engine.previewSet("models.titlePrompt", JsonPrimitive(ordinary))
        val result = store.engine.set(preview.key, preview.after, preview.before, preview.toJson())
        assertEquals("ok", result["status"]?.jsonPrimitive?.content)
        assertEquals(ordinary, store.current.titlePrompt)
        assertEquals(JsonPrimitive(ordinary), result["setting"]!!.jsonObject["value"])
        assertEquals(1, store.writes)
    }
}
