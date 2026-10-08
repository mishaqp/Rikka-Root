package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingRestoreMultipleDatabaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main get() = File(temporary.root, "databases/rikka_hub")
    private val workflows get() = File(temporary.root, "databases/workflows.db")
    private fun restore() = PendingRestore(File(temporary.root, "restore"), main,
        File(temporary.root, "files"), listOf(workflows))
    private fun write(file: File, value: String) {
        file.parentFile!!.mkdirs()
        file.writeText(value)
    }
    private fun seed() {
        for (db in listOf(main, workflows)) {
            write(db, "old ${db.name}")
            for (suffix in listOf("-wal", "-shm", "-journal")) write(File(db.path + suffix), "old $suffix")
        }
    }
    private fun stage(includeWorkflows: Boolean = true) {
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/database/${main.name}"), "new main")
        if (includeWorkflows) write(File(staging, "payload/database/${workflows.name}"), "new workflows")
        write(File(staging, "settings.json"), "settings")
        restore.publish(staging)
    }

    @Test fun twoDatabasesInstallWithoutOldSidecars() = runBlocking {
        seed(); stage()
        assertTrue(restore().apply { })
        assertEquals("new main", main.readText())
        assertEquals("new workflows", workflows.readText())
        for (db in listOf(main, workflows)) for (suffix in listOf("-wal", "-shm", "-journal"))
            assertFalse(File(db.path + suffix).exists())
    }

    @Test fun settingsFailureRollsBackBothDatabasesAndTheirSidecars() = runBlocking {
        seed(); stage()
        try {
            restore().apply { throw IOException("Disk full") }
            fail("Expected rollback")
        } catch (_: RestoreFailedException) { }
        for (db in listOf(main, workflows)) {
            assertEquals("old ${db.name}", db.readText())
            for (suffix in listOf("-wal", "-shm", "-journal"))
                assertEquals("old $suffix", File(db.path + suffix).readText())
        }
    }

    @Test fun restartResumesBothDatabaseInstallationWithoutReplay() = runBlocking {
        seed(); stage()
        try { restore().apply { throw ProcessDeath() } } catch (_: ProcessDeath) { }
        assertTrue(restore().apply { assertEquals("settings", it) })
        assertEquals("new workflows", workflows.readText())
        workflows.writeText("subsequent edit")
        assertFalse(restore().apply { fail("Must not replay") })
        assertEquals("subsequent edit", workflows.readText())
    }

    @Test fun oldBackupLeavesWorkflowDatabaseAndSidecarsUntouched() = runBlocking {
        seed(); stage(includeWorkflows = false)
        assertTrue(restore().apply { })
        assertEquals("old ${workflows.name}", workflows.readText())
        for (suffix in listOf("-wal", "-shm", "-journal"))
            assertEquals("old $suffix", File(workflows.path + suffix).readText())
    }

    @Test fun unrelatedDatabaseCannotBeInstalled() = runBlocking {
        seed()
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/database/other.db"), "unknown database")
        restore.publish(staging)
        try { restore.apply { fail("No settings") }; fail("Expected rejection") }
        catch (_: RestoreFailedException) { }
        assertFalse(File(main.parentFile, "other.db").exists())
        assertEquals("old ${workflows.name}", workflows.readText())
    }

    private class ProcessDeath : Error()
}
