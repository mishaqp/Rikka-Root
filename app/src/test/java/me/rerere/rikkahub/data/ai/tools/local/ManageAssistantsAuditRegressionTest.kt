package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.managementToolApprovalDescription
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Behavioral regressions for audit items 17–19; all credentials below are synthetic fixtures. */
class ManageAssistantsAuditRegressionTest {
    private class Fixture {
        val owner = Assistant(name = "Владелец текущего чата")
        val target = Assistant(name = "Помощник для работы", localTools = listOf(LocalToolOption.TimeInfo))
        val firstServer = McpServerConfig.SseTransportServer(commonOptions = McpCommonOptions(name = "Первый MCP"))
        val secondServer = McpServerConfig.SseTransportServer(commonOptions = McpCommonOptions(name = "Второй MCP"))
        var settings = Settings(assistants = listOf(owner, target), assistantId = owner.id,
            mcpServers = listOf(firstServer, secondServer))
        var writes = 0
        var dependencyReads = 0
        var dependencyOwner: Uuid? = null
        var storedDependencies = AssistantManagerDependencies()
        val engine = newEngine { id ->
            dependencyReads++
            dependencyOwner = id
            storedDependencies
        }

        fun newEngine(dependencies: (suspend (Uuid) -> AssistantManagerDependencies)? = null) =
            AssistantManagerEngine(readSettings = { settings }, updateSettings = { transform ->
                transform(settings).also { settings = it; writes++ }
            }, catalog = { AssistantManagerCatalog() },
                caller = AssistantManagerCaller({ owner.id }, {}), dependencies = dependencies)

        fun args(action: String = "update", patch: JsonObject? = null) = buildJsonObject {
            put("action", action)
            put("assistant_id", target.id.toString())
            patch?.let { put("settings", it) }
        }

        fun prepare(input: JsonObject): JsonObject =
            prepareManagementToolApproval("manage_assistants", input, settings).jsonObject

        fun current() = settings.assistants.single { it.id == target.id }
        fun replaceTarget(value: Assistant) {
            settings = settings.copy(assistants = settings.assistants.map { if (it.id == value.id) value else it })
        }
    }

    @Test fun `audit 17 approval freezes trusted old values and shows assistant name and full diff`() {
        val f = Fixture()
        val input = JsonObject(f.args(patch = buildJsonObject { put("name", "Новое рабочее имя") }) + mapOf(
            "_expected_before" to JsonPrimitive("подделанное старое значение"),
            "_approval_preview" to buildJsonObject { put("description", "подделанное описание") }))
        val prepared = f.prepare(input)
        val frozen = prepared["_expected_before"]
        assertNotNull("Approval must capture the old settings independently of model arguments", frozen)
        assertTrue(frozen.toString(), frozen.toString().contains(f.target.name))
        assertFalse(prepared.toString(), prepared.toString().contains("подделанн"))
        val description = managementToolApprovalDescription("manage_assistants", prepared)!!
        assertTrue(description, description.contains(f.target.name))
        assertTrue(description, description.contains("Новое рабочее имя"))
        assertTrue(description, description.contains("→"))
    }

