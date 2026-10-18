package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.managementToolApprovalDescription
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantAbsoluteUrlProseRegressionTest {
    @Test fun aUrlPrefixDoesNotTurnUnparseableOrdinaryProseIntoCredentialParameters() = runBlocking {
        for (field in fields) for (text in listOf(
            "https://example.test/help# Роль: не раскрывай password",
            "https://example.test/help? Роль: не раскрывай secret",
        )) {
            val original = withText(Assistant(name = "Assistant"), field, text)
            val f = Fixture(original)
            val view = f.get()
            assertEquals("A URL-looking prefix is insufficient in $field", text, textOf(view, field))
            val next = "$text Updated."
            val prepared = f.prepare(patch(field, next, view))
            assertEquals(text, textOf(prepared["_expected_before"]!!.jsonObject, field))
            assertFalse(managementToolApprovalDescription("manage_assistants", prepared)!!.contains("••••"))
            val result = f.engine.execute(prepared)
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(next, textOf(f.get(), field))
            assertEquals(1, f.writes)
        }
    }

    @Test fun parseableCredentialUrlsAndKnownKeysStayProtectedBeforeTheOrdinaryUrlGate() = runBlocking {
        for (field in fields) for (credential in listOf(
            "https://example.test/help?password=synthetic-secret",
            "https://example.test/help#access_token=synthetic-secret",
            "https://user:synthetic-password@example.test/help",
            "https://example.test/help# Роль: sk-proj-FAKE_test_credential_1234567890",
        )) {
            val original = withText(Assistant(name = "Assistant"), field, credential)
            val f = Fixture(original)
            val view = f.get()
            assertEquals("••••", textOf(view, field))
            val result = f.engine.execute(f.prepare(patch(field, "ordinary replacement", view)))
            assertEquals("MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(original, f.current())
            assertEquals(0, f.writes)
        }
    }

    @Test fun specializedCredentialValuesRetainTolerantUrlHandling() = runBlocking {
        for (value in listOf("https://host/image name?api_key=synthetic-secret",
            "https://user:synthetic-password@host/image name",
            "https://example.test/help# Роль: не раскрывай password")) {
            val original = Assistant(name = "Assistant", customHeaders = listOf(CustomHeader("X-Endpoint", value)),
                customBodies = listOf(CustomBody("ordinary", JsonPrimitive(value))))
            val f = Fixture(original)
            val view = f.get()
            assertEquals("••••", view["customHeaders"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals("••••", view["customBodies"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            val result = f.engine.execute(f.prepare(buildJsonObject {
                put("customHeaders", JsonArray(emptyList())); put("customBodies", JsonArray(emptyList()))
            }))
            assertEquals("MANUAL_ONLY", result["error"]?.jsonPrimitive?.content)
            assertEquals(original, f.current())
            assertEquals(0, f.writes)
        }
    }

    @Test fun fileUrisWithCredentialParametersKeepOldAndNewOrdinaryFieldsProtected() = runBlocking {
        val plainFile = "file:///scratch/image.png"
        for (field in fields) {
            val ordinary = Fixture(withText(Assistant(name = "Assistant"), field, plainFile))
            assertEquals(plainFile, textOf(ordinary.get(), field))
            val ordinaryResult = ordinary.engine.execute(ordinary.prepare(patch(field, "$plainFile Updated.", ordinary.get())))
            assertFalse(ordinaryResult.toString(), ordinaryResult.containsKey("error"))
            assertEquals("$plainFile Updated.", textOf(ordinary.get(), field))
        }
        for (field in fields) for (credential in fileCredentials) {
            val original = withText(Assistant(name = "Assistant"), field, credential)
            val old = Fixture(original)
            val view = old.get()
            assertEquals("A parsed absolute file URI is credential evidence in $field", "••••", textOf(view, field))
            val rejected = old.engine.execute(old.prepare(patch(field, "ordinary replacement", view)))
            assertEquals("MANUAL_ONLY", rejected["error"]?.jsonPrimitive?.content)
            assertEquals(original, old.current())
            assertEquals(0, old.writes)
            val roundtrip = old.engine.execute(old.prepare(JsonObject(view - "id")))
            assertFalse(roundtrip.toString(), roundtrip.containsKey("error"))
            assertEquals(original, old.current())
            assertEquals(1, old.writes)

            val fresh = Fixture(withText(Assistant(name = "Assistant"), field, "plain"))
            val value = patch(field, credential, fresh.get())
            val introduced = fresh.engine.execute(fresh.prepare(value))
            assertEquals("MANUAL_ONLY", introduced["error"]?.jsonPrimitive?.content)
            assertEquals(0, fresh.writes)
            val createArgs = buildJsonObject {
                put("action", "create")
                put("settings", JsonObject(mapOf("name" to JsonPrimitive("Fresh assistant")) + value))
            }
            val create = fresh.engine.execute(prepareManagementToolApproval("manage_assistants", createArgs, fresh.settings))
            assertEquals("MANUAL_ONLY", create["error"]?.jsonPrimitive?.content)
            assertEquals(0, fresh.writes)
            assertFalse(introduced.toString().contains("synthetic-file-secret"))
            assertFalse(create.toString().contains("synthetic-file-secret"))
        }
    }

    @Test fun fileUrisRemainProtectedInSpecializedHeadersBodiesAvatarAndBackground() = runBlocking {
        for (field in listOf("customHeaders", "customBodies", "avatar", "background")) for (credential in fileCredentials) {
            val original = when (field) {
                "customHeaders" -> Assistant(name = "Assistant", customHeaders = listOf(CustomHeader("X-Endpoint", credential)))
                "customBodies" -> Assistant(name = "Assistant", customBodies = listOf(CustomBody("ordinary", JsonPrimitive(credential))))
                "avatar" -> Assistant(name = "Assistant", avatar = me.rerere.rikkahub.data.model.Avatar.Image(credential))
                else -> Assistant(name = "Assistant", background = credential)
            }
            val old = Fixture(original)
            val view = old.get()
            val projected = when (field) {
                "customHeaders", "customBodies" -> view[field]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content
                "avatar" -> view[field]!!.jsonObject["url"]!!.jsonPrimitive.content
                else -> view[field]!!.jsonPrimitive.content
            }
            assertEquals("••••", projected)
            val empty = when (field) {
                "customHeaders", "customBodies" -> JsonArray(emptyList())
                "avatar" -> buildJsonObject { put("type", "dummy") }
                else -> JsonNull
            }
            val rejected = old.engine.execute(old.prepare(buildJsonObject { put(field, empty) }))
            assertEquals("MANUAL_ONLY", rejected["error"]?.jsonPrimitive?.content)
            assertEquals(original, old.current())
            assertEquals(0, old.writes)
            val roundtrip = old.engine.execute(old.prepare(JsonObject(view - "id")))
            assertFalse(roundtrip.toString(), roundtrip.containsKey("error"))
            assertEquals(original, old.current())

            val fresh = Fixture(Assistant(name = "Assistant"))
            val raw = when (field) {
                "customHeaders" -> buildJsonArray { add(buildJsonObject { put("name", "X-Endpoint"); put("value", credential) }) }
                "customBodies" -> buildJsonArray { add(buildJsonObject { put("key", "ordinary"); put("value", credential) }) }
                "avatar" -> buildJsonObject { put("type", "image"); put("url", credential) }
                else -> JsonPrimitive(credential)
            }
            val introduced = fresh.engine.execute(fresh.prepare(buildJsonObject { put(field, raw) }))
            assertEquals("MANUAL_ONLY", introduced["error"]?.jsonPrimitive?.content)
            assertEquals(0, fresh.writes)
            val createArgs = buildJsonObject {
                put("action", "create")
                put("settings", buildJsonObject { put("name", "Fresh assistant"); put(field, raw) })
            }
            val create = fresh.engine.execute(prepareManagementToolApproval("manage_assistants", createArgs, fresh.settings))
            assertEquals("MANUAL_ONLY", create["error"]?.jsonPrimitive?.content)
            assertEquals(0, fresh.writes)
            assertFalse(introduced.toString().contains("synthetic-file-secret"))
            assertFalse(create.toString().contains("synthetic-file-secret"))
        }
    }

    private class Fixture(original: Assistant) {
        private val id = original.id
        var settings = Settings(assistants = listOf(original), assistantId = id)
        var writes = 0
        val engine = AssistantManagerEngine({ settings }, { transform -> transform(settings).also { settings = it; writes++ } },
            { AssistantManagerCatalog() }, AssistantManagerCaller({ id }, {}))
        fun current() = settings.assistants.single()
        suspend fun get() = engine.execute(buildJsonObject { put("action", "get"); put("assistant_id", id.toString()) })["assistant"]!!.jsonObject
        fun prepare(patch: JsonObject) = prepareManagementToolApproval("manage_assistants", buildJsonObject {
            put("action", "update"); put("assistant_id", id.toString()); put("settings", patch)
        }, settings).jsonObject
    }

    private fun withText(assistant: Assistant, field: String, value: String): Assistant = when (field) {
        "name" -> assistant.copy(name = value)
        "systemPrompt" -> assistant.copy(systemPrompt = value)
        "presetMessages" -> assistant.copy(presetMessages = listOf(UIMessage.assistant(value)))
        "regexes" -> assistant.copy(regexes = listOf(AssistantRegex(id = Uuid.random(), name = "Rule", findRegex = "hello", replaceString = value)))
        else -> error(field)
    }
    private fun textOf(view: JsonObject, field: String): String = when (field) {
        "presetMessages" -> view[field]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
        "regexes" -> view[field]!!.jsonArray.single().jsonObject["replaceString"]!!.jsonPrimitive.content
        else -> view[field]!!.jsonPrimitive.content
    }
    private fun patch(field: String, value: String, view: JsonObject) = buildJsonObject {
        put(field, when (field) {
            "presetMessages" -> JsonArray(listOf(JsonObject(view[field]!!.jsonArray.single().jsonObject + ("content" to JsonPrimitive(value)))))
            "regexes" -> JsonArray(listOf(JsonObject(view[field]!!.jsonArray.single().jsonObject + ("replaceString" to JsonPrimitive(value)))))
            else -> JsonPrimitive(value)
        })
    }
    companion object {
        private val fields = listOf("name", "systemPrompt", "presetMessages", "regexes")
        private val fileCredentials = listOf("file:///scratch/image.png?password=synthetic-file-secret",
            "file:///scratch/image.png#access_token=synthetic-file-secret")
    }
}
