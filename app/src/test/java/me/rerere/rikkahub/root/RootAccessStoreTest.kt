package me.rerere.rikkahub.root

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootAccessStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedDisableSaveStillRevokesInMemoryAndNeverLoadsBackupPermission() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutomatic("assistant-a", true)
        val file = java.io.File(directory, "root-access.json")
        assertTrue(file.renameTo(java.io.File(temp.root, "old-enabled-state")))
        assertTrue(file.mkdir())
        java.io.File(file, "block replacement").writeText("obstruct atomic replace")
        try {
            store.setAutomatic("assistant-a", false)
            fail("failed persistent revocation must be reported")
        } catch (_: java.io.IOException) {}
        assertFalse(store.isAutomatic("assistant-a"))
        assertTrue(store.enabledAssistants.value.isEmpty())
        assertFalse(RootAccessStore(directory).isAutomatic("assistant-a"))
    }

    @Test fun permissionsAreOffByDefaultAndPersistPerAssistant() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        assertFalse(store.isAutomatic("assistant-a"))
        store.setAutomatic("assistant-a", true)
        assertTrue(store.isAutomatic("assistant-a"))
        assertFalse(store.isAutomatic("assistant-b"))
        assertEquals(setOf("assistant-a"), RootAccessStore(directory).enabledAssistants.value)
        store.setAutomatic("assistant-a", false)
        assertFalse(RootAccessStore(directory).isAutomatic("assistant-a"))
        assertTrue(RootAccessStore(temp.newFolder()).enabledAssistants.value.isEmpty())
    }

    @Test fun corruptedStateIsAllOff() = runBlocking {
        val directory = temp.newFolder()
        val file = java.io.File(directory, "root-access.json")
        file.writeText("{\"enabledAssistants\":[\"assistant-a\"],broken")
        val reopened = RootAccessStore(directory)
        assertTrue(reopened.enabledAssistants.value.isEmpty())
        assertTrue(reopened.entries.value.isEmpty())
    }

    @Test fun journalMasksArgumentsAndUnknownStatusWithoutOutputFields() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        val id = store.beginCommand("assistant-a", "printf 'unlabelled-private-value'; curl -H 'Authorization: Bearer private-token' https://private.host/path")
        assertFalse("begin must durably record the pending command", RootAccessStore(directory).entries.value.isEmpty())
        val started = RootAccessStore(directory).entries.value.single()
        assertEquals(id, started.id)
        assertEquals("running", started.status)
        assertTrue(started.timestampMs > 0)
        store.finishCommand(id, 9, "completed")
        val finished = RootAccessStore(directory).entries.value.single()
        assertEquals(9, finished.exitCode)
        assertEquals("completed", finished.status)
        assertFalse(finished.command.contains("unlabelled-private-value"))
        assertFalse(finished.command.contains("private-token"))
        assertFalse(finished.command.contains("private.host"))
        store.finishCommand(id, null, "exception containing another-private-value")
        assertEquals("failed", store.entries.value.single().status)
        val disk = directory.listFiles()!!.single().readText()
        assertFalse(disk.contains("another-private-value"))
        assertFalse(disk.contains("stdout"))
        assertFalse(disk.contains("stderr"))
    }

    @Test fun concurrentJournalEntriesStayBoundedAndKeepOutcomes() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        (0 until 205).map { index ->
            async {
                val id = store.beginCommand("assistant-a", "echo private-$index")
                store.finishCommand(id, index, "completed")
            }
        }.awaitAll()
        val entries = RootAccessStore(directory).entries.value
        assertEquals(200, entries.size)
        assertEquals(200, entries.map { it.id }.distinct().size)
        assertTrue(entries.all { it.status == "completed" })
        assertEquals(200, entries.map { it.exitCode }.distinct().size)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun failedPersistenceCannotPretendToBeginCommand() = runBlocking {
        val store = RootAccessStore(temp.newFile())
        try {
            store.beginCommand("assistant-a", "id")
            fail("must not permit an unjournaled launch")
        } catch (_: java.io.IOException) {}
        assertTrue(store.entries.value.isEmpty())
    }
}
