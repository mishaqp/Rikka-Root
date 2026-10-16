package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import kotlin.uuid.Uuid

/** Аудит 9b74e541, пункты 10 и 11. Проверяются результаты инструментов и реальные строки Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AssistantDataIsolationAuditTest {
    private lateinit var database: AppDatabase
    private lateinit var memories: MemoryRepository
    private val owner = Assistant(enableMemory = true, localTools = emptyList())
    private val foreignOwner = Uuid.random().toString()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        memories = MemoryRepository(database.memoryDao())
    }

    @After fun tearDown() { database.close() }

    @Test fun editCannotChangeAnotherAssistantsMemory() = runBlocking {
        val id = seed(foreignOwner)
        val output = invokeMemory(owner, "edit", id)
        assertEquals("Исходная запись", database.memoryDao().getMemoryById(id)?.content)
        assertNotFound(output)
    }

    @Test fun deleteCannotRemoveAnotherAssistantsMemory() = runBlocking {
        val id = seed(foreignOwner)
        val output = invokeMemory(owner, "delete", id)
        assertNotNull(database.memoryDao().getMemoryById(id))
        assertNotFound(output)
    }

    @Test fun missingMemoryEditReturnsNotFound() = runBlocking { assertNotFound(invokeMemory(owner, "edit", 99999)) }

    @Test fun missingMemoryDeleteReturnsNotFound() = runBlocking { assertNotFound(invokeMemory(owner, "delete", 99999)) }

    @Test fun privateAssistantCannotEditGlobalMemory() = runBlocking {
        val id = seed(MemoryRepository.GLOBAL_MEMORY_ID)
        assertNotFound(invokeMemory(owner, "edit", id))
        assertEquals("Исходная запись", database.memoryDao().getMemoryById(id)?.content)
    }

    @Test fun globalMemoryModeCannotDeletePrivateMemory() = runBlocking {
        val id = seed(owner.id.toString())
        assertNotFound(invokeMemory(owner.copy(useGlobalMemory = true), "delete", id))
        assertNotNull(database.memoryDao().getMemoryById(id))
    }

    @Test fun ownedMemoryCanStillBeEditedAndDeleted() = runBlocking {
        val id = seed(owner.id.toString())
        val edited = invokeMemory(owner, "edit", id)
        assertEquals("Новая запись", edited["content"]?.jsonPrimitive?.content)
        assertEquals("Новая запись", database.memoryDao().getMemoryById(id)?.content)
        val deleted = invokeMemory(owner, "delete", id)
        assertEquals("true", deleted["success"]?.jsonPrimitive?.content)
        assertNull(database.memoryDao().getMemoryById(id))
    }

    @Test fun globalModeCanStillChangeGlobalMemory() = runBlocking {
        val id = seed(MemoryRepository.GLOBAL_MEMORY_ID)
        val global = owner.copy(useGlobalMemory = true)
        assertEquals("Новая запись", invokeMemory(global, "edit", id)["content"]?.jsonPrimitive?.content)
        assertEquals("true", invokeMemory(global, "delete", id)["success"]?.jsonPrimitive?.content)
        assertNull(database.memoryDao().getMemoryById(id))
    }

    @Test fun conversationSearchCannotReturnAnotherAssistantsChats() = runBlocking {
        // Native jieba tokenizer is unavailable in JVM tests; replace only the FTS boundary.
        // The real repository forwards the scope and the real tool renders its results.
        val own = searchResult("own-chat", "Свой чат")
        val foreign = searchResult("foreign-chat", "Чужой секрет")
        val fts = mockk<MessageFtsManager> {
            coEvery { search("планы", MessageSearchSort.RELEVANCE, null) } returns listOf(foreign, own)
            coEvery { search("планы", MessageSearchSort.RELEVANCE, owner.id.toString()) } returns listOf(own)
        }
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, mockk(relaxed = true), fts)
        val search = createConversationTools(repository, owner.id).first { it.name == "conversation_search" }
        val parts = search.execute(buildJsonObject { put("query", "планы"); put("limit", 1) })
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonArray
        assertEquals(listOf("own-chat"), output.map { it.jsonObject["conversation_id"]!!.jsonPrimitive.content })
        assertFalse(output.toString().contains("Чужой секрет"))
    }

    private suspend fun seed(assistantId: String): Int =
        database.memoryDao().insertMemory(MemoryEntity(assistantId = assistantId, content = "Исходная запись")).toInt()

    private suspend fun invokeMemory(assistant: Assistant, action: String, id: Int): JsonObject {
        val factory = ChatToolFactory(Json, memories, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), { error("Subagent must not run") }, mockk(relaxed = true))
        val tool: Tool = factory.createTools(Settings(), assistant, Model()).single { it.name == "memory_tool" }
        val parts = tool.execute(buildJsonObject { put("action", action); put("id", id); put("content", "Новая запись") })
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun assertNotFound(output: JsonObject) {
        assertEquals(output.toString(), "not_found", output["code"]?.jsonPrimitive?.content)
        assertTrue(output.toString(), output.containsKey("error"))
        assertFalse(output.toString(), output.containsKey("success"))
    }

    private fun searchResult(id: String, title: String) =
        MessageSearchResult("node-$id", "message-$id", id, title, Instant.EPOCH, "планы")
}
