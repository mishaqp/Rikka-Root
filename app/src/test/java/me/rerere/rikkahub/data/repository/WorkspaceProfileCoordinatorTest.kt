package me.rerere.rikkahub.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceProfileKind
import me.rerere.workspace.WorkspaceProfileState
import me.rerere.workspace.WorkspaceProfileStatus
import me.rerere.workspace.WorkspaceProfilesSnapshot
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProfileCoordinatorTest {
    @Test
    fun `repeated tap does not duplicate preparation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repository("one")
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            gate.await()
            readyState(WorkspaceProfileKind.PYTHON)
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        coordinator.states("one")
        runCurrent()

        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        assertFalse(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        runCurrent()
        coVerify(exactly = 1) { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) }
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `cancel preserves unrelated background processes`() = runTest {
        val repository = repository("one")
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.NODE) } coAnswers { awaitCancellation() }
        coEvery { repository.cancelProfile("one", WorkspaceProfileKind.NODE) } returns
            WorkspaceProfileState(WorkspaceProfileKind.NODE, WorkspaceProfileStatus.CANCELLED)
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        coordinator.states("one")
        runCurrent()
        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.NODE))
        runCurrent()

        coordinator.cancel("one")
        runCurrent()

        coVerify(exactly = 1) { repository.cancelProfile("one", WorkspaceProfileKind.NODE) }
        coVerify(exactly = 0) { repository.stopBackground(any(), any()) }
    }

    @Test
    fun `two workspaces run independently`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val repository = repository("one", "two")
        coEvery { repository.prepareProfile("one", any()) } coAnswers {
            firstGate.await()
            readyState(WorkspaceProfileKind.PYTHON)
        }
        coEvery { repository.prepareProfile("two", any()) } coAnswers {
            secondGate.await()
            readyState(WorkspaceProfileKind.NODE)
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        coordinator.states("one")
        coordinator.states("two")
        runCurrent()

        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        assertTrue(coordinator.prepare("two", WorkspaceProfileKind.NODE))
        runCurrent()
        coVerify(exactly = 1) { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 1) { repository.prepareProfile("two", WorkspaceProfileKind.NODE) }
        firstGate.complete(Unit)
        secondGate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `recovery marks interruption and verifies without resuming installation`() = runTest {
        val repository = repository("one")
        var snapshot = WorkspaceProfilesSnapshot(
            rootfsStamp = "same-rootfs",
            python = WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.PREPARING),
        )
        coEvery { repository.profileStates("one") } answers { snapshot }
        coEvery { repository.interruptProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.copy(
                python = WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.INTERRUPTED)
            )
            snapshot.python
        }
        coEvery { repository.verifyProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.copy(python = readyState(WorkspaceProfileKind.PYTHON))
            snapshot.python
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)

        coordinator.states("one")
        runCurrent()

        coVerify(exactly = 1) { repository.interruptProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 1) { repository.verifyProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 0) { repository.prepareProfile(any(), any()) }
    }

    @Test
    fun `cancelled operation owns workspace until its cleanup completes`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val repository = repository("one")
        var snapshot = WorkspaceProfilesSnapshot.empty("rootfs")
        coEvery { repository.profileStates("one") } answers { snapshot }
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.PREPARING))
            awaitCancellation()
        }
        coEvery { repository.cancelProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            cleanup.await()
            snapshot = snapshot.with(WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.CANCELLED))
            snapshot.python
        }
        coEvery { repository.verifyProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(readyState(WorkspaceProfileKind.PYTHON))
            snapshot.python
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        val states = coordinator.states("one")
        runCurrent()
        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        runCurrent()
        coordinator.cancel("one")
        runCurrent()
        try {
            assertFalse("Cancellation cleanup must finish before the next operation starts", coordinator.verify("one", WorkspaceProfileKind.PYTHON))
        } finally {
            cleanup.complete(Unit)
            runCurrent()
        }
        assertEquals(WorkspaceProfileStatus.CANCELLED, states.value.python.status)
        assertTrue(coordinator.verify("one", WorkspaceProfileKind.PYTHON))
        runCurrent()
        assertEquals(WorkspaceProfileStatus.READY, states.value.python.status)
    }

    @Test
    fun `recovery verification can be cancelled without leaving checking stuck`() = runTest {
        val repository = repository("one")
        var snapshot = WorkspaceProfilesSnapshot.empty("rootfs").with(
            WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.PREPARING)
        )
        coEvery { repository.profileStates("one") } answers { snapshot }
        coEvery { repository.interruptProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.INTERRUPTED))
            snapshot.python
        }
        coEvery { repository.verifyProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.CHECKING))
            awaitCancellation()
        }
        coEvery { repository.cancelProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(WorkspaceProfileState(WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.CANCELLED))
            snapshot.python
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        val states = coordinator.states("one")
        runCurrent()
        assertEquals(WorkspaceProfileStatus.CHECKING, states.value.python.status)
        coordinator.cancel("one")
        runCurrent()
        assertEquals(WorkspaceProfileStatus.CANCELLED, states.value.python.status)
        coVerify(exactly = 0) { repository.prepareProfile(any(), any()) }
    }

    @Test
    fun `refresh cannot replace active status with a stale stored snapshot`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repository("one")
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            gate.await()
            readyState(WorkspaceProfileKind.PYTHON)
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        val states = coordinator.states("one")
        runCurrent()
        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        runCurrent()
        try {
            coordinator.refresh("one")
            runCurrent()
            assertEquals(WorkspaceProfileStatus.PREPARING, states.value.python.status)
        } finally {
            gate.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun `slow refresh cannot overwrite a subsequently completed operation`() = runTest {
        val staleRead = CompletableDeferred<Unit>()
        val repository = repository("one")
        var snapshot = WorkspaceProfilesSnapshot.empty("rootfs")
        var holdNextRead = false
        coEvery { repository.profileStates("one") } coAnswers {
            val captured = snapshot
            if (holdNextRead) {
                holdNextRead = false
                staleRead.await()
            }
            captured
        }
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) } coAnswers {
            snapshot = snapshot.with(readyState(WorkspaceProfileKind.PYTHON))
            snapshot.python
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        val states = coordinator.states("one")
        runCurrent()
        holdNextRead = true
        coordinator.refresh("one")
        runCurrent()
        try {
            assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
            runCurrent()
            assertEquals(WorkspaceProfileStatus.READY, states.value.python.status)
            staleRead.complete(Unit)
            runCurrent()
            assertEquals(WorkspaceProfileStatus.READY, states.value.python.status)
        } finally {
            staleRead.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun `deletion clears a previously ready profile without another operation`() = runTest {
        val repository = repository("one")
        val workspaces = MutableStateFlow(listOf(WorkspaceEntity("one", "one", "one", WorkspaceShellStatus.READY.name, 0L, 0L)))
        every { repository.listFlow() } returns workspaces
        coEvery { repository.profileStates("one") } answers {
            if (workspaces.value.isEmpty()) WorkspaceProfilesSnapshot.empty()
            else WorkspaceProfilesSnapshot.empty("rootfs").with(readyState(WorkspaceProfileKind.PYTHON))
        }
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        val states = coordinator.states("one")
        runCurrent()
        assertEquals(WorkspaceProfileStatus.READY, states.value.python.status)
        workspaces.value = emptyList()
        runCurrent()
        assertEquals(WorkspaceProfileStatus.NOT_INSTALLED, states.value.python.status)
    }

    private fun repository(vararg ids: String): WorkspaceRepository {
        val entities = ids.map { id ->
            WorkspaceEntity(id, id, id, WorkspaceShellStatus.READY.name, 0L, 0L)
        }
        return mockk {
            every { listFlow() } returns MutableStateFlow(entities)
            ids.forEach { id ->
                coEvery { profileStates(id) } returns WorkspaceProfilesSnapshot.empty("rootfs")
            }
        }
    }

    private fun readyState(kind: WorkspaceProfileKind) = WorkspaceProfileState(
        kind = kind,
        status = WorkspaceProfileStatus.READY,
        version = if (kind == WorkspaceProfileKind.PYTHON) "Python 3.12.3" else "v24.22.1",
    )
}
