package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AppSettingsEngineTest {
    private class Store(var current: Settings = Settings()) {
        var writes = 0
        val engine = AppSettingsEngine({ current }) { transform ->
            current = transform(current)
            writes++
            current
        }

        suspend fun set(key: String, value: JsonElement, approve: suspend (AppSettingsChangePreview) -> Boolean): JsonObject {
            val preview = runCatching { engine.previewSet(key, value) }.getOrElse {
                return engine.set(key, value, null)
            }
            if (!approve(preview)) return buildJsonObject { put("status", "cancelled") }
            return engine.set(key, value, preview.before)
        }
    }

    @Test fun `ordinary setting waits for approval then saves without losing unrelated current changes`() = runBlocking {
        val store = Store(Settings(displaySetting = DisplaySetting(showTokenUsage = true)))
        val result = store.set("display.showTokenUsage", JsonPrimitive(false)) { preview ->
            assertEquals(0, store.writes)
            assertTrue(store.current.displaySetting.showTokenUsage)
            assertEquals(JsonPrimitive(true), preview.before)
            assertEquals(JsonPrimitive(false), preview.after)
            assertTrue(preview.description.contains("→"))
            store.current = store.current.copy(displaySetting = store.current.displaySetting.copy(userNickname = "новое имя"))
            true
        }
        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        assertFalse(store.current.displaySetting.showTokenUsage)
        assertEquals("новое имя", store.current.displaySetting.userNickname)
        assertEquals(1, store.writes)
    }

    @Test fun `approval denial never writes`() = runBlocking {
        val store = Store()
        val before = store.current
        val result = store.set("display.fontSizeRatio", JsonPrimitive(1.5)) { false }
        assertEquals("cancelled", result["status"]!!.jsonPrimitive.content)
        assertEquals(before, store.current)
        assertEquals(0, store.writes)
    }

    @Test fun `changed setting during approval is rejected rather than silently overwriting`() = runBlocking {
        val store = Store()
        val result = store.set("display.fontSizeRatio", JsonPrimitive(1.5)) {
            store.current = store.current.copy(displaySetting = store.current.displaySetting.copy(fontSizeRatio = 1.2f))
            true
        }
        assertEquals("stale_preview", result["code"]!!.jsonPrimitive.content)
        assertEquals(1.2f, store.current.displaySetting.fontSizeRatio)
        assertEquals(0, store.writes)
    }

    @Test fun `transaction checks actual persisted before value even if presentation cache remains stale`() = runBlocking {
        val cached = Settings(displaySetting = DisplaySetting(fontSizeRatio = 1f))
        var persisted = cached.copy(displaySetting = cached.displaySetting.copy(fontSizeRatio = 1.25f))
        val engine = AppSettingsEngine({ cached }) { transform -> persisted = transform(persisted); persisted }
        val result = engine.set("display.fontSizeRatio", JsonPrimitive(1.5), JsonPrimitive(1f))
        assertEquals("stale_preview", result["code"]!!.jsonPrimitive.content)
        assertEquals(1.25f, persisted.displaySetting.fontSizeRatio)
    }

    @Test fun `nullable values permit explicit inherited state and null before approval marker`() = runBlocking {
        val store = Store()
        assertNull(store.current.displaySetting.showUserName)
        val enabled = store.engine.set("display.showUserName", JsonPrimitive(true), JsonNull)
        assertEquals("ok", enabled["status"]!!.jsonPrimitive.content)
        assertEquals(true, store.current.displaySetting.showUserName)
        val inherited = store.engine.set("display.showUserName", JsonNull, JsonPrimitive(true))
        assertEquals("ok", inherited["status"]!!.jsonPrimitive.content)
        assertNull(store.current.displaySetting.showUserName)
    }

    @Test fun `enumerated appearance values validate and exact scalar style leaves cannot replace settings`() = runBlocking {
        val store = Store()
        val invalid = store.set("display.chatMessageBackgroundStyle", JsonPrimitive("injected-style")) { fail("invalid enum reached approval"); true }
        assertEquals("invalid_choice", invalid["code"]!!.jsonPrimitive.content)
        store.set("display.chatMessageBackgroundStyle", JsonPrimitive("frosted")) { true }
        store.set("display.userChatBubbleStyleOverrides.borderWidth", JsonPrimitive(2.5)) { true }
        assertEquals(2.5f, store.current.displaySetting.userChatBubbleStyleOverrides.borderWidth)
        val rejected = store.set("display.userChatBubbleStyleOverrides", buildJsonObject { put("borderWidth", 100); put("toolApprovals", false) }) { fail("object reached approval"); true }
        assertEquals("manual_only", rejected["code"]!!.jsonPrimitive.content)
        assertEquals(2.5f, store.current.displaySetting.userChatBubbleStyleOverrides.borderWidth)
    }

    @Test fun `numeric bounds follow current interface and reject wrong JSON types before approval`() = runBlocking {
        val store = Store()
        var approvals = 0
        for ((key, value) in listOf(
            "display.fontSizeRatio" to JsonPrimitive(2.1),
            "display.fontSizeRatio" to JsonPrimitive("1.5"),
            "display.userMessageCollapseChars" to JsonPrimitive(49),
            "display.codeBlockCollapseLines" to JsonPrimitive(1000),
            "display.autoScrollIdleSeconds" to JsonPrimitive(65),
            "display.pasteLongTextThreshold" to JsonPrimitive(99),
            "speech.defaultTTSPlaybackSpeed" to JsonPrimitive(2.1),
            "display.showTokenUsage" to JsonPrimitive("false"),
        )) {
            val result = store.set(key, value) { approvals++; true }
            assertEquals(key, "error", result["status"]!!.jsonPrimitive.content)
        }
        assertEquals(0, approvals)
        assertEquals(0, store.writes)
        store.set("display.userMessageCollapseLines", JsonPrimitive(50)) { true }
        store.set("display.pasteLongTextThreshold", JsonPrimitive(1_000_000)) { true }
        store.set("display.volumeKeyScrollRatio", JsonPrimitive(2)) { true }
        assertEquals(50, store.current.displaySetting.userMessageCollapseLines)
        assertEquals(1_000_000, store.current.displaySetting.pasteLongTextThreshold)
        assertEquals(2f, store.current.displaySetting.volumeKeyScrollRatio)
    }

    @Test fun `models and selectable services expose real IDs and validate again after approval`() = runBlocking {
        val model = Model(modelId = "real-model", displayName = "Моя модель")
        val service = SearchServiceOptions.DuckDuckGoOptions()
        val provider = ProviderSetting.OpenAI(name = "Провайдер", apiKey = "provider-secret", models = listOf(model))
        val store = Store(Settings(providers = listOf(provider), searchServices = listOf(service)))
        val models = store.engine.get(section = "models").toString()
        assertTrue(models.contains(model.id.toString()))
        assertTrue(models.contains("real-model"))
        assertFalse(models.contains("provider-secret"))
        val search = store.engine.get(section = "search").toString()
        assertTrue(search.contains(service.id.toString()))
        assertTrue(search.contains("DuckDuckGo"))
        val invalid = store.set("models.chatModelId", JsonPrimitive(Uuid.random().toString())) { fail("invalid model reached approval"); true }
        assertEquals("error", invalid["status"]!!.jsonPrimitive.content)
        store.set("models.chatModelId", JsonPrimitive(model.id.toString())) { true }
        assertEquals(model.id, store.current.chatModelId)
        val removed = store.set("models.fastModelId", JsonPrimitive(model.id.toString())) {
            store.current = store.current.copy(providers = emptyList())
            true
        }
        assertEquals("error", removed["status"]!!.jsonPrimitive.content)
        assertNotEquals(model.id, store.current.fastModelId)
    }

    @Test fun `ASR selection only accepts concrete existing IDs and never reports a normalized away null write`() = runBlocking {
        val first = ASRProviderSetting.OpenAIRealtime(name = "Первый")
        val second = ASRProviderSetting.OpenAIRealtime(name = "Второй")
        val store = Store(Settings(asrProviders = listOf(first, second), selectedASRProviderId = first.id))
        val rejected = store.set("speech.selectedASRProviderId", JsonNull) { fail("null ASR selection reached approval"); true }
        assertEquals("invalid_type", rejected["code"]!!.jsonPrimitive.content)
        assertEquals(first.id, store.current.selectedASRProviderId)
        assertEquals(0, store.writes)
        val saved = store.set("speech.selectedASRProviderId", JsonPrimitive(second.id.toString())) { true }
        assertEquals("ok", saved["status"]!!.jsonPrimitive.content)
        assertEquals(second.id, store.current.selectedASRProviderId)
        assertEquals(1, store.writes)
        val empty = Store(Settings(asrProviders = emptyList(), selectedASRProviderId = null))
        val setting = empty.engine.get(key = "speech.selectedASRProviderId")["setting"]!!.jsonObject
        assertEquals(JsonNull, setting["value"])
        assertEquals(JsonPrimitive(false), setting["nullable"])
        assertEquals(JsonArray(emptyList()), setting["choices"])
        val unavailable = empty.set("speech.selectedASRProviderId", JsonPrimitive(second.id.toString())) { fail("unavailable ASR reached approval"); true }
        assertEquals("invalid_choice", unavailable["code"]!!.jsonPrimitive.content)
        assertEquals(0, empty.writes)
    }

    @Test fun `secret reads always return fixed mask without serializing protected configs`() {
        val store = Store(Settings(
            providers = listOf(ProviderSetting.OpenAI(apiKey = "provider-secret")),
            networkSetting = NetworkSetting(proxyUrl = "https://user:proxy-secret@example.org", proxyPassword = "proxy-secret"),
            webDavConfig = WebDavConfig(password = "webdav-secret"),
            webServerAccessPassword = "server-secret",
        ))
        for (key in listOf("providers", "providers.0.apiKey", "network.proxyPassword", "mcpServers", "ssh.password", "webDavConfig.password", "s3Config", "webServerAccessPassword", "customHeaders", "customBodies")) {
            val result = store.engine.get(key = key)
            assertEquals("error", result["status"]!!.jsonPrimitive.content)
            assertEquals("manual_only", result["code"]!!.jsonPrimitive.content)
            assertEquals("Это можно изменить только вручную в настройках", result["message"]!!.jsonPrimitive.content)
            val response = result.toString()
            assertTrue(key, response.contains("••••"))
            assertFalse(response.contains("provider-secret"))
            assertFalse(response.contains("proxy-secret"))
            assertFalse(response.contains("webdav-secret"))
            assertFalse(response.contains("server-secret"))
        }
        val listed = store.engine.listSections().toString()
        assertFalse(listed.contains("provider-secret"))
    }

    @Test fun `security deletion secrets and nested object bypasses are immutable`() = runBlocking {
        val store = Store(Settings(fileToolsAllStorage = true))
        val before = store.current
        for (key in listOf("providers", "providers.0.apiKey", "network.proxyUrl", "mcpServers", "ssh", "webDavConfig", "s3Config", "toolPermissions", "toolApprovals", "HARDLINE", "rootApproval", "fileToolsAllStorage", "deleteChats", "backups", "display", "display.regenerateDeleteTrailingMessages", "display.chatCustomFontPath", "network.headers", "models.customBodies")) {
            val result = store.set(key, buildJsonObject { put("apiKey", "injected"); put("enabled", false) }) { fail("protected key reached approval: $key"); true }
            assertEquals(key, "Это можно изменить только вручную в настройках", result["message"]!!.jsonPrimitive.content)
        }
        val nested = store.set("display.showTokenUsage", buildJsonObject { put("value", false); put("toolPermissions", true) }) { fail("object reached approval"); true }
        assertEquals("error", nested["status"]!!.jsonPrimitive.content)
        assertEquals("Это можно изменить только вручную в настройках", nested["message"]!!.jsonPrimitive.content)
        assertEquals(before, store.current)
        assertEquals(0, store.writes)
    }

    @Test fun `speech language prompts search and theme save ordinary settings immediately`() = runBlocking {
        val store = Store()
        val tts = store.current.ttsProviders.first()
        val search = store.current.searchServices.last()
        for ((key, value) in listOf(
            "language.appLanguageTag" to JsonPrimitive("ru"),
            "theme.dynamicColor" to JsonPrimitive(false),
            "models.titlePrompt" to JsonPrimitive("Кратко назови чат"),
            "models.enableSuggestion" to JsonPrimitive(false),
            "search.selectedServiceId" to JsonPrimitive(search.id.toString()),
            "search.resultSize" to JsonPrimitive(25),
            "speech.selectedTTSProviderId" to JsonPrimitive(tts.id.toString()),
            "speech.defaultTTSPlaybackSpeed" to JsonPrimitive(1.5),
            "network.enableAutoRetry" to JsonPrimitive(false),
        )) assertEquals(key, "ok", store.set(key, value) { true }["status"]!!.jsonPrimitive.content)
        assertEquals("ru", store.current.displaySetting.appLanguageTag)
        assertFalse(store.current.dynamicColor)
        assertEquals("Кратко назови чат", store.current.titlePrompt)
        assertFalse(store.current.enableSuggestion)
        assertEquals(search.id, store.current.searchServices[store.current.searchServiceSelected].id)
        assertEquals(25, store.current.searchCommonOptions.resultSize)
        assertEquals(tts.id, store.current.selectedTTSProviderId)
        assertEquals(1.5f, store.current.defaultTTSPlaybackSpeed)
        assertFalse(store.current.networkSetting.enableAutoRetry)
    }
}
