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

    @Test fun webGuardPersistsPerConversationWithoutChangingPermissionsOrJournal() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutoApprove(true)
        store.grantAlways("root_exec")
        val original = store.permissions.value
        store.markWebContent("web-chat")
        assertEquals(original, store.permissions.value)
        assertFalse(store.isAllowed("root_exec", "web-chat"))
        assertTrue(store.isAllowed("root_exec", "fresh-chat"))
        val id = store.beginCommand("assistant", "id")
        store.finishCommand(id, 0, "completed")
        val reopened = RootAccessStore(directory)
        assertFalse(reopened.isAllowed("root_exec", "web-chat"))
        assertTrue(reopened.isAllowed("root_exec", "fresh-chat"))
        assertEquals(original, reopened.permissions.value)
        assertEquals(1, reopened.entries.value.size)
        assertTrue(directory.deleteRecursively())
        val cleared = RootAccessStore(directory)
        assertTrue(cleared.webContentConversations.value.isEmpty())
        assertFalse(cleared.isAllowed("root_exec"))
    }

    @Test fun failedDisableSaveStillRevokesInMemoryAndNeverLoadsBackupPermission() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutoApprove(true)
        val file = java.io.File(directory, "root-access.json")
        assertTrue(file.renameTo(java.io.File(temp.root, "old-enabled-state")))
        assertTrue(file.mkdir())
        java.io.File(file, "block replacement").writeText("obstruct atomic replace")
        try {
            store.setAutoApprove(false)
            fail("failed persistent revocation must be reported")
        } catch (_: java.io.IOException) {}
        assertFalse(store.isAllowed("root_exec"))
        assertFalse(store.permissions.value.autoApproveAll)
        assertFalse(RootAccessStore(directory).isAllowed("root_exec"))
    }

    @Test fun permissionsAreOffByDefaultAndApplyGlobally() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        assertFalse(store.isAllowed("root_exec"))
        store.setAutoApprove(true)
        assertTrue(store.isAllowed("root_exec"))
        assertTrue("Explicit device permission applies to every side-effect tool", store.isAllowed("workspace_shell"))
        assertTrue(RootAccessStore(directory).permissions.value.autoApproveAll)
        store.setAutoApprove(false)
        assertFalse(RootAccessStore(directory).isAllowed("root_exec"))
        assertFalse(RootAccessStore(temp.newFolder()).permissions.value.autoApproveAll)
    }

    @Test fun corruptedStateIsAllOff() = runBlocking {
        val directory = temp.newFolder()
        val file = java.io.File(directory, "root-access.json")
        file.writeText("{\"enabledAssistants\":[\"assistant-a\"],broken")
        val reopened = RootAccessStore(directory)
        assertFalse(reopened.permissions.value.autoApproveAll)
        assertTrue(reopened.entries.value.isEmpty())
    }

    @Test fun oldAssistantGrantsAreIgnoredButJournalSurvives() = runBlocking {
        val directory = temp.newFolder()
        java.io.File(directory, "root-access.json").writeText("""{"enabledAssistants":["assistant-a"],"entries":[{"id":"old","assistantId":"assistant-a","timestampMs":1,"command":"id -u","exitCode":0,"status":"completed"}]}""")
        val store = RootAccessStore(directory)
        assertFalse(store.isAllowed("root_exec"))
        assertTrue(store.permissions.value.alwaysAllow.isEmpty())
        assertEquals("old", store.entries.value.single().id)
        store.grantAlways("root_exec")
        assertTrue(RootAccessStore(directory).isAllowed("root_exec"))
        store.setAutoApprove(true)
        store.disableAllAutomaticApprovals()
        assertFalse(RootAccessStore(directory).isAllowed("root_exec"))
        assertEquals(1, store.entries.value.size)
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
