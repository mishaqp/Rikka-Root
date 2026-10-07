package me.rerere.rikkahub.data.preferences

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.root.RootAccessStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolApprovalPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun grantsAndTogglesPersistButWorkspaceNeverEntersGlobalList() = runBlocking {
        val directory = folder.newFolder()
        ToolApprovalTestStore(directory).use { fixture ->
            fixture.preferences.setYolo(true)
            fixture.preferences.setAskAfterWebContent(true)
            listOf("root_exec", "mcp__test__write", "workspace_shell", "ask_user").map { name ->
                async { fixture.preferences.grantAlways(name) }
            }.awaitAll()
        }
        ToolApprovalTestStore(directory).use { fixture ->
            assertTrue(fixture.preferences.currentYolo())
            assertTrue(fixture.preferences.currentAskAfterWebContent())
            assertEquals(setOf("root_exec", "mcp__test__write"), fixture.preferences.current())
            fixture.preferences.revoke("root_exec")
            assertEquals(setOf("mcp__test__write"), fixture.preferences.current())
        }
    }

    @Test fun legacyPermissionsMigrateOnceWithoutResurrectingRevokedGrants() = runBlocking {
        val legacy = RootAccessStore(folder.newFolder())
        legacy.setAutoApprove(true)
        legacy.grantAlways("root_exec")
        legacy.grantAlways("workspace_shell")
        val directory = folder.newFolder()
        ToolApprovalTestStore(directory, legacy).use { fixture ->
            assertTrue(fixture.preferences.currentYolo())
            assertFalse(fixture.preferences.currentAskAfterWebContent())
            assertEquals(setOf("root_exec"), fixture.preferences.current())
            fixture.preferences.setYolo(false)
            fixture.preferences.revokeAll()
        }
        ToolApprovalTestStore(directory, legacy).use { fixture ->
            assertFalse(fixture.preferences.currentYolo())
            assertTrue(fixture.preferences.current().isEmpty())
        }
    }
}
