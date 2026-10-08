package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.repository.sanitizeWorkflowDefinition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkflowDatabaseBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    // Android's native SQLite shadow exercises real SQLite files and actual generated Room
    // validation. Requery's Android JNI library is used by production, not a host JVM test.
    private fun open(context: Context, name: String): WorkflowDatabase =
        Room.databaseBuilder(context, WorkflowDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    private inline fun <T> WorkflowDatabase.useDatabase(block: (WorkflowDatabase) -> T): T =
        try { block(this) } finally { close() }

    private fun normalize(context: Context, file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0)
            }
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok")
            }
        }
        DatabaseBackup.removeSidecars(file)
    }

    private fun definition() = WorkflowDefinition("workflow", "Backup", trigger = TriggerSpec.Manual,
        authoringAssistantId = "11111111-1111-1111-1111-111111111111",
        actions = listOf(WorkflowAction("ssh_exec", buildJsonObject { put("host", "example.com"); put("password", "original-password") })))

    private suspend fun stage(raw: String = WorkflowJson.encode(definition())): File {
        val file = temporary.newFile("staged.db")
        open(context, file.absolutePath).useDatabase { db ->
            db.workflowDao().upsert(WorkflowEntity("workflow", "Backup", definitionJson = raw, createdAtMs = 1, updatedAtMs = 2,
                lastRunError = "password=original-password"))
            db.workflowRunDao().insert(WorkflowRunEntity(workflowId = "workflow", firedAtMs = 1,
                status = "FAILED", durationMs = 1, errorMessage = "Bearer original-secret"))
            DatabaseBackup.checkpoint(db.openHelper.writableDatabase)
        }
        return file
    }

    @Test fun `snapshot includes committed workflow rows and produces a standalone file`() = runBlocking {
        val source = temporary.newFile("live.db")
        val snapshot = File(temporary.root, "snapshot.db")
        open(context, source.absolutePath).useDatabase { db ->
            db.workflowDao().upsert(WorkflowEntity("workflow", "Backup", definitionJson = WorkflowJson.encode(definition()), createdAtMs = 1, updatedAtMs = 2))
            WorkflowDatabaseBackup.createSnapshot(db, snapshot)
        }
        open(context, snapshot.absolutePath).useDatabase { restored ->
            assertEquals("Backup", restored.workflowDao().getById("workflow")!!.name)
        }
        assertTrue(snapshot.isFile)
        assertFalse(File(snapshot.path + "-wal").exists())
    }

    @Test fun `staged credential sanitization and error redaction happen before live files are changed`() = runBlocking {
        val file = stage()
        val live = temporary.newFile("current-live.db").apply { writeText("original live database") }
        WorkflowDatabaseBackup.normalizeAndValidate(context, file, { def ->
            sanitizeWorkflowDefinition(def) { name, args ->
                assertEquals("ssh_exec", name)
                args.replace("original-password", "rikka-ssh-secret:reference:password")
            }
        }, ::normalize, ::open)
        open(context, file.absolutePath).useDatabase { db ->
            val entity = db.workflowDao().getById("workflow")!!
            assertFalse(entity.definitionJson.contains("original-password"))
            assertTrue(entity.definitionJson.contains("rikka-ssh-secret:"))
            assertFalse(entity.lastRunError.orEmpty().contains("original-password"))
            assertFalse(db.workflowRunDao().lastN("workflow", 10).single().errorMessage.orEmpty().contains("original-secret"))
        }
        assertEquals("original live database", live.readText())
    }

    @Test fun `restored database bytes contain no legacy key left in overflow or freed pages`() = runBlocking {
        val canary = "legacy-private-key-page-canary-"
        val privateKey = canary.repeat(5000)
        val legacy = definition().copy(actions = listOf(WorkflowAction("ssh_exec", buildJsonObject {
            put("host", "example.com")
            put("private_key", privateKey)
        })))
        val file = stage(WorkflowJson.encode(legacy))
        assertTrue("The original SQLite file must contain the legacy key", file.readBytes().toString(Charsets.ISO_8859_1).contains(canary))

        WorkflowDatabaseBackup.normalizeAndValidate(context, file, { def ->
            sanitizeWorkflowDefinition(def) { _, args -> args.replace(privateKey, "rikka-ssh-secret:reference:private_key") }
        }, ::normalize, ::open)

        assertFalse("Deleted SQLite pages must not preserve the legacy key", file.readBytes().toString(Charsets.ISO_8859_1).contains(canary))
        assertFalse(File(file.path + "-wal").exists())
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.contains("-clean-") })
        open(context, file.absolutePath).useDatabase { db ->
            assertTrue(db.workflowDao().getById("workflow")!!.definitionJson.contains("rikka-ssh-secret:reference:private_key"))
        }
    }

    @Test fun `future schema fails before sanitizer or installation`() = runBlocking {
        val file = stage()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        var calls = 0
        try {
            WorkflowDatabaseBackup.normalizeAndValidate(context, file, { calls++; it }, ::normalize, ::open)
            fail("A future workflow schema must be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }

    @Test fun `wrong table schema is rejected despite matching version`() = runBlocking {
        val file = temporary.newFile("invalid-schema.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE workflows (id TEXT NOT NULL PRIMARY KEY)")
            db.version = 1
        }
        var calls = 0
        try {
            WorkflowDatabaseBackup.normalizeAndValidate(context, file, { calls++; it }, ::normalize, ::open)
            fail("Wrong workflow tables must be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }

    @Test fun `malformed stored condition cannot become an unconditional restored workflow`() = runBlocking {
        val raw = WorkflowJson.encode(definition()).replace("\"conditions\":[]", "\"conditions\":[{\"type\":\"unknown_condition\"}]")
        val file = stage(raw)
        var calls = 0
        try {
            WorkflowDatabaseBackup.normalizeAndValidate(context, file, { calls++; it }, ::normalize, ::open)
            fail("Dropped condition must reject restoration")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
}
