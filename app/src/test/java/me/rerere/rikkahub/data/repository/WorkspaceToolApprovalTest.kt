package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.*
import org.junit.Test

class WorkspaceToolApprovalTest {
    @Test fun concurrentAlwaysGrantsRetainBothOverridesAndStayInOneWorkspace() = runBlocking {
        val dao = InMemoryWorkspaceDao()
        val mutex = Mutex()
        val results = listOf("workspace_shell", "workspace_background_start").map { name ->
            async { updateWorkspaceToolApproval(dao, mutex, "a", name, false) }
        }.awaitAll()
        assertTrue(results.all { it })
        assertEquals(mapOf("workspace_shell" to false, "workspace_background_start" to false), dao.rows.getValue("a").toolApprovalOverrides())
        assertTrue(dao.rows.getValue("b").toolApprovalOverrides().isEmpty())
        assertFalse(updateWorkspaceToolApproval(dao, mutex, "deleted", "workspace_shell", false))
    }

    private class InMemoryWorkspaceDao : WorkspaceDAO {
        val rows = listOf("a", "b").associateWith { WorkspaceEntity(it, it, it, createdAt = 1, updatedAt = 1) }.toMutableMap()
        override suspend fun getById(id: String): WorkspaceEntity? = rows[id].also { yield() }
        override suspend fun upsert(workspace: WorkspaceEntity) { rows[workspace.id] = workspace }
        override fun listFlow(): Flow<List<WorkspaceEntity>> = error("unused")
        override suspend fun getAll(): List<WorkspaceEntity> = error("unused")
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int = error("unused")
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int = error("unused")
        override suspend fun deleteById(id: String): Int = error("unused")
    }
}
