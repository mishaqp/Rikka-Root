package me.rerere.rikkahub.root

import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootJournalOutcomeAuditTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `journal preserves native headless failure codes after reopening`() = runBlocking {
        for (code in listOf("root_interaction_required", "root_cleanup_unconfirmed", "root_execution_indeterminate")) {
            val directory = folder.newFolder()
            val store = RootAccessStore(directory)
            val id = store.beginCommand("owner", "printf diagnostic")
            store.finishCommand(id, null, code)
            assertEquals(code, store.entries.value.single().status)
            assertEquals(code, RootAccessStore(directory).entries.value.single().status)
        }
    }

    @Test fun `missing session and unavailable cleanup have distinct actionable explanations`() {
        assertTrue(rootCommandStatusMessage("root_interaction_required").contains("настройк", ignoreCase = true))
        val unavailable = rootCommandStatusMessage("root_cleanup_unavailable")
        assertTrue(unavailable, unavailable.contains("не выполнена"))
        assertTrue(unavailable, unavailable.contains("setsid"))
        assertTrue(rootCommandStatusMessage("root_cleanup_unconfirmed").contains("не подтвержден", ignoreCase = true))
        assertTrue(rootCommandStatusMessage("root_execution_indeterminate").contains("неизвестен"))
    }

    @Test fun `exception after launch leaves an indeterminate journal outcome`() = runBlocking {
        val directory = folder.newFolder()
        val store = RootAccessStore(directory)
        val lostResult = IOException("transport result unavailable")
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), any(), any(), any()) } throws lostResult

        try {
            buildRootTool(manager, store, "owner").execute(buildJsonObject { put("command", "printf diagnostic") })
            fail("A lost transport result must propagate to the generation checkpoint handler")
        } catch (error: IOException) {
            assertSame(lostResult, error)
        }
        assertEquals("root_execution_indeterminate", store.entries.value.single().status)
        assertEquals("root_execution_indeterminate", RootAccessStore(directory).entries.value.single().status)
        assertNull(store.entries.value.single().exitCode)
    }
}
