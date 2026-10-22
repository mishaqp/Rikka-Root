package me.rerere.rikkahub.service

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ChatOrphanRuntimeOwnershipAuditTest : ChatAuditServiceFixture() {
    private var localOptions = emptyList<LocalToolOption>()
    private fun useActualFactory() {
        val local = mockk<LocalTools>(relaxed = true)
        every { local.getTools(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            localOptions = firstArg(); emptyList()
        }
        val mcp = mockk<McpManager>(relaxed = true)
        every { mcp.getAllAvailableTools(any(), any()) } returns emptyList()
        actualFactory = ChatToolFactory(JsonInstant, memories, repo, local, mcp,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), { mockk(relaxed = true) }, SubAgentRegistry())
    }

    @Test(timeout = 30_000) fun orphanRuntimeReadsOnlyOriginalOwnerMemoryAndRecentChats() = runBlocking {
        val oldOwner = Uuid.random()
        settings.value = settings.value.copy(assistants = listOf(owner.copy(enableMemory = true, enableRecentChatsReference = true)))
        db.memoryDao().insertMemory(MemoryEntity(assistantId = oldOwner.toString(), content = "A ONLY PRIVATE MEMORY"))
        val otherMemory = db.memoryDao().insertMemory(MemoryEntity(assistantId = owner.id.toString(), content = "B PRIVATE MEMORY")).toInt()
        val other = seed(title = "B PRIVATE CHAT")
        val source = seed(assistantId = oldOwner, rawOrphan = true, title = "A HISTORY")
        useActualFactory()
        service.regenerateAtMessage(source.id, source.currentMessages.first()); waitGeneration(source.id)
        assertEquals("Runtime owner must remain the conversation owner", oldOwner, runtimeAssistant!!.id)
        assertEquals(listOf("A ONLY PRIVATE MEMORY"), runtimeMemories.map { it.content })
        assertFalse(runtimeMemories.any { it.content.contains("B PRIVATE") })
        val recent = runtimeTools.single { it.name == "recent_chats" }.execute(buildJsonObject { put("limit", 30) }).toString()
        assertTrue(recent, recent.contains("A HISTORY"))
        assertFalse(recent, recent.contains("B PRIVATE CHAT") || recent.contains(other.id.toString()))
        runtimeTools.single { it.name == "memory_tool" }.execute(buildJsonObject {
            put("action", "edit"); put("id", otherMemory); put("content", "MUST NOT WRITE B")
        })
        assertEquals("B PRIVATE MEMORY", memories.getMemoriesOfAssistant(owner.id.toString()).single().content)
        assertEquals(oldOwner, repo.getConversationById(source.id)!!.assistantId)
        assertFalse(settings.value.assistants.any { it.id == oldOwner })
    }

    @Test(timeout = 30_000) fun orphanRuntimeNeverInheritsFallbackGlobalMemoryRootModeOrLocalCapabilities() = runBlocking {
        val oldOwner = Uuid.random()
        val fallback = owner.copy(enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
            rootMode = true, localTools = listOf(LocalToolOption.Files), enabledSkills = setOf("B-private-skill"))
        settings.value = settings.value.copy(assistants = listOf(fallback))
        db.memoryDao().insertMemory(MemoryEntity(assistantId = oldOwner.toString(), content = "A MEMORY"))
        db.memoryDao().insertMemory(MemoryEntity(assistantId = MemoryRepository.GLOBAL_MEMORY_ID, content = "B GLOBAL PRIVATE"))
        val source = seed(assistantId = oldOwner, rawOrphan = true)
        useActualFactory()
        service.regenerateAtMessage(source.id, source.currentMessages.first()); waitGeneration(source.id)
        val runtime = requireNotNull(runtimeAssistant)
        assertEquals(oldOwner, runtime.id)
        assertFalse("Fallback global-memory choice is not an orphan grant", runtime.useGlobalMemory)
        assertFalse(runtimeMemories.any { it.content == "B GLOBAL PRIVATE" })
        assertFalse("B RootMode must never become A capability", runtime.rootMode)
        assertTrue(runtime.localTools.isEmpty())
        assertTrue(localOptions.isEmpty())
        assertTrue(runtime.enabledSkills.isEmpty())
        assertTrue(runtime.mcpServers.isEmpty())
        assertNull(runtime.workspaceId)
        assertEquals("Display fallback stays compatible", fallback.id, settings.value.getAssistantOf(source).id)
    }

    @Test(timeout = 30_000) fun liveOwnerKeepsExplicitGlobalMemoryAndRootModeSettings() = runBlocking {
        val live = owner.copy(enableMemory = true, useGlobalMemory = true, rootMode = true, localTools = listOf(LocalToolOption.Files))
        settings.value = settings.value.copy(assistants = listOf(live))
        db.memoryDao().insertMemory(MemoryEntity(assistantId = MemoryRepository.GLOBAL_MEMORY_ID, content = "AUTHORIZED GLOBAL"))
        val source = seed(); useActualFactory()
        service.regenerateAtMessage(source.id, source.currentMessages.first()); waitGeneration(source.id)
        assertEquals(live.id, runtimeAssistant!!.id)
        assertTrue(runtimeAssistant!!.useGlobalMemory)
        assertTrue(runtimeAssistant!!.rootMode)
        assertEquals(listOf("AUTHORIZED GLOBAL"), runtimeMemories.map { it.content })
        assertTrue("Normal RootMode remains governed by the existing effective options", LocalToolOption.Root in localOptions)
    }
}
