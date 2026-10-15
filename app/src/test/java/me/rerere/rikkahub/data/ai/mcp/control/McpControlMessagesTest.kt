package me.rerere.rikkahub.data.ai.mcp.control

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpControlMessagesTest {
    private val rawFailure = "HTTP 401 Unauthorized"
    private val server = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "Remote"),
        url = "https://8.8.8.8/mcp",
    )
    private val caller = McpControlCaller({ setOf(server.id) }, {}, {})

    @Test fun `get and list explain remote failure in Russian while keeping raw diagnosis`() = runBlocking {
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(mcpServers = listOf(server)))
        val manager = mockk<McpManager>()
        every { manager.syncingStatus } returns MutableStateFlow(mapOf(server.id to McpStatus.Error(rawFailure)))

        val get = envelope(mcpGetTool(settings, manager, caller).execute(buildJsonObject { put("id", server.id.toString()) }))
        val listed = envelope(mcpListTool(settings, manager, caller).execute(JsonObject(emptyMap())))
            .getValue("servers").jsonArray.single().jsonObject

        for (result in listOf(get, listed)) {
            assertEquals("ERROR", result.getValue("status").jsonPrimitive.content)
            val explanation = result.getValue("error").jsonPrimitive.content
            assertTrue(explanation, explanation.contains("Проверьте"))
            assertFalse(explanation, explanation.contains("Unauthorized"))
            assertEquals(rawFailure, result.getValue("raw_error").jsonPrimitive.content)
        }
    }

    @Test fun `failed add keeps machine code and raw error but shows Russian recovery and rollback`() = runBlocking {
        val state = MutableStateFlow(Settings())
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns state
        coEvery { settings.update(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(state.value).also { state.value = it }
        }
        val statuses = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
        val manager = mockk<McpManager>()
        every { manager.syncingStatus } returns statuses
        coEvery { manager.addClient(any()) } coAnswers {
            val added = firstArg<McpServerConfig>()
            statuses.value = mapOf(added.id to McpStatus.Error(rawFailure))
        }
        coEvery { manager.removeClient(any()) } returns Unit
        val result = envelope(mcpAddTool(settings, manager, caller, mockk()).execute(buildJsonObject {
            put("transport", "streamable_http")
            put("name", "Remote")
            put("url", server.url)
        }))

        assertEquals("auth_required", result.getValue("error").jsonPrimitive.content)
        assertEquals(rawFailure, result.getValue("raw_error").jsonPrimitive.content)
        val explanation = result.getValue("detail").jsonPrimitive.content
        assertTrue(explanation, explanation.contains("Проверьте"))
        assertTrue(explanation, explanation.contains("Конфигурация удалена"))
        assertFalse(explanation, explanation.contains("Unauthorized"))
        assertTrue(state.value.mcpServers.isEmpty())
    }

    private fun envelope(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
}
