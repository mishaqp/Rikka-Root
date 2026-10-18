package me.rerere.rikkahub.workflow.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkflowMalformedAssistantReferenceRegressionTest {
    private lateinit var database: WorkflowDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WorkflowDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { database.close() }

    @Test fun `malformed unrelated tool argument is not an assistant binding while its actual owner remains protected`() = runBlocking {
        val author = Uuid.random().toString()
        val ignored = Uuid.random().toString()
        val raw = """{"authoring_assistant_id":"$author","actions":[{"tool":"root_exec","args":{"command":"echo ok","assistant_id":"$ignored"}}], BROKEN"""
        val row = store(raw)
        assertEquals("root_exec never consumes assistant_id as an assistant binding", 0,
            database.workflowDao().countReferencingAssistant(ignored))
        assertEquals("The damaged workflow must still retain its actual author", 1,
            database.workflowDao().countReferencingAssistant(author))
        assertEquals(row, database.workflowDao().getById(row.id))
    }

    @Test fun `malformed canonical and escaped authoring owners stay protected without rewriting the row`() = runBlocking {
        for (escaped in listOf(false, true)) {
            val owner = Uuid.random().toString()
            val key = if (escaped) "authoring_\\u0061ssistant_id" else "authoring_assistant_id"
            val value = if (escaped) owner.replace("-", "\\u002d") else owner
            val row = store("""{"$key":"$value", BROKEN""")
            assertEquals(1, database.workflowDao().countReferencingAssistant(owner))
            assertEquals(row, database.workflowDao().getById(row.id))
        }
    }

    @Test fun `all actual management target actions remain references and ignored actions do not`() = runBlocking {
        for (action in listOf("get", "update", "duplicate", "switch", "delete", "create", "list", "list_options")) {
            val owner = Uuid.random().toString()
            val row = store("""{"actions":[{"tool":"manage_assistants","args":{"action":"$action","assistant_id":"$owner"}}]}""")
            assertEquals(action, if (action in setOf("get", "update", "duplicate", "switch", "delete")) 1 else 0,
                database.workflowDao().countReferencingAssistant(owner))
            assertEquals(row, database.workflowDao().getById(row.id))
        }
    }

    @Test fun `complete actual action subtree stays protected when the surrounding workflow is damaged`() = runBlocking {
        for (damageBeforeActions in listOf(false, true)) {
            val author = Uuid.random().toString()
            val target = Uuid.random().toString()
            val actions = """"actions":[{"args":{"assistant_id":"$target","action":"get"},"tool":"manage_assistants"}]"""
            val raw = if (damageBeforeActions) """{"authoring_assistant_id":"$author", BROKEN, $actions}"""
                else """{"authoring_assistant_id":"$author",$actions, BROKEN"""
            val row = store(raw)
            assertEquals("A complete semantic management target must remain protected", 1,
                database.workflowDao().countReferencingAssistant(target))
            assertEquals(1, database.workflowDao().countReferencingAssistant(author))
            assertEquals(row, database.workflowDao().getById(row.id))
        }
    }

    @Test fun `complete actual action prefix survives an incomplete array without trusting unfinished objects or strings`() = runBlocking {
        val author = Uuid.random().toString()
        val ignored = Uuid.random().toString()
        val target = Uuid.random().toString()
        val unfinished = Uuid.random().toString()
        val raw = """{"authoring_assistant_id":"$author","actions":[{"tool":"root_exec","args":{"assistant_id":"$ignored"}},{"tool":"manage_assistants","args":{"action":"get","assistant_id":"$target"}},{"tool":"manage_assistants","args":{"action":"get","assistant_id":""" + "\"$unfinished\""
        val row = store(raw)
        assertEquals(0, database.workflowDao().countReferencingAssistant(ignored))
        assertEquals("The complete actual action before the damaged tail remains a binding", 1,
            database.workflowDao().countReferencingAssistant(target))
        assertEquals("Unclosed tool/args objects are not verified references", 0,
            database.workflowDao().countReferencingAssistant(unfinished))
        assertEquals(1, database.workflowDao().countReferencingAssistant(author))
        assertEquals(row, database.workflowDao().getById(row.id))

        val stringOwner = Uuid.random().toString()
        val fake = Uuid.random().toString()
        val invalidString = """{"authoring_assistant_id":"$stringOwner","description":"unfinished prose \"actions\":[{\"tool\":\"manage_assistants\",\"args\":{\"action\":\"get\",\"assistant_id\":\"$fake\"}}]"""
        val stringRow = store(invalidString)
        assertEquals("Do not scan through an unterminated string for fake root fields", 0,
            database.workflowDao().countReferencingAssistant(fake))
        assertEquals(1, database.workflowDao().countReferencingAssistant(stringOwner))
        assertEquals(stringRow, database.workflowDao().getById(stringRow.id))
    }

    private suspend fun store(raw: String): WorkflowEntity = WorkflowEntity(
        id = Uuid.random().toString(), name = "Проверка ссылки", enabled = false,
        definitionJson = raw, createdAtMs = 1, updatedAtMs = 1,
    ).also { database.workflowDao().upsert(it) }
}
