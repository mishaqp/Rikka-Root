package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ManageAssistantsToolTest {
    private class Fixture {
        val owner = Assistant(name = "Текущий")
        val model = Model(modelId = "test-model", displayName = "Test model")
        var settings = Settings(assistants = listOf(owner), assistantId = owner.id,
            providers = listOf(ProviderSetting.OpenAI(name = "Провайдер", models = listOf(model))))
        var catalog = AssistantManagerCatalog(skills = listOf(AssistantManagerOption("skill", "Навык")),
            workspaces = listOf(AssistantManagerOption("11111111-1111-1111-1111-111111111111", "Папка")))
        var writes = 0
        val engine = AssistantManagerEngine(readSettings = { settings }, updateSettings = { transform ->
            transform(settings).also { settings = it; writes++ }
        }, catalog = { catalog }, caller = AssistantManagerCaller(currentAssistantId = { owner.id }, switchAssistant = { id ->
            require(settings.assistants.any { it.id == id }); settings = settings.copy(assistantId = id)
        }))
        suspend fun call(action: String, id: Uuid? = null, patch: JsonObject? = null): JsonObject = engine.execute(buildJsonObject {
            put("action", action); id?.let { put("assistant_id", it.toString()) }; patch?.let { put("settings", it) }
        })
    }

    @Test fun `schema and get cover every assistant field`() = runBlocking {
        val f = Fixture()
        val fields = Assistant.serializer().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        }
        val result = f.call("get", f.owner.id)
        assertEquals(fields, result.getValue("assistant").jsonObject.keys)
        assertEquals(fields - "id", assistantSettingsSchema().keys)
        assertEquals(fields - "id", result.getValue("fields").jsonObject.keys)
    }

    @Test fun `create accepts every editable field and get roundtrips`() = runBlocking {
        val f = Fixture()
        val tag = me.rerere.rikkahub.data.model.Tag(Uuid.random(), "Работа")
        val quick = me.rerere.rikkahub.data.model.QuickMessage(title = "Продолжить", content = "Продолжи ответ")
        val mode = me.rerere.rikkahub.data.model.PromptInjection.ModeInjection(name = "Режим")
        val lorebook = me.rerere.rikkahub.data.model.Lorebook(name = "Мир")
        val mcp = me.rerere.rikkahub.data.ai.mcp.McpServerConfig.SseTransportServer(
            commonOptions = me.rerere.rikkahub.data.ai.mcp.McpCommonOptions(name = "Сервер"))
        f.settings = f.settings.copy(assistantTags = listOf(tag), quickMessages = listOf(quick),
            modeInjections = listOf(mode), lorebooks = listOf(lorebook), mcpServers = listOf(mcp))
        val patch = assistantSettingsJson(f.owner).toMutableMap().apply {
            remove("id"); put("name", JsonPrimitive("Полный")); put("chatModelId", JsonPrimitive(f.model.id.toString()))
            // Every boolean and every optional/default field is deliberately non-default.
            toMap().forEach { (key, value) -> (value as? JsonPrimitive)?.booleanOrNull?.let { put(key, JsonPrimitive(!it)) } }
            put("avatar", buildJsonObject { put("type", "emoji"); put("content", "🦊") })
            put("systemPrompt", JsonPrimitive("Отвечай точно")); put("messageTemplate", JsonPrimitive("Вопрос: {{ message }}"))
            put("contextMessageLimit", JsonPrimitive(42)); put("autoCompressionTokenThreshold", JsonPrimitive(8192))
            put("reasoningLevel", JsonPrimitive("high")); put("subAgentConcurrencyLimit", JsonPrimitive(4))
            put("background", JsonPrimitive("https://example.org/background.png")); put("backgroundOpacity", JsonPrimitive(0.25))
            put("timeReminderIntervalMinutes", JsonPrimitive(15))
            put("temperature", JsonPrimitive(0.7)); put("topP", JsonPrimitive(0.9))
            put("maxTokens", JsonPrimitive(500)); put("tokenBudgetSoftCap", JsonPrimitive(1000)); put("tokenBudgetHardCap", JsonPrimitive(2000))
            put("tags", buildJsonArray { add(tag.id.toString()) }); put("quickMessageIds", buildJsonArray { add(quick.id.toString()) })
            put("mcpServers", buildJsonArray { add(mcp.id.toString()) }); put("modeInjectionIds", buildJsonArray { add(mode.id.toString()) })
            put("lorebookIds", buildJsonArray { add(lorebook.id.toString()) }); put("localTools", buildJsonArray { add("time_info"); add("ask_user") })
            put("enabledSkills", buildJsonArray { add("skill") }); put("workspaceId", JsonPrimitive(f.catalog.workspaces.single().id))
            put("customHeaders", buildJsonArray { add(buildJsonObject { put("name", "X-Feature"); put("value", "on") }) })
            put("customBodies", buildJsonArray {
                add(buildJsonObject { put("key", "request"); put("value", buildJsonObject { put("flag", true) }) })
                add(buildJsonObject { put("key", "max_tokens"); put("value", 300) })
            })
            put("presetMessages", buildJsonArray { add(buildJsonObject { put("id", Uuid.random().toString()); put("role", "ASSISTANT"); put("content", "Продолжение") }) })
            put("regexes", buildJsonArray { add(buildJsonObject {
                put("id", Uuid.random().toString()); put("name", "Замена"); put("enabled", false); put("findRegex", "foo+"); put("replaceString", "bar")
                put("affectingScope", buildJsonArray { add("USER"); add("ASSISTANT") }); put("visualOnly", true)
            }) })
        }
        val result = f.call("create", patch = JsonObject(patch))
        assertFalse(result.toString(), result.containsKey("error"))
        val created = f.settings.assistants.last()
        assertEquals("Полный", created.name)
        assertEquals("Продолжение", created.presetMessages.single().toText())
        assertEquals("on", created.customHeaders.single().value)
        assertEquals(JsonObject(patch), JsonObject(assistantSettingsJson(created) - "id"))
        assertEquals(assistantSettingsJson(created), f.call("get", created.id)["assistant"])
        assertEquals(1, f.writes)
    }

    @Test fun `invalid and stale ids show valid choices and never mutate`() = runBlocking {
        val f = Fixture()
        for (field in listOf("chatModelId", "workspaceId", "enabledSkills", "mcpServers", "tags", "quickMessageIds", "modeInjectionIds", "lorebookIds", "localTools")) {
            val value: JsonElement = if (field.endsWith("Id")) JsonPrimitive("bad") else buildJsonArray { add("bad") }
            val result = f.call("update", f.owner.id, buildJsonObject { put(field, value) })
            assertTrue(result.toString(), result["message"]!!.jsonPrimitive.content.contains("Допустимые"))
        }
        f.catalog = AssistantManagerCatalog()
        val stale = f.call("update", f.owner.id, buildJsonObject { put("enabledSkills", buildJsonArray { add("skill") }) })
        assertTrue(stale.containsKey("error")); assertEquals(0, f.writes)
    }

    @Test fun `local tool metadata provides Russian labels without authorizing invented ids`() = runBlocking {
        val f = Fixture()
        f.catalog = f.catalog.copy(localTools = listOf(
            AssistantManagerOption("time_info", "Текущее время", "Дата, время и часовой пояс"),
            AssistantManagerOption("invented", "Вымышленный инструмент")))
        val options = f.call("list_options")["localTools"]!!.jsonArray.map { it.jsonObject }
        val time = options.single { it["id"]!!.jsonPrimitive.content == "time_info" }
        assertEquals("Текущее время", time["name"]!!.jsonPrimitive.content)
        assertEquals("Дата, время и часовой пояс", time["description"]!!.jsonPrimitive.content)
        assertFalse(options.any { it["id"]!!.jsonPrimitive.content == "invented" })
        assertTrue(options.all { it["name"]!!.jsonPrimitive.content.isNotBlank() })
        val rejected = f.call("update", f.owner.id, buildJsonObject { put("localTools", buildJsonArray { add("invented") }) })
        assertEquals("INVALID_ID", rejected["error"]!!.jsonPrimitive.content)
        assertTrue(rejected["message"]!!.jsonPrimitive.content.contains("Текущее время"))
        assertEquals(0, f.writes)
    }

    @Test fun `secrets are masked and cannot be replaced or removed`() = runBlocking {
        val f = Fixture()
        f.settings = f.settings.copy(assistants = listOf(f.owner.copy(
            customHeaders = listOf(CustomHeader("Authorization", "sensitive-header")),
            customBodies = listOf(CustomBody("options", buildJsonObject { put("api_key", "sensitive-body"); put("n", 1) })))))
        val get = f.call("get", f.owner.id).toString()
        assertFalse(get.contains("sensitive-header")); assertFalse(get.contains("sensitive-body")); assertTrue(get.contains("••••"))
        val changes = listOf(buildJsonObject { put("customHeaders", JsonArray(emptyList())) },
            buildJsonObject { put("customBodies", JsonArray(emptyList())) },
            buildJsonObject { put("toolApprovals", buildJsonObject {}) })
        for (change in changes) assertEquals("Это можно изменить только вручную в настройках", f.call("update", f.owner.id, change)["message"]!!.jsonPrimitive.content)
        assertEquals(0, f.writes)
    }

    @Test fun `owner and last assistant cannot be deleted and switch leaves owner protected`() = runBlocking {
        val f = Fixture()
        assertTrue(f.call("delete", f.owner.id).containsKey("error"))
        f.call("create", patch = buildJsonObject { put("name", "Временный") })
        val other = f.settings.assistants.last()
        assertFalse(f.call("switch", other.id).containsKey("error"))
        assertEquals(other.id, f.settings.assistantId)
        assertTrue(f.call("delete", f.owner.id).containsKey("error"))
        assertFalse(f.call("delete", other.id).containsKey("error"))
        assertEquals(listOf(f.owner), f.settings.assistants)
    }

    @Test fun `all mutations always require approval`() {
        for (action in listOf("create", "update", "duplicate", "switch", "delete")) assertTrue(manageAssistantsNeedsApproval(buildJsonObject { put("action", action) }))
        for (action in listOf("list", "get", "list_options")) assertFalse(manageAssistantsNeedsApproval(buildJsonObject { put("action", action) }))
        assertTrue(manageAssistantsNeedsApproval(buildJsonObject { put("action", "unknown") }))
        assertTrue(manageAssistantsNeedsApproval(JsonNull))
    }

    @Test fun `credential urls stay protected across get preview update and duplicate`() = runBlocking {
        val f = Fixture()
        val url = "https://user:hidden-password@example.org/image?api_key=hidden-key"
        val withSecrets = f.owner.copy(avatar = me.rerere.rikkahub.data.model.Avatar.Image(url), background = url,
            customHeaders = listOf(CustomHeader("X-Endpoint", url)),
            customBodies = listOf(CustomBody("endpoint", JsonPrimitive(url))))
        f.settings = f.settings.copy(assistants = listOf(withSecrets))
        assertFalse(f.call("get", f.owner.id).toString().contains("hidden-"))
        val preview = manageAssistantsApprovalDescription(buildJsonObject { put("action", "update"); put("settings", buildJsonObject {
            put("background", url); put("customHeaders", buildJsonArray { add(buildJsonObject { put("name", "Authorization"); put("value", "hidden-header") }) })
            put("customBodies", buildJsonArray { add(buildJsonObject { put("key", "nested"); put("value", buildJsonObject { put("password", "hidden-body") }) }) })
        }) })
        assertFalse(preview, preview.contains("hidden-")); assertTrue(preview.contains("••••"))
        assertFalse(f.call("update", f.owner.id, buildJsonObject { put("name", "Сохранён") }).containsKey("error"))
        val preserved = f.settings.assistants.single()
        assertEquals(withSecrets.avatar, preserved.avatar); assertEquals(url, preserved.background)
        assertEquals(withSecrets.customHeaders, preserved.customHeaders); assertEquals(withSecrets.customBodies, preserved.customBodies)
        assertEquals("Это можно изменить только вручную в настройках", f.call("duplicate", f.owner.id)["message"]!!.jsonPrimitive.content)
        assertEquals("Это можно изменить только вручную в настройках", f.call("update", f.owner.id, buildJsonObject { put("background", JsonNull) })["message"]!!.jsonPrimitive.content)
    }

    @Test fun `masked full patch changes nonsecret body while preserving nested credentials`() = runBlocking {
        val f = Fixture()
        val body = CustomBody("options", buildJsonObject { put("api_key", "must-stay"); put("n", 1) })
        f.settings = f.settings.copy(assistants = listOf(f.owner.copy(customBodies = listOf(body))))
        val masked = f.call("get", f.owner.id)["assistant"]!!.jsonObject
        val updated = JsonObject(masked - "id" + ("name" to JsonPrimitive("Изменён")))
        assertFalse(f.call("update", f.owner.id, updated).containsKey("error"))
        assertEquals(body, f.settings.assistants.single().customBodies.single())
    }

    @Test fun `reasoning levels use native serial names and roundtrip through get`() = runBlocking {
        val f = Fixture()
        for (level in me.rerere.ai.core.ReasoningLevel.entries) {
            val wire = Json.encodeToJsonElement(me.rerere.ai.core.ReasoningLevel.serializer(), level)
            assertFalse(f.call("update", f.owner.id, buildJsonObject { put("reasoningLevel", wire) }).containsKey("error"))
            assertEquals(level, f.settings.assistants.single().reasoningLevel)
            val read = f.call("get", f.owner.id)["assistant"]!!.jsonObject["reasoningLevel"]
            assertEquals(wire, read)
        }
    }

    @Test fun `unrelated patch preserves native presets and existing invalid regex without revalidation`() = runBlocking {
        val f = Fixture()
        val regexes = listOf(
            me.rerere.rikkahub.data.model.AssistantRegex(Uuid.random(), name = "Ещё редактируется", findRegex = "["),
            me.rerere.rikkahub.data.model.AssistantRegex(Uuid.random(), name = "Второе", findRegex = "valid"))
        val presets = listOf(me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.SYSTEM,
            parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("Нативный префикс"),
                me.rerere.ai.ui.UIMessagePart.Image("https://example.org/preset.png"))))
        f.settings = f.settings.copy(assistants = listOf(f.owner.copy(regexes = regexes, presetMessages = presets)))
        val result = f.call("update", f.owner.id, buildJsonObject { put("name", "Новое имя") })
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("Новое имя", f.settings.assistants.single().name)
        assertSame(regexes, f.settings.assistants.single().regexes)
        assertSame(presets, f.settings.assistants.single().presetMessages)
        val explicitInvalid = f.call("update", f.owner.id, buildJsonObject { put("regexes", buildJsonArray {
            add(buildJsonObject { put("findRegex", "[") })
        }) })
        assertEquals("INVALID_ARGUMENT", explicitInvalid["error"]!!.jsonPrimitive.content)
        assertSame(regexes, f.settings.assistants.single().regexes)
        assertSame(presets, f.settings.assistants.single().presetMessages)
    }

    @Test fun `explicit preset and regex arrays reject duplicate ids`() = runBlocking {
        val f = Fixture()
        val id = Uuid.random().toString()
        for (field in listOf("presetMessages", "regexes")) {
            val entry = buildJsonObject {
                put("id", id)
                if (field == "presetMessages") { put("role", "ASSISTANT"); put("content", "Реплика") }
                else put("findRegex", "valid")
            }
            val result = f.call("update", f.owner.id, buildJsonObject { put(field, buildJsonArray { add(entry); add(entry) }) })
            assertEquals("INVALID_ARGUMENT", result["error"]!!.jsonPrimitive.content)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("уникальными"))
        }
        assertEquals(0, f.writes)
    }

    @Test fun `credential masking handles whitespace and imperfect urls before strict uri parsing`() = runBlocking {
        val f = Fixture()
        val values = listOf(" Bearer hidden-bearer ", "https://host/image name?api_key=hidden-query",
            "https://user:hidden-password@host/image name", "https://host/path#access_token=hidden-fragment")
        for (value in values) {
            f.settings = f.settings.copy(assistants = listOf(f.owner.copy(customHeaders = listOf(CustomHeader("X-Endpoint", value)))))
            assertFalse(f.call("get", f.owner.id).toString().contains("hidden-"))
            val result = f.call("update", f.owner.id, buildJsonObject { put("customHeaders", JsonArray(emptyList())) })
            assertEquals("Это можно изменить только вручную в настройках", result["message"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `credential header names mask token appearing in the middle`() = runBlocking {
        val f = Fixture()
        for (name in listOf("X-Token-Something", "X-Auth", "X-Auth-Key", "X-Custom-Key")) {
            f.settings = f.settings.copy(assistants = listOf(f.owner.copy(customHeaders = listOf(CustomHeader(name, "hidden-middle")))))
            val get = f.call("get", f.owner.id)
            assertFalse(get.toString(), get.toString().contains("hidden-middle"))
            assertEquals("••••", get["assistant"]!!.jsonObject["customHeaders"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `duplicate and delete operate against state inside persistence transaction`() = runBlocking {
        val f = Fixture()
        f.call("create", patch = buildJsonObject { put("name", "Копируемый") })
        val other = f.settings.assistants.last()
        val raceEngine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            // Simulate another settings writer deleting the target after preflight and before the lock.
            f.settings = f.settings.copy(assistants = listOf(f.owner))
            transform(f.settings).also { f.settings = it }
        }, catalog = { f.catalog }, caller = AssistantManagerCaller({ f.owner.id }, {}))
        val result = raceEngine.execute(buildJsonObject { put("action", "duplicate"); put("assistant_id", other.id.toString()) })
        assertEquals("NOT_FOUND", result["error"]!!.jsonPrimitive.content)
        assertEquals(1, f.settings.assistants.size)
    }

    @Test fun `delete rechecks last assistant and requires a trusted chat owner`() = runBlocking {
        val f = Fixture()
        val other = Assistant(name = "Другой")
        f.settings = f.settings.copy(assistants = listOf(f.owner, other))
        val raceEngine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            f.settings = f.settings.copy(assistants = listOf(other), assistantId = other.id)
            transform(f.settings).also { f.settings = it }
        }, catalog = { f.catalog }, caller = AssistantManagerCaller({ f.owner.id }, {}))
        val args = buildJsonObject { put("action", "delete"); put("assistant_id", other.id.toString()) }
        assertEquals("PROTECTED_ASSISTANT", raceEngine.execute(args)["error"]!!.jsonPrimitive.content)
        assertEquals(listOf(other), f.settings.assistants)
        f.settings = f.settings.copy(assistants = listOf(f.owner, other))
        val untrusted = AssistantManagerEngine({ f.settings }, { transform -> transform(f.settings).also { f.settings = it } },
            { f.catalog }, AssistantManagerCaller({ null }, {}))
        assertEquals("PROTECTED_ASSISTANT", untrusted.execute(args)["error"]!!.jsonPrimitive.content)
        assertEquals(2, f.settings.assistants.size)
    }

    @Test fun `unloaded dummy settings cannot be mutated`() = runBlocking {
        val f = Fixture()
        f.settings = f.settings.copy(init = true)
        val result = f.call("create", patch = buildJsonObject { put("name", "Ранний") })
        assertEquals("SETTINGS_UNAVAILABLE", result["error"]!!.jsonPrimitive.content)
        assertEquals(0, f.writes)
    }

    @Test fun `actions reject irrelevant settings and clear rather than ignoring them`() = runBlocking {
        val f = Fixture()
        for (action in listOf("list", "get", "list_options", "switch", "delete")) {
            assertEquals("INVALID_ARGUMENT", f.call(action, if (action in listOf("get", "switch", "delete")) f.owner.id else null,
                buildJsonObject { put("name", "Игнорировать нельзя") })["error"]!!.jsonPrimitive.content)
            assertEquals("MANUAL_ONLY", f.call(action, if (action in listOf("get", "switch", "delete")) f.owner.id else null,
                buildJsonObject { put("apiKey", "hidden") })["error"]!!.jsonPrimitive.content)
            val clear = f.engine.execute(buildJsonObject { put("action", action); put("clear", buildJsonArray { add("background") }) })
            assertEquals("INVALID_ARGUMENT", clear["error"]!!.jsonPrimitive.content)
        }
        assertEquals(0, f.writes)
    }

    @Test fun `type and range validation does not silently coerce`() = runBlocking {
        val f = Fixture()
        for (patch in listOf("{\"temperature\":3}", "{\"topP\":-1}", "{\"streamOutput\":\"true\"}",
            "{\"contextMessageLimit\":-1}", "{\"subAgentConcurrencyLimit\":9}", "{\"autoCompressionTokenThreshold\":2047}",
            "{\"backgroundOpacity\":1.1}", "{\"timeReminderIntervalMinutes\":0}", "{\"maxTokens\":1.5}")) {
            assertTrue(patch, f.call("update", f.owner.id, Json.parseToJsonElement(patch).jsonObject).containsKey("error"))
        }
        assertEquals(0, f.writes)
    }
}
