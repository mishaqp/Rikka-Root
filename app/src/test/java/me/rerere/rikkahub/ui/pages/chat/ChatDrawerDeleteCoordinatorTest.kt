package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatDrawerDeleteCoordinatorTest {
    @Test fun undoKeepsConversationAndAttachments() = runTest {
        val coordinator = ChatDrawerDeleteCoordinator()
        var deletions = 0
        coordinator.delete(Uuid.random(), offerUndo = { true }, commit = { deletions++ })
        assertEquals(0, deletions)
        assertTrue(coordinator.pendingIds.value.isEmpty())
    }

    @Test fun dismissalCommitsOnceAfterOpportunity() = runTest {
        val coordinator = ChatDrawerDeleteCoordinator()
        val decision = CompletableDeferred<Boolean>()
        val id = Uuid.random()
        var deletions = 0
        val job = launch { coordinator.delete(id, { decision.await() }, { deletions++ }) }
        runCurrent()
        assertEquals(setOf(id), coordinator.pendingIds.value)
        assertEquals(0, deletions)
        coordinator.delete(id, { false }, { deletions++ })
        decision.complete(false)
        job.join()
        assertEquals(1, deletions)
        assertTrue(coordinator.pendingIds.value.isEmpty())
    }

    @Test fun closingDrawerCompletesPendingCleanup() = runTest {
        val coordinator = ChatDrawerDeleteCoordinator()
        var deletions = 0
        val job = launch {
            coordinator.delete(Uuid.random(), { CompletableDeferred<Boolean>().await() }, { deletions++ })
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, deletions)
        assertTrue(coordinator.pendingIds.value.isEmpty())
    }

    @Test fun failedCleanupDoesNotLeaveHiddenRow() = runTest {
        val coordinator = ChatDrawerDeleteCoordinator()
        try {
            coordinator.delete(Uuid.random(), { false }, { error("storage unavailable") })
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertTrue(coordinator.pendingIds.value.isEmpty())
        }
    }
}
