package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.prepareManagementToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class RootModeManagementCompatibilityTest {
    private class Fixture(initial: Assistant = Assistant(name = "Ассистент")) {
        val id = initial.id
        var settings = Settings(assistants = listOf(initial), assistantId = id)
        var writes = 0
        val engine = AssistantManagerEngine(
            readSettings = { settings },
            updateSettings = { transform -> transform(settings).also { settings = it; writes++ } },
            catalog = { AssistantManagerCatalog() },
            caller = AssistantManagerCaller({ id }, {}),
            dependencies = { AssistantManagerDependencies() },
        )
        fun current() = settings.assistants.single { it.id == id }
        fun manuallySetRootMode(value: Boolean) {
            settings = settings.copy(assistants = settings.assistants.map { if (it.id == id) it.copy(rootMode = value) else it })
        }
        fun args(action: String = "update", patch: JsonObject? = null) = buildJsonObject {
            put("action", action)
            put("assistant_id", id.toString())
            patch?.let { put("settings", it) }
        }
        fun prepare(args: JsonObject) = prepareManagementToolApproval("manage_assistants", args, settings).jsonObject
        suspend fun call(action: String = "update", patch: JsonObject? = null) = engine.execute(prepare(args(action, patch)))
    }

    @Test fun `old assistant JSON retains false root mode default`() {
        assertFalse(Assistant().rootMode)
        assertFalse(JsonInstant.decodeFromString<Assistant>("{}").rootMode)
        assertFalse(assistantSettingsJson(Assistant()).getValue("rootMode").jsonPrimitive.boolean)
    }

    @Test fun `root mode is advertised consistently in get and the editable boolean schema`() = runBlocking {
        val fixture = Fixture(Assistant(rootMode = true))
        val get = fixture.call(action = "get")
        assertTrue(get.getValue("assistant").jsonObject.getValue("rootMode").jsonPrimitive.boolean)
        assertEquals("boolean", assistantSettingsSchema()["rootMode"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue(get.getValue("fields").jsonObject.containsKey("rootMode"))
    }

    @Test fun `approved root mode update preserves selected native tools and captures boolean diff`() = runBlocking {
        val selected = listOf(LocalToolOption.TimeInfo, LocalToolOption.AlarmClock, LocalToolOption.Browser)
        val fixture = Fixture(Assistant(localTools = selected))
        val approved = fixture.prepare(fixture.args(patch = buildJsonObject { put("rootMode", true) }))
        assertFalse(approved.getValue("_expected_before").jsonObject.getValue("rootMode").jsonPrimitive.boolean)
        assertTrue(approved.getValue("_approval_preview").jsonObject.getValue("after").jsonObject.getValue("rootMode").jsonPrimitive.boolean)
        val result = fixture.engine.execute(approved)
        assertFalse(result.toString(), result.containsKey("error"))
        assertTrue(fixture.current().rootMode)
        assertEquals(selected, fixture.current().localTools)
        assertEquals(1, fixture.writes)
    }

    @Test fun `full get settings roundtrip preserves enabled root mode`() = runBlocking {
        val fixture = Fixture(Assistant(name = "Всё через root", rootMode = true))
        val before = fixture.current()
        val get = fixture.call(action = "get").getValue("assistant").jsonObject
        val result = fixture.call(patch = JsonObject(get - "id"))
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(before, fixture.current())
        assertTrue(fixture.current().rootMode)
    }

    @Test fun `manual root mode and native SYSTEM preset survive unrelated update and duplication`() = runBlocking {
        val preset = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("Нативное сообщение")))
        val selected = listOf(LocalToolOption.Files, LocalToolOption.Browser)
        val fixture = Fixture(Assistant(rootMode = true, localTools = selected, presetMessages = listOf(preset)))
        val updated = fixture.call(patch = buildJsonObject { put("name", "Новое имя") })
        assertFalse(updated.toString(), updated.containsKey("error"))
        assertTrue(fixture.current().rootMode)
        assertSame(preset, fixture.current().presetMessages.single())
        val duplicated = fixture.call(action = "duplicate")
        assertFalse(duplicated.toString(), duplicated.containsKey("error"))
        val copy = fixture.settings.assistants.single { it.id != fixture.id }
        assertTrue(copy.rootMode)
        assertEquals(selected, copy.localTools)
        assertSame(preset, copy.presetMessages.single())
        assertNotEquals(fixture.id, copy.id)
    }

    @Test fun `approval rejects a root mode toggle whose old value changed before commit`() = runBlocking {
        val fixture = Fixture()
        val approved = fixture.prepare(fixture.args(patch = buildJsonObject { put("rootMode", true) }))
        fixture.manuallySetRootMode(true)
        val before = fixture.settings
        val result = fixture.engine.execute(approved)
        assertEquals("STALE_APPROVAL", result["error"]?.jsonPrimitive?.content)
        assertEquals(before, fixture.settings)
        assertEquals(0, fixture.writes)
    }

    @Test fun `unrelated approved edit preserves root mode changed manually after approval`() = runBlocking {
        val fixture = Fixture()
        val approved = fixture.prepare(fixture.args(patch = buildJsonObject { put("name", "Новое имя") }))
        fixture.manuallySetRootMode(true)
        val result = fixture.engine.execute(approved)
        assertFalse(result.toString(), result.containsKey("error"))
        assertTrue(fixture.current().rootMode)
        assertEquals("Новое имя", fixture.current().name)
    }

    @Test fun `duplicate approval includes root mode and refuses a stale flag`() = runBlocking {
        val fixture = Fixture(Assistant(rootMode = true))
        val approved = fixture.prepare(fixture.args(action = "duplicate"))
        assertTrue(approved.getValue("_expected_before").jsonObject.getValue("rootMode").jsonPrimitive.boolean)
        fixture.manuallySetRootMode(false)
        val before = fixture.settings
        val result = fixture.engine.execute(approved)
        assertEquals("STALE_APPROVAL", result["error"]?.jsonPrimitive?.content)
        assertEquals(before, fixture.settings)
        assertEquals(0, fixture.writes)
    }
}
