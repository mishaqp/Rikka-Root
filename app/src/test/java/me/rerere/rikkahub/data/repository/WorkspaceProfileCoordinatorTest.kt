package me.rerere.rikkahub.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceProfileKind
import me.rerere.workspace.WorkspaceProfileState
import me.rerere.workspace.WorkspaceProfileStatus
import me.rerere.workspace.WorkspaceProfilesSnapshot
import me.rerere.workspace.WorkspaceShellStatus
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
        advanceUntilIdle()

        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        assertFalse(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) }
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `cancel preserves unrelated background processes`() = runTest {
        val repository = repository("one")
        coEvery { repository.prepareProfile("one", WorkspaceProfileKind.NODE) } coAnswers { awaitCancellation() }
        coEvery { repository.cancelProfile("one", WorkspaceProfileKind.NODE) } returns
            WorkspaceProfileState(WorkspaceProfileKind.NODE, WorkspaceProfileStatus.CANCELLED)
        val coordinator = WorkspaceProfileCoordinator(repository, backgroundScope)
        coordinator.states("one")
        advanceUntilIdle()
        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.NODE))
        advanceUntilIdle()

        coordinator.cancel("one")
        advanceUntilIdle()

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
        advanceUntilIdle()

        assertTrue(coordinator.prepare("one", WorkspaceProfileKind.PYTHON))
        assertTrue(coordinator.prepare("two", WorkspaceProfileKind.NODE))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.prepareProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 1) { repository.prepareProfile("two", WorkspaceProfileKind.NODE) }
        firstGate.complete(Unit)
        secondGate.complete(Unit)
        advanceUntilIdle()
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
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.interruptProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 1) { repository.verifyProfile("one", WorkspaceProfileKind.PYTHON) }
        coVerify(exactly = 0) { repository.prepareProfile(any(), any()) }
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
