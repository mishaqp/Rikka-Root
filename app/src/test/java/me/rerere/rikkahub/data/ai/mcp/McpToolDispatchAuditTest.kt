package me.rerere.rikkahub.data.ai.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class McpToolDispatchAuditTest {
    @Test fun `audit 18 normalized dispatch name routes to the exact original remote tool name`() = runBlocking {
        val serverId = Uuid.random()
        val originalName = "прочитать/" + "very-long-tool-name".repeat(12)
        val manager = mockk<McpManager>()
        every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(serverId, "Мой сервер", McpTool(name = originalName)))
        coEvery { manager.callTool(serverId, originalName, any()) } returns listOf(UIMessagePart.Text("remote-result"))
        val localTools = mockk<LocalTools>(relaxed = true)
        val factory = ChatToolFactory(
            json = Json,
            memoryRepository = mockk(), conversationRepository = mockk(), localTools = localTools,
            mcpManager = manager, skillManager = mockk(), workspaceRepository = mockk(),
            rootAccessStore = mockk(), toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
            subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
        )
        val registered = factory.createTools(Settings(), Assistant(localTools = emptyList()), Model()).single()
        assertTrue(registered.name, registered.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val result = registered.execute(JsonObject(emptyMap()))
        assertEquals("remote-result", (result.single() as UIMessagePart.Text).text)
        coVerify(exactly = 1) { manager.callTool(serverId, originalName, JsonObject(emptyMap())) }
    }
}
