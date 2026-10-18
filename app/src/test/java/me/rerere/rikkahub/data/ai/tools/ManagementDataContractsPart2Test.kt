package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.RecentChatsToolUI
import me.rerere.rikkahub.ui.components.message.tools.ConversationSearchToolUI
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ManagementDataContractsPart2Test {
    private suspend fun invoke(tool: Tool, args: String): JsonElement {
        val result = runCatching { tool.execute(Json.parseToJsonElement(args)) }
        assertNull("Tool must return an actionable result, not throw: ${tool.name}: $args", result.exceptionOrNull())
        val text = (result.getOrThrow().single() as UIMessagePart.Text).text
        assertFalse("Tool JSON must be compact: ${tool.name}", text.contains('\n'))
        return Json.parseToJsonElement(text)
    }
    private fun assertError(result: JsonElement, field: String) {
        assertTrue(result.toString(), result is JsonObject)
        val obj = result.jsonObject
        assertTrue(result.toString(), "error" in obj || obj["status"] == JsonPrimitive("error") || obj["ok"] == JsonPrimitive(false))
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result.toString()))
        assertTrue("Error must identify $field: $result", result.toString().contains(field))
    }
    @Test fun memoryRejectsNumericContentBeforeCreatingRecord() = runBlocking {
        val saved = mutableListOf<String>()
        val tool = buildMemoryTools(Json, { saved += it; AssistantMemory(1, it) }, { id, text -> AssistantMemory(id, text) }, {}).single()
        assertError(invoke(tool, """{"action":"create","content":123}"""), "content")
        assertTrue(saved.toString(), saved.isEmpty())
    }
    @Test fun memoryRejectsQuotedIdBeforeEditingOrDeletingRecord() = runBlocking {
        val records = mutableMapOf(12 to "Исходная память")
        val tool = buildMemoryTools(Json, { AssistantMemory(1, it) }, { id, text -> records[id] = text; AssistantMemory(id, text) }, { records.remove(it) }).single()
        for (action in listOf("edit", "delete")) assertError(invoke(tool,
            """{"action":"$action","id":"12","content":"Подмена"}"""), "id")
        assertEquals(mapOf(12 to "Исходная память"), records)
    }
    @Test fun validMemoryCreateStillPassesExactText() = runBlocking {
        var saved: String? = null
        val tool = buildMemoryTools(Json, { saved = it; AssistantMemory(7, it) }, { id, text -> AssistantMemory(id, text) }, {}).single()
        val result = invoke(tool, """{"action":"create","content":"Текст 123"}""").jsonObject
        assertEquals("Текст 123", saved)
        assertEquals(JsonPrimitive(7), result["id"])
        assertEquals(JsonPrimitive("Текст 123"), result["content"])
    }
    @Test fun conversationMissingQueryReturnsRussianJsonError() = runBlocking {
        val tool = createConversationTools(mockk(), Uuid.random()).single { it.name == "conversation_search" }
        assertError(invoke(tool, "{}"), "query")
    }
    @Test fun conversationRejectsNumericQueryWithoutSearching() = runBlocking {
        var searched = false
        val repository = mockk<ConversationRepository> {
            coEvery { searchMessages(any(), any(), any(), any()) } coAnswers { searched = true; emptyList() }
        }
        val tool = createConversationTools(repository, Uuid.random()).single { it.name == "conversation_search" }
        assertError(invoke(tool, """{"query":123}"""), "query")
        assertFalse(searched)
    }
    @Test fun conversationSearchReportsOmittedHitsAndKeepsOwnerScope() = runBlocking {
        val owner = Uuid.random()
        var usedOwner: Uuid? = null
        val hits = (1..3).map { MessageSearchResult("node$it", "m$it", "own$it", "Свой $it", Instant.EPOCH, "Совпадение") }
        val repository = mockk<ConversationRepository> {
            coEvery { searchMessages(any(), any(), any(), any()) } coAnswers { usedOwner = thirdArg(); hits }
        }
        val tool = createConversationTools(repository, owner).single { it.name == "conversation_search" }
        val result = invoke(tool, """{"query":"планы","limit":2}""")
        assertTrue("Search must identify omitted rows: $result", result is JsonObject)
        assertEquals(owner, usedOwner)
        val obj = result.jsonObject
        assertEquals(listOf("own1", "own2"), obj["items"]?.jsonArray?.map { it.jsonObject["conversation_id"]?.jsonPrimitive?.content })
        assertEquals(JsonPrimitive(true), obj["truncated"])
        assertTrue(obj.toString(), Regex("уточн|query|limit", RegexOption.IGNORE_CASE).containsMatchIn(obj["message"]?.jsonPrimitive?.content.orEmpty()))
    }
    @Test fun recentChatsPreserveClampingAndReportExtraKnownRowWithoutForeignOwners() = runBlocking {
        val owner = Uuid.random()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
                mockk(relaxed = true), mockk(relaxed = true))
            val rows = (1..31).map { Conversation(assistantId = owner, title = "Свой $it", messageNodes = emptyList()) }
            val foreign = Conversation(assistantId = Uuid.random(), title = "Чужой секрет", messageNodes = emptyList(), isPinned = true)
            (rows + foreign).forEach { db.conversationDao().insert(repository.conversationToConversationEntity(it)) }
            val tool = createConversationTools(repository, owner).single { it.name == "recent_chats" }
            val result = invoke(tool, """{"limit":999}""")
            assertTrue(result.toString(), result is JsonObject)
            val items = result.jsonObject["items"]?.jsonArray
            assertEquals(30, items?.size)
            assertTrue(items.toString(), items!!.all { item -> rows.any { it.id.toString() == item.jsonObject["id"]?.jsonPrimitive?.content } })
            assertFalse(result.toString(), result.toString().contains("Чужой секрет"))
            assertEquals(JsonPrimitive(true), result.jsonObject["truncated"])
            assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result.jsonObject["message"]?.jsonPrimitive?.content.orEmpty()))
        } finally { db.close() }
    }
    @Test fun conversationToolCardsKeepOldArraysAndReadNewItemsEnvelopes() {
        val items = Json.parseToJsonElement("""[{"id":"c","conversation_id":"c","title":"Чат"}]""")
        for (renderer in listOf(RecentChatsToolUI, ConversationSearchToolUI)) {
            fun context(content: JsonElement) = ToolUIContext(
                UIMessagePart.Tool(toolCallId = "call", toolName = renderer.toolName, input = "{}"),
                buildJsonObject {}, content, false)
            assertTrue("Legacy array summary disappeared: ${renderer.toolName}", renderer.hasSummary(context(items)))
            val envelope = buildJsonObject { put("items", items); put("truncated", true) }
            assertTrue("Envelope summary missing: ${renderer.toolName}", renderer.hasSummary(context(envelope)))
        }
    }
    @Test fun workspaceRejectsQuotedAndInvalidOverwriteBeforeWriting() = runBlocking {
        var writes = 0
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById("w") } returns null
            coEvery { executeCommand(any(), any(), any(), any(), any()) } coAnswers {
                writes++; WorkspaceCommandResult(0, "f\u00001\u00001\u0000/workspace/test.txt\u0000", "")
            }
        }
        val tool = createWorkspaceTools("w", repository).single { it.name == "workspace_write_file" }
        for (bad in listOf("\"false\"", "123", "null", "{}")) {
            assertError(invoke(tool, """{"path":"/workspace/test.txt","text":"X","overwrite":$bad}"""), "overwrite")
        }
        assertEquals(0, writes)
    }
    @Test fun workspaceRejectsQuotedReplaceAllBeforeReadingOrWriting() = runBlocking {
        var calls = 0
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById("w") } returns null
            coEvery { rootfsFileSize(any(), any()) } coAnswers { calls++; 0L }
        }
        val tool = createWorkspaceTools("w", repository).single { it.name == "workspace_edit_file" }
        assertError(invoke(tool, """{"path":"/workspace/test.txt","old_text":"a","new_text":"b","replace_all":"true"}"""), "replace_all")
        assertEquals(0, calls)
    }
    @Test fun workspaceShellRejectsQuotedTimeoutWithoutExecution() = runBlocking {
        var calls = 0
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById("w") } returns null
            coEvery { executeCommand(any(), any(), any(), any(), any()) } coAnswers { calls++; WorkspaceCommandResult(0, "", "") }
        }
        val tool = createWorkspaceTools("w", repository).single { it.name == "workspace_shell" }
        assertError(invoke(tool, """{"command":"pwd","timeout":"30"}"""), "timeout")
        assertEquals(0, calls)
    }
    @Test fun workspaceShellKeepsDefaultAndClampedNumericTimeouts() = runBlocking {
        val timeouts = mutableListOf<Long>()
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById("w") } returns null
            coEvery { executeCommand(any(), any(), any(), any(), any()) } coAnswers {
                timeouts += arg<Long>(3); WorkspaceCommandResult(0, "", "")
            }
        }
        val tool = createWorkspaceTools("w", repository).single { it.name == "workspace_shell" }
        invoke(tool, """{"command":"pwd"}""")
        invoke(tool, """{"command":"pwd","timeout":0}""")
        invoke(tool, """{"command":"pwd","timeout":999}""")
        assertEquals(listOf(30000L, 1000L, 600000L), timeouts)
    }
    @Test fun workspaceBackgroundOutputExplicitlyReportsDroppedTailAndRecovery() = runBlocking {
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById("w") } returns null
            coEvery { backgroundOutput("w", "p") } returns BackgroundStatus("p", "cmd", "", false, 0, 1,
                "tail", "", 64, 0)
        }
        val tool = createWorkspaceTools("w", repository).single { it.name == "workspace_background_output" }
        val result = invoke(tool, """{"process_id":"p"}""").jsonObject
        assertEquals(JsonPrimitive("tail"), result["stdout"])
        assertEquals(JsonPrimitive(64), result["droppedStdout"])
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result["message"]?.jsonPrimitive?.content.orEmpty()))
    }
}