    @Test fun `audit 17 local tool array changed after approval is preserved and update is rejected`() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare(f.args(patch = buildJsonObject { put("localTools", buildJsonArray { add("ask_user") }) }))
        val changed = f.current().copy(localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser))
        f.replaceTarget(changed)
        val result = f.engine.execute(prepared)
        assertTrue("Stale array replacement must return an error: $result", result.containsKey("error"))
        assertEquals(changed, f.current())
        assertEquals(0, f.writes)
    }

    @Test fun `audit 17 MCP array changed after approval is preserved and update is rejected`() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare(f.args(patch = buildJsonObject {
            put("mcpServers", buildJsonArray { add(f.firstServer.id.toString()) })
        }))
        val changed = f.current().copy(mcpServers = setOf(f.secondServer.id))
        f.replaceTarget(changed)
        val result = f.engine.execute(prepared)
        assertTrue("Stale MCP replacement must return an error: $result", result.containsKey("error"))
        assertEquals(changed, f.current())
        assertEquals(0, f.writes)
    }

    @Test fun `audit 17 unrelated concurrent edit does not invalidate an approved field change`() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare(f.args(patch = buildJsonObject { put("name", "Одобренное имя") }))
        f.replaceTarget(f.current().copy(systemPrompt = "Параллельное изменение промпта"))
        val result = f.engine.execute(prepared)
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("Одобренное имя", f.current().name)
        assertEquals("Параллельное изменение промпта", f.current().systemPrompt)
        assertEquals(1, f.writes)
    }

    @Test fun `audit 18 delete refuses each persisted dependency without removing its owner`() = runBlocking {
        for (dependency in listOf(
            AssistantManagerDependencies(conversations = 1),
            AssistantManagerDependencies(memories = 1),
            AssistantManagerDependencies(cronJobs = 1),
            AssistantManagerDependencies(workflows = 1),
        )) {
            val f = Fixture()
            f.storedDependencies = dependency
            val settingsBefore = f.settings
            val result = f.engine.execute(f.args(action = "delete"))
            assertTrue("Dependent records must prevent deletion ($dependency): $result", result.containsKey("error"))
            assertEquals(settingsBefore, f.settings)
            assertEquals(f.target.id, f.dependencyOwner)
            assertEquals(1, f.dependencyReads)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 18 delete without dependency checker fails closed`() = runBlocking {
        val f = Fixture()
        val before = f.settings
        val result = f.newEngine().execute(f.args(action = "delete"))
        assertTrue("Missing ownership evidence must prevent deletion: $result", result.containsKey("error"))
        assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertEquals(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 18 empty assistant can be deleted after a fresh dependency check`() = runBlocking {
        val f = Fixture()
        val result = f.engine.execute(f.args(action = "delete"))
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(f.target.id, f.dependencyOwner)
        assertEquals(1, f.dependencyReads)
        assertEquals(listOf(f.owner), f.settings.assistants)
        assertEquals(1, f.writes)
    }

    @Test fun `audit 18 dependency read failure prevents deletion and preserves data`() = runBlocking {
        val f = Fixture()
        val before = f.settings
        val engine = f.newEngine { throw IllegalStateException("database unavailable") }
        val result = engine.execute(f.args(action = "delete"))
        assertTrue(result.toString(), result.containsKey("error"))
        assertEquals(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 neutral header and nested body values are masked in get and approval`() = runBlocking {
        for (token in tokenFixtures) {
            val f = Fixture()
            f.replaceTarget(f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token)),
                customBodies = listOf(CustomBody("options", buildJsonObject {
                    put("value", buildJsonArray { add(token) })
                }))))
            val get = f.engine.execute(f.args(action = "get")).toString()
            assertFalse("Neutral secret value must not reach the model: $get", get.contains(token))
            assertTrue(get, get.contains("••••"))
            val preview = managementToolApprovalDescription("manage_assistants", f.prepare(f.args(patch = buildJsonObject {
                put("customHeaders", buildJsonArray { add(buildJsonObject { put("name", "X-Mode"); put("value", token) }) })
                put("customBodies", buildJsonArray { add(buildJsonObject { put("key", "options"); put("value", buildJsonObject {
                    put("value", buildJsonArray { add(token) })
                }) }) })
            })))!!
            assertFalse("Neutral secret value must not reach the approval card: $preview", preview.contains(token))
        }
    }

    @Test fun `audit 19 new neutral token values require manual settings for create and update`() = runBlocking {
        for (token in tokenFixtures) {
            for (field in listOf("customHeaders", "customBodies")) {
                for (action in listOf("create", "update")) {
                    val f = Fixture()
                    val patch = buildJsonObject {
                        put("name", "Новый ассистент")
                        put(field, buildJsonArray { add(buildJsonObject {
                            put(if (field == "customHeaders") "name" else "key", "X-Mode")
                            put("value", token)
                        }) })
                    }
                    val input = if (action == "create") buildJsonObject { put("action", action); put("settings", patch) }
                        else f.args(patch = patch)
                    val before = f.settings
                    val result = f.engine.execute(input)
                    assertEquals("$action/$field must not inject a credential: $result", "MANUAL_ONLY",
                        result["error"]?.jsonPrimitive?.content)
                    assertEquals(before, f.settings)
                    assertEquals(0, f.writes)
                }
            }
        }
    }

    @Test fun `audit 19 modern OpenAI keys never reach approval snapshots or descriptions`() {
        for (token in modernOpenAiTokens) {
            val f = Fixture()
            f.replaceTarget(f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token))))
            val prepared = f.prepare(f.args(patch = buildJsonObject {
                put("customHeaders", buildJsonArray { add(buildJsonObject {
                    put("name", "X-Mode"); put("value", token)
                }) })
            }))
            assertFalse("Frozen state must not disclose a modern key", prepared["_expected_before"].toString().contains(token))
            assertFalse("Preview metadata must not disclose a modern key", prepared["_approval_preview"].toString().contains(token))
            val description = managementToolApprovalDescription("manage_assistants", prepared)!!
            assertFalse("Approval description must mask the complete key", description.contains(token))
            assertTrue(description, description.contains("••••"))
        }
    }

    @Test fun `audit 19 duplicate cannot copy neutral token values`() = runBlocking {
        for (token in tokenFixtures) {
            val f = Fixture()
            f.replaceTarget(f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token))))
            val before = f.settings
            val result = f.engine.execute(f.args(action = "duplicate"))
            assertEquals(result.toString(), "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(before, f.settings)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 19 masked roundtrip preserves existing neutral credentials`() = runBlocking {
        for (token in tokenFixtures) {
            val f = Fixture()
            val original = f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token)),
                customBodies = listOf(CustomBody("options", buildJsonObject { put("value", token) })))
            f.replaceTarget(original)
            val masked = f.engine.execute(f.args(action = "get")).getValue("assistant").jsonObject
            assertFalse("get must redact before the model can produce a roundtrip patch", masked.toString().contains(token))
            val patch = JsonObject(masked - "id" + ("name" to JsonPrimitive("Новое имя")))
            val result = f.engine.execute(f.prepare(f.args(patch = patch)))
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(original.customHeaders, f.current().customHeaders)
            assertEquals(original.customBodies, f.current().customBodies)
        }
    }

    @Test fun `audit 19 text fields never disclose embedded keys in get list or approval metadata`() = runBlocking {
        for (field in textualSecretFields) for (token in modernOpenAiTokens) {
            val f = Fixture()
            f.replaceTarget(withTextSecret(f.current(), field, token))
            val get = f.engine.execute(f.args(action = "get"))
            assertFalse(get.toString(), get.containsKey("error"))
            assertFalse("get exposes a key in $field", get.toString().contains(token))
            val list = f.engine.execute(buildJsonObject { put("action", "list") })
            assertTrue("list must actually return assistants", list["assistants"] is JsonArray)
            assertFalse("list exposes a key in $field", list.toString().contains(token))
            val prepared = f.prepare(f.args(action = "duplicate"))
            assertFalse("Frozen approval state exposes a key in $field", prepared["_expected_before"].toString().contains(token))
            assertFalse("Approval metadata exposes a key in $field", prepared["_approval_preview"].toString().contains(token))
            assertFalse("Approval card exposes a key in $field",
                managementToolApprovalDescription("manage_assistants", prepared)!!.contains(token))
        }
    }

    @Test fun `audit 19 duplicate refuses credentials embedded in text fields`() = runBlocking {
        for (field in textualSecretFields) for (token in modernOpenAiTokens) {
            val f = Fixture()
            f.replaceTarget(withTextSecret(f.current(), field, token))
            val before = f.settings
            val result = f.engine.execute(f.prepare(f.args(action = "duplicate")))
            assertEquals("duplicate must protect $field: $result", "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(before, f.settings)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 19 unrelated field updates preserve original text credentials`() = runBlocking {
        for (field in textualSecretFields) for (token in modernOpenAiTokens) {
            val f = Fixture()
            val original = withTextSecret(f.current(), field, token)
            f.replaceTarget(original)
            val patch = buildJsonObject {
                put("temperature", 0.5)
                if (field != "name") put("name", "Новое обычное имя")
            }
            val result = f.engine.execute(f.prepare(f.args(patch = patch)))
            assertFalse("An unrelated change must not erase or block $field: $result", result.containsKey("error"))
            assertEquals(original.systemPrompt, f.current().systemPrompt)
            assertEquals(original.presetMessages, f.current().presetMessages)
            if (field == "name") assertEquals(original.name, f.current().name)
            assertEquals(0.5f, f.current().temperature)
        }
    }

    @Test fun `audit 19 masked full roundtrip preserves original text credentials`() = runBlocking {
        for (field in textualSecretFields) for (token in modernOpenAiTokens) {
            val f = Fixture()
            val original = withTextSecret(f.current(), field, token)
            f.replaceTarget(original)
            val masked = f.engine.execute(f.args(action = "get")).getValue("assistant").jsonObject
            assertFalse("Transport must redact $field before roundtrip", masked.toString().contains(token))
            val patch = JsonObject(masked - "id" + ("temperature" to JsonPrimitive(0.7)))
            val result = f.engine.execute(f.prepare(f.args(patch = patch)))
            assertFalse("A masked roundtrip must preserve $field: $result", result.containsKey("error"))
            assertEquals(original.name, f.current().name)
            assertEquals(original.systemPrompt, f.current().systemPrompt)
            assertEquals(original.presetMessages, f.current().presetMessages)
            assertEquals(0.7f, f.current().temperature)
        }
    }

    @Test fun `audit 19 removing credentials from protected text fields is manual only`() = runBlocking {
        for (field in textualSecretFields) for (token in modernOpenAiTokens) {
            val f = Fixture()
            f.replaceTarget(withTextSecret(f.current(), field, token))
            val before = f.settings
            val patch = buildJsonObject {
                when (field) {
                    "name" -> put(field, "Имя без ключа")
                    "systemPrompt" -> put(field, "")
                    "presetMessages" -> put(field, JsonArray(emptyList()))
                }
            }
            val result = f.engine.execute(f.prepare(f.args(patch = patch)))
            assertEquals("Removing a credential from $field requires manual settings: $result", "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(before, f.settings)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 19 delete preserves a secret custom header`() = runBlocking {
        assertDeleteWithSecretIsManual("customHeader")
    }

    @Test fun `audit 19 delete preserves a modern key in a neutral header`() = runBlocking {
        assertDeleteWithSecretIsManual("neutralHeader")
    }

    @Test fun `audit 19 delete preserves a secret system prompt`() = runBlocking {
        assertDeleteWithSecretIsManual("systemPrompt")
    }

    @Test fun `audit 19 delete preserves a secret in a non-text preset part`() = runBlocking {
        assertDeleteWithSecretIsManual("nativePreset")
    }

    private suspend fun assertDeleteWithSecretIsManual(field: String) {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val original = when (field) {
            "customHeader" -> f.current().copy(customHeaders = listOf(CustomHeader("Authorization", "synthetic-header-secret")))
            "neutralHeader" -> f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token)))
            "systemPrompt" -> f.current().copy(systemPrompt = "Не менять исходный секрет: $token")
            "nativePreset" -> f.current().copy(presetMessages = listOf(UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Image(url = "https://example.invalid/image.png?asset=$token")))))
            else -> error("Unknown deletion secret fixture")
        }
        f.replaceTarget(original)
        val before = f.settings
        val result = f.engine.execute(f.args(action = "delete"))
        assertEquals("Deleting $field requires manual settings: $result", "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
        assertEquals("The assistant and every raw secret must remain", before, f.settings)
        assertEquals(original, f.current())
        assertEquals(original.customHeaders, f.current().customHeaders)
        assertEquals(original.systemPrompt, f.current().systemPrompt)
        assertEquals(original.presetMessages, f.current().presetMessages)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 delete rechecks a secret introduced after dependency counts before settings transform`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        var dependencyReads = 0
        var rotated: Assistant? = null
        val engine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            // Another settings writer persisted a credential after the preflight dependency check.
            val withSecret = f.current().copy(customHeaders = listOf(CustomHeader("X-Mode", token)))
            rotated = withSecret
            f.replaceTarget(withSecret)
            transform(f.settings).also { f.settings = it; f.writes++ }
        }, catalog = { AssistantManagerCatalog() }, caller = AssistantManagerCaller({ f.owner.id }, {}),
            dependencies = { dependencyReads++; AssistantManagerDependencies() })
        val result = engine.execute(f.args(action = "delete"))
        assertEquals("A credential appearing before commit must prevent removal: $result", "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
        assertEquals("Preflight must have completed while the assistant had no secret", 1, dependencyReads)
        assertNotNull(rotated)
        assertEquals(rotated, f.current())
        assertEquals(token, f.current().customHeaders.single().value)
        assertEquals(2, f.settings.assistants.size)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 catalog quick message content is redacted without changing settings`() = runBlocking {
        for (token in modernOpenAiTokens) {
            val f = Fixture()
            val message = QuickMessage(title = "Обычный шаблон", content = "Содержимое с ключом $token")
            f.settings = f.settings.copy(quickMessages = listOf(message))
            val before = f.settings
            val result = f.engine.execute(buildJsonObject { put("action", "list_options") })
            val option = result.getValue("quickMessageIds").jsonArray.single().jsonObject
            assertEquals("The quick-message body is transport-only metadata", "••••", option.getValue("description").jsonPrimitive.content)
            assertFalse(result.toString().contains(token))
            assertEquals(message.id.toString(), option.getValue("id").jsonPrimitive.content)
            assertEquals(message.title, option.getValue("name").jsonPrimitive.content)
            assertSame(before, f.settings)
            assertEquals(message.content, f.settings.quickMessages.single().content)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 19 catalog neutral names and descriptions redact credential values`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val model = Model(modelId = "ordinary-model-v1", displayName = "Модель $token")
        val provider = ProviderSetting.OpenAI(name = "Провайдер $token", models = listOf(model))
        val tag = Tag(Uuid.random(), "Метка $token")
        val quick = QuickMessage(title = "Заголовок $token", content = "Обычный текст")
        f.settings = f.settings.copy(providers = listOf(provider), assistantTags = listOf(tag), quickMessages = listOf(quick))
        val workspace = AssistantManagerOption(Uuid.random().toString(), "Рабочее место $token", "Описание $token")
        val skill = AssistantManagerOption("ordinary-skill", "Навык $token", "Описание $token")
        val local = AssistantManagerOption(localToolId(LocalToolOption.TimeInfo), "Инструмент $token", "Описание $token")
        val external = AssistantManagerCatalog(workspaces = listOf(workspace), skills = listOf(skill), localTools = listOf(local))
        val before = f.settings
        val engine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            transform(f.settings).also { f.settings = it; f.writes++ }
        }, catalog = { external }, caller = AssistantManagerCaller({ f.owner.id }, {}))
        val result = engine.execute(buildJsonObject { put("action", "list_options") })
        assertFalse("Catalog name/description fields must use value redaction: $result", result.toString().contains(token))
        assertEquals("••••", result.getValue("providers").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("••••", result.getValue("models").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        for (field in listOf("workspaces", "skills", "localTools")) {
            val option = result.getValue(field).jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content ==
                when (field) { "workspaces" -> workspace.id; "skills" -> skill.id; else -> local.id } }.jsonObject
            assertEquals("••••", option.getValue("name").jsonPrimitive.content)
            assertEquals("••••", option.getValue("description").jsonPrimitive.content)
        }
        assertEquals("••••", result.getValue("tags").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("••••", result.getValue("quickMessageIds").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertSame(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 catalog get references redact provider model workspace and tag names`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val model = Model(modelId = "ordinary-model-v1", displayName = "Модель $token")
        val provider = ProviderSetting.OpenAI(name = "Провайдер $token", models = listOf(model))
        val tag = Tag(Uuid.random(), "Метка $token")
        val workspaceId = Uuid.random()
        f.settings = f.settings.copy(providers = listOf(provider), assistantTags = listOf(tag))
        f.replaceTarget(f.current().copy(chatModelId = model.id, workspaceId = workspaceId, tags = listOf(tag.id)))
        val before = f.settings
        val external = AssistantManagerCatalog(workspaces = listOf(AssistantManagerOption(workspaceId.toString(), "Рабочее место $token")))
        val engine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            transform(f.settings).also { f.settings = it; f.writes++ }
        }, catalog = { external }, caller = AssistantManagerCaller({ f.owner.id }, {}))
        val result = engine.execute(f.args(action = "get"))
        assertFalse("Reference labels must redact secret values: $result", result.toString().contains(token))
        val refs = result.getValue("references").jsonObject
        val chatModel = refs.getValue("chatModel").jsonObject
        assertEquals("••••", chatModel.getValue("name").jsonPrimitive.content)
        assertEquals("••••", chatModel.getValue("provider").jsonPrimitive.content)
        assertEquals(model.id.toString(), chatModel.getValue("id").jsonPrimitive.content)
        val workspace = refs.getValue("workspace").jsonObject
        assertEquals("••••", workspace.getValue("name").jsonPrimitive.content)
        assertEquals(workspaceId.toString(), workspace.getValue("id").jsonPrimitive.content)
        val tagRef = refs.getValue("tags").jsonArray.single().jsonObject
        assertEquals("••••", tagRef.getValue("name").jsonPrimitive.content)
        assertEquals(tag.id.toString(), tagRef.getValue("id").jsonPrimitive.content)
        assertEquals(true, tagRef.getValue("available").jsonPrimitive.boolean)
        assertSame(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 catalog redaction preserves schema structural keys ordinary ids and values`() = runBlocking {
        val f = Fixture()
        val model = Model(modelId = "ordinary-model-v1", displayName = "Обычная модель")
        val provider = ProviderSetting.OpenAI(name = "Обычный провайдер", models = listOf(model))
        f.settings = f.settings.copy(providers = listOf(provider))
        val before = f.settings
        val result = f.engine.execute(buildJsonObject { put("action", "list_options") })
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("Schema has credential-like structural keys that must remain intact", assistantSettingsSchema(), result["fields"])
        val option = result.getValue("models").jsonArray.single().jsonObject
        assertEquals(model.id.toString(), option.getValue("id").jsonPrimitive.content)
        assertEquals(model.modelId, option.getValue("modelId").jsonPrimitive.content)
        assertEquals(model.displayName, option.getValue("name").jsonPrimitive.content)
        assertEquals(provider.id.toString(), option.getValue("providerId").jsonPrimitive.content)
        assertEquals(provider.name, option.getValue("providerName").jsonPrimitive.content)
        assertSame(before, f.settings)
        assertEquals(0, f.writes)
    }

    @Test fun `audit 19 catalog invalid ids redact provider model workspace tag and quick message labels`() = runBlocking {
        for (field in listOf("chatModelId", "workspaceId", "tags", "quickMessageIds")) {
            val f = Fixture()
            val providerToken = modernOpenAiTokens.first()
            val labelToken = modernOpenAiTokens.last()
            val model = me.rerere.ai.provider.Model(modelId = "ordinary-model-v1", displayName = "Модель $labelToken")
            val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(name = "Провайдер $providerToken", models = listOf(model))
            val tag = me.rerere.rikkahub.data.model.Tag(Uuid.random(), "Метка $labelToken")
            val quick = me.rerere.rikkahub.data.model.QuickMessage(title = "Шаблон $labelToken", content = "Обычный текст")
            val workspaceId = Uuid.random()
            val external = AssistantManagerCatalog(workspaces = listOf(AssistantManagerOption(workspaceId.toString(), "Рабочее место $labelToken")))
            f.settings = f.settings.copy(providers = listOf(provider), assistantTags = listOf(tag), quickMessages = listOf(quick))
            val before = f.settings
            val engine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
                transform(f.settings).also { f.settings = it; f.writes++ }
            }, catalog = { external }, caller = AssistantManagerCaller({ f.owner.id }, {}))
            val invalidId = Uuid.random().toString()
            val patch = buildJsonObject {
                if (field in listOf("tags", "quickMessageIds")) put(field, buildJsonArray { add(invalidId) })
                else put(field, invalidId)
            }
            val result = engine.execute(f.prepare(f.args(patch = patch)))
            assertEquals("Unknown $field must retain the actionable error code: $result", "INVALID_ID", result["error"]?.jsonPrimitive?.content)
            val message = result.getValue("message").jsonPrimitive.content
            assertFalse("Invalid $field must not disclose a credential-valued label: $message", message.contains(labelToken))
            assertFalse("Invalid $field must not disclose a provider credential: $message", message.contains(providerToken))
            val allowedId = when (field) {
                "chatModelId" -> model.id.toString()
                "workspaceId" -> workspaceId.toString()
                "tags" -> tag.id.toString()
                else -> quick.id.toString()
            }
            assertTrue("Keep the real allowed UUID so the caller can correct $field: $message", message.contains(allowedId))
            assertSame(before, f.settings)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `audit 19 catalog invalid ids redact credential-like choice ids and preserve ordinary choices`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val ordinaryId = Uuid.random().toString()
        val external = AssistantManagerCatalog(skills = listOf(
            AssistantManagerOption(token, "Обычный навык"),
            AssistantManagerOption(ordinaryId, "Доступный навык")))
        val before = f.settings
        val engine = AssistantManagerEngine(readSettings = { f.settings }, updateSettings = { transform ->
            transform(f.settings).also { f.settings = it; f.writes++ }
        }, catalog = { external }, caller = AssistantManagerCaller({ f.owner.id }, {}))
        val result = engine.execute(f.prepare(f.args(patch = buildJsonObject {
            put("enabledSkills", buildJsonArray { add("unavailable-ordinary-skill") })
        })))
        assertEquals("INVALID_ID", result["error"]?.jsonPrimitive?.content)
        val message = result.getValue("message").jsonPrimitive.content
        assertFalse("A credential-like id must stay out of diagnostic text: $message", message.contains(token))
        assertTrue("A normal allowed id must remain usable: $message", message.contains(ordinaryId))
        assertTrue(message, message.contains("Обычный навык"))
        assertTrue(message, message.contains("Доступный навык"))
        assertEquals("Redaction must not mutate the catalog used for validation", token, external.skills.first().id)
        assertSame(before, f.settings)
        assertEquals(0, f.writes)
    }

    private fun withTextSecret(assistant: Assistant, field: String, token: String): Assistant = when (field) {
        "name" -> assistant.copy(name = "Рабочее имя $token")
        "systemPrompt" -> assistant.copy(systemPrompt = "Пользовательский промпт: $token")
        "presetMessages" -> assistant.copy(presetMessages = listOf(
            UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("Предустановленный текст: $token")))))
        else -> error("Unknown text field fixture")
    }

    @Test fun `audit 19 extra generic regex redaction and masked roundtrip preserve the native rule`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val regex = AssistantRegex(id = Uuid.random(), name = "Пользовательское правило", enabled = false,
            findRegex = "prefix-(.*)", replaceString = "replacement $token",
            affectingScope = setOf(AssistantAffectScope.USER), visualOnly = true)
        val original = f.current().copy(regexes = listOf(regex))
        f.replaceTarget(original)
        val masked = f.engine.execute(f.args(action = "get")).getValue("assistant").jsonObject
        assertFalse("Generic regex content must not expose a key", masked.toString().contains(token))
        assertEquals("••••", masked.getValue("regexes").jsonArray.single().jsonObject.getValue("replaceString").jsonPrimitive.content)
        val patch = JsonObject(masked - "id" + ("temperature" to JsonPrimitive(0.6)))
        val result = f.engine.execute(f.prepare(f.args(patch = patch)))
        assertFalse("A masked regex roundtrip must remain read-only: $result", result.containsKey("error"))
        assertEquals(original.regexes, f.current().regexes)
        assertSame("Retain the existing native rule instead of reconstructing it", regex, f.current().regexes.single())
        assertEquals("replacement $token", f.current().regexes.single().replaceString)
        assertEquals(0.6f, f.current().temperature)
    }

    @Test fun `audit 19 extra native SYSTEM preset redaction and roundtrip preserve all native parts`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val preset = UIMessage(role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text("Системный текст: $token"),
                UIMessagePart.Image(url = "https://example.invalid/preserved-image.png")),
            createdAt = LocalDateTime(2025, 1, 2, 3, 4), finishedAt = LocalDateTime(2025, 1, 2, 3, 5))
        val original = f.current().copy(presetMessages = listOf(preset))
        f.replaceTarget(original)
        val masked = f.engine.execute(f.args(action = "get")).getValue("assistant").jsonObject
        assertFalse("Native SYSTEM text must be redacted in transport", masked.toString().contains(token))
        val projected = masked.getValue("presetMessages").jsonArray.single().jsonObject
        assertEquals("SYSTEM", projected.getValue("role").jsonPrimitive.content)
        assertEquals("••••", projected.getValue("content").jsonPrimitive.content)
        val patch = JsonObject(masked - "id" + ("temperature" to JsonPrimitive(0.8)))
        val result = f.engine.execute(f.prepare(f.args(patch = patch)))
        assertFalse("A read-only projection must not reject the stored native SYSTEM role: $result", result.containsKey("error"))
        assertEquals(original.presetMessages, f.current().presetMessages)
        assertSame("Retain native role, timestamps and non-text parts", preset, f.current().presetMessages.single())
        assertEquals(preset.parts, f.current().presetMessages.single().parts)
        assertEquals(0.8f, f.current().temperature)
    }

    @Test fun `audit 19 extra duplicate refuses a secret confined to a non-text preset part`() = runBlocking {
        val f = Fixture()
        val token = modernOpenAiTokens.first()
        val image = UIMessagePart.Image(url = "https://example.invalid/image.png?asset=$token")
        val preset = UIMessage(role = MessageRole.USER, parts = listOf(image))
        assertEquals("The secret must be absent from canonical text, not a Text fixture", "", preset.toText())
        f.replaceTarget(f.current().copy(presetMessages = listOf(preset)))
        val before = f.settings
        val transport = f.engine.execute(f.args(action = "get"))
        assertFalse("The non-text source must stay outside the text projection", transport.toString().contains(token))
        val result = f.engine.execute(f.prepare(f.args(action = "duplicate")))
        assertEquals("Duplication must inspect native parts beyond toText(): $result", "MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
        assertEquals(before, f.settings)
        assertSame(preset, f.current().presetMessages.single())
        assertEquals(0, f.writes)
    }

    companion object {
        private val textualSecretFields = listOf("systemPrompt", "presetMessages", "name")
        private val modernOpenAiTokens = listOf(
            "sk-proj-" + "a".repeat(48),
            "sk-svcacct-" + "aB9_".repeat(12) + "-Zx7",
        )
        private val tokenFixtures = listOf(
            "sk-abcdefghijklmnopqrstuvwx",
            "AIzaabcdefghijklmnopqrstuvwx",
            "ghp_abcdefghijklmnopqrstuvwx",
        ) + modernOpenAiTokens
    }
}
