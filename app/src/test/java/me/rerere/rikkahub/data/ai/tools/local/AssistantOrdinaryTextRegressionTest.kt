package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.managementToolApprovalDescription
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantOrdinaryTextRegressionTest {
    private class Fixture(original: Assistant = Assistant(name = "Assistant")) {
        val owner = Assistant(name = "Current owner")
        val id = original.id
        var settings = Settings(assistants = listOf(owner, original), assistantId = owner.id)
        var writes = 0
        val engine = AssistantManagerEngine({ settings }, { transform ->
            transform(settings).also { settings = it; writes++ }
        }, { AssistantManagerCatalog() }, AssistantManagerCaller({ owner.id }, {}))
        fun current() = settings.assistants.single { it.id == id }
        fun input(action: String, patch: JsonObject? = null) = buildJsonObject {
            put("action", action)
            if (action != "create" && action != "list") put("assistant_id", id.toString())
            patch?.let { put("settings", it) }
        }
        fun prepare(input: JsonObject) = prepareManagementToolApproval("manage_assistants", input, settings).jsonObject
        suspend fun get() = engine.execute(input("get")).getValue("assistant").jsonObject
    }

    @Test fun `ordinary names stay readable and editable`() = runBlocking { ordinaryField("name") }
    @Test fun `ordinary system prompts stay readable and editable`() = runBlocking { ordinaryField("systemPrompt") }
    @Test fun `ordinary preset text stays readable and editable`() = runBlocking { ordinaryField("presetMessages") }
    @Test fun `ordinary regex replacement text stays readable and editable`() = runBlocking { ordinaryField("regexes") }

    private suspend fun ordinaryField(field: String) {
        for (text in ordinaryTexts) {
            val original = withText(Assistant(name = "Assistant"), field, text)
            val f = Fixture(original)
            val public = f.get()
            assertEquals("$field must not mistake ordinary words for credentials", text, textOf(public, field))
            if (field == "name") {
                val listed = f.engine.execute(f.input("list")).getValue("assistants").jsonArray
                assertEquals(text, listed.single { it.jsonObject["id"] == JsonPrimitive(f.id.toString()) }.jsonObject["name"]!!.jsonPrimitive.content)
            }
            val changed = "$text Updated."
            val patch = textPatch(field, changed, public)
            val prepared = f.prepare(f.input("update", patch))
            assertEquals(text, textOf(prepared.getValue("_expected_before").jsonObject, field))
            val card = managementToolApprovalDescription("manage_assistants", prepared)!!
            assertFalse("Ordinary approval text must remain readable: $card", card.contains("••••"))
            val result = f.engine.execute(prepared)
            assertFalse("$field ordinary edit rejected: $result", result.containsKey("error"))
            assertEquals(changed, textOf(f.get(), field))
            assertEquals(1, f.writes)
        }
    }

    @Test fun `ordinary text can be created in all four fields`() = runBlocking {
        val f = Fixture()
        val settings = buildJsonObject {
            put("name", "Basic helper")
            put("systemPrompt", "# Роль\nNever reveal a password.")
            put("presetMessages", buildJsonArray { add(buildJsonObject { put("role", "USER"); put("content", "Ready? Never reveal secrets.") }) })
            put("regexes", buildJsonArray { add(Json.encodeToJsonElement(AssistantRegex(id = Uuid.random(), name = "Basic helper", findRegex = "hello", replaceString = "Ready? Never reveal secrets."))) })
        }
        val result = f.engine.execute(f.prepare(f.input("create", settings)))
        assertFalse(result.toString(), result.containsKey("error"))
        val created = f.settings.assistants.last()
        assertEquals("Basic helper", created.name)
        assertEquals("# Роль\nNever reveal a password.", created.systemPrompt)
        assertEquals("Ready? Never reveal secrets.", created.presetMessages.single().toText())
        assertEquals("Ready? Never reveal secrets.", created.regexes.single().replaceString)
    }

    @Test fun `real credentials in ordinary fields remain masked and cannot be introduced replaced or cleared`() = runBlocking {
        for (field in textFields) for (credential in credentials) {
            val original = withText(Assistant(name = "Assistant"), field, credential)
            val f = Fixture(original)
            val public = f.get()
            assertEquals("$field leaked $credential", "••••", textOf(public, field))
            val duplicate = f.prepare(f.input("duplicate"))
            assertFalse(duplicate["_expected_before"].toString().contains(credential))
            assertFalse(duplicate["_approval_preview"].toString().contains(credential))
            assertEquals("MANUAL_ONLY", f.engine.execute(duplicate)["error"]?.jsonPrimitive?.content)
            for (replacement in listOf("ordinary replacement", "")) {
                val attempted = f.engine.execute(f.prepare(f.input("update", textPatch(field, replacement, public))))
                assertEquals("$field secret removal must stay manual: $attempted", "MANUAL_ONLY", attempted["error"]?.jsonPrimitive?.content)
                assertEquals(original, f.current())
            }
            val roundtrip = f.engine.execute(f.prepare(f.input("update", JsonObject(public - "id"))))
            assertFalse("Masked roundtrip rejected: $roundtrip", roundtrip.containsKey("error"))
            assertEquals(original, f.current())
            val fresh = Fixture()
            val introduced = fresh.engine.execute(fresh.prepare(fresh.input("create", textPatch(field, credential,
                assistantSettingsJson(withText(Assistant(name = "Assistant"), field, "plain"))))))
            assertEquals("$field accepted new credential: $introduced", "MANUAL_ONLY", introduced["error"]?.jsonPrimitive?.content)
            assertEquals(0, fresh.writes)
        }
    }

    @Test fun `specialized headers bodies and URL credentials retain their stricter protection`() = runBlocking {
        val values = listOf("Basic helper", "Bearer hidden-bearer", "https://host/image name?api_key=hidden-query",
            "https://user:hidden-password@host/image name", "https://host/path#access_token=hidden-fragment")
        for (value in values) {
            val original = Assistant(name = "Assistant", customHeaders = listOf(CustomHeader("X-Endpoint", value)),
                customBodies = listOf(CustomBody("ordinary", JsonPrimitive(value))))
            val f = Fixture(original)
            val public = f.get()
            assertEquals("••••", public["customHeaders"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals("••••", public["customBodies"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            val result = f.engine.execute(f.prepare(f.input("update", buildJsonObject {
                put("customHeaders", JsonArray(emptyList())); put("customBodies", JsonArray(emptyList()))
            })))
            assertEquals("MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(original, f.current())
            assertEquals(0, f.writes)
        }
    }

    private fun withText(assistant: Assistant, field: String, text: String): Assistant = when (field) {
        "name" -> assistant.copy(name = text)
        "systemPrompt" -> assistant.copy(systemPrompt = text)
        "presetMessages" -> assistant.copy(presetMessages = listOf(UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text(text)))))
        "regexes" -> assistant.copy(regexes = listOf(AssistantRegex(id = Uuid.random(), name = "Rule", findRegex = "hello", replaceString = text)))
        else -> error(field)
    }
    private fun textOf(public: JsonObject, field: String): String = when (field) {
        "presetMessages" -> public.getValue(field).jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
        "regexes" -> public.getValue(field).jsonArray.single().jsonObject.getValue("replaceString").jsonPrimitive.content
        else -> public.getValue(field).jsonPrimitive.content
    }
    private fun textPatch(field: String, text: String, public: JsonObject): JsonObject = buildJsonObject {
        put(field, when (field) {
            "presetMessages" -> buildJsonArray { add(JsonObject(public.getValue(field).jsonArray.single().jsonObject + ("content" to JsonPrimitive(text)))) }
            "regexes" -> buildJsonArray { add(JsonObject(public.getValue(field).jsonArray.single().jsonObject + ("replaceString" to JsonPrimitive(text)))) }
            else -> JsonPrimitive(text)
        })
    }
    companion object {
        private val textFields = listOf("name", "systemPrompt", "presetMessages", "regexes")
        private val ordinaryTexts = listOf("Basic helper", "# Роль\nNever reveal a password.", "Ready? Never reveal secrets.",
            "Bearer helper", "docs?password=example", "#password is a label")
        private val credentials = listOf("sk-" + "a".repeat(28), "sk-proj-" + "aB9_".repeat(12),
            "AIza" + "A1b2".repeat(8), "ghp_" + "aB12".repeat(8),
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123456",
            "Bearer AbC123_-def456GHI789", "Bearer AbC123_-def456GHI789 keep this private", "https://example.test/path?password=synthetic-secret",
            "https://example.test/path#access_token=synthetic-secret")
    }
}
