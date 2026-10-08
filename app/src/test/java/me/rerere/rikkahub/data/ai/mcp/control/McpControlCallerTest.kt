package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withoutConversationFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpControlCallerTest {
    @Test fun `interactive mutation targets current chat and preserves other chats and selected assistant`() = runBlocking {
        val globalServer = Uuid.random(); val firstServer = Uuid.random(); val secondServer = Uuid.random(); val added = Uuid.random()
        val owner = Assistant(mcpServers = setOf(globalServer))
        val selected = Assistant(mcpServers = setOf(secondServer))
        var settings = Settings(assistantId = selected.id, assistants = listOf(owner, selected),
            mcpServers = listOf(globalServer,firstServer,secondServer,added).map { McpServerConfig.StreamableHTTPServer(id = it) })
        val first = Conversation(assistantId = owner.id, messageNodes = emptyList(), config = ConversationConfig(mcpServers = setOf(firstServer)))
        val second = Conversation(assistantId = owner.id, messageNodes = emptyList(), config = ConversationConfig(mcpServers = setOf(secondServer)))
        val chats = mutableMapOf(first.id to first, second.id to second)
        val writes = mutableListOf<Uuid>()
        val caller = createMcpControlCaller(settings.getAssistantOf(first), first.id, headless = false) { id, transform ->
            writes += id
            val current = chats.getValue(id)
            val stored = settings.getStoredAssistantOf(current)
            val updated = transform(settings.getAssistantOf(current))
            chats[id] = current.withAssistantUpdate(updated, settings)
            val storedUpdate = updated.withoutConversationFields(current, stored)
            settings = settings.copy(assistants = settings.assistants.map { if (it.id == storedUpdate.id) storedUpdate else it })
        }
        caller.addServer(added)
        assertEquals(setOf(firstServer,added), chats.getValue(first.id).config!!.mcpServers)
        assertEquals(second, chats.getValue(second.id))
        assertEquals(listOf(owner,selected), settings.assistants)
        assertEquals(selected.id, settings.assistantId)
        assertEquals(setOf(firstServer,added), caller.assistantMcpServers())
        caller.removeServer(firstServer)
        assertEquals(setOf(added), chats.getValue(first.id).config!!.mcpServers)
        assertEquals(listOf(first.id,first.id), writes)
    }

    @Test fun `headless MCP changes stay in side context without writing creator chat`() = runBlocking {
        val original = Uuid.random(); val added = Uuid.random()
        val assistant = Assistant(mcpServers = setOf(original))
        var persistenceCalls = 0
        val caller = createMcpControlCaller(assistant, Uuid.random(), headless = true) { _, _ ->
            persistenceCalls++
            throw AssertionError("Headless run must never write creator or run chat")
        }
        caller.addServer(added)
        assertEquals(setOf(original,added), caller.assistantMcpServers())
        caller.removeServer(original)
        assertEquals(setOf(added), caller.assistantMcpServers())
        assertEquals(setOf(original), assistant.mcpServers)
        assertEquals(0, persistenceCalls)
    }

    @Test fun `changed owner cannot redirect a pending MCP update to another assistant`() = runBlocking {
        val original = Assistant(mcpServers = setOf(Uuid.random()))
        val changed = Assistant()
        var changedAfter = changed
        val caller = createMcpControlCaller(original, Uuid.random(), headless = false) { _, transform ->
            changedAfter = transform(changed)
        }
        val failure = runCatching { caller.addServer(Uuid.random()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Владелец"))
        assertEquals(changed, changedAfter)
        assertEquals(original.mcpServers, caller.assistantMcpServers())
    }

    @Test fun `missing current chat and failed persistence do not claim enabled server`() = runBlocking {
        val assistant = Assistant()
        val server = Uuid.random()
        val missing = createMcpControlCaller(assistant, null, headless = false) { _, _ -> throw AssertionError("No context") }
        assertTrue(runCatching { missing.addServer(server) }.isFailure)
        val failed = createMcpControlCaller(assistant, Uuid.random(), headless = false) { _, transform ->
            transform(assistant)
            error("Failed to save")
        }
        assertTrue(runCatching { failed.addServer(server) }.isFailure)
        assertFalse(server in failed.assistantMcpServers())
    }
}
