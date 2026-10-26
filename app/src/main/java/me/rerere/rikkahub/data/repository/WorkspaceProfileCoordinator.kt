package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceProfileKind
import me.rerere.workspace.WorkspaceProfileState
import me.rerere.workspace.WorkspaceProfileStatus
import me.rerere.workspace.WorkspaceProfilesSnapshot

/** Application-scoped operations survive ViewModel recreation and device rotation. */
class WorkspaceProfileCoordinator(
    private val repository: WorkspaceRepository,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val runtimes = mutableMapOf<String, Runtime>()

    fun states(id: String): StateFlow<WorkspaceProfilesSnapshot> = runtime(id).flow.asStateFlow()

    fun prepare(id: String, kind: WorkspaceProfileKind): Boolean = startOperation(id, kind, prepare = true)

    fun verify(id: String, kind: WorkspaceProfileKind): Boolean = startOperation(id, kind, prepare = false)

    /** Cancels only this operation's Job; never stops unrelated background processes. */
    fun cancel(id: String) {
        synchronized(lock) { runtimes[id]?.job }?.cancel()
    }

    fun refresh(id: String) {
        val runtime = runtime(id)
        val revision = synchronized(lock) {
            if (runtime.deleted || runtime.job?.isCompleted == false) return
            runtime.revision
        }
        scope.launch { publishSnapshot(id, runtime, revision) }
    }

    private fun startOperation(id: String, kind: WorkspaceProfileKind, prepare: Boolean): Boolean {
        val runtime = runtime(id)
        synchronized(lock) {
            // A cancelling Job still owns its processes and metadata until cleanup finishes.
            if (runtime.deleted || runtime.job?.isCompleted == false) return false
            runtime.revision++
            runtime.flow.value = runtime.flow.value.with(
                runtime.flow.value.get(kind).operationCopy(
                    if (prepare) WorkspaceProfileStatus.PREPARING else WorkspaceProfileStatus.CHECKING
                )
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runOperation(id, runtime, kind, prepare)
            }
            runtime.job = job
            job.start()
            return true
        }
    }

    private suspend fun runOperation(
        id: String,
        runtime: Runtime,
        kind: WorkspaceProfileKind,
        prepare: Boolean,
    ) {
        try {
            if (prepare) repository.prepareProfile(id, kind) else repository.verifyProfile(id, kind)
        } catch (cancelled: CancellationException) {
            settleProfile(runtime, kind, WorkspaceProfileStatus.CANCELLED, "Операция остановлена пользователем.") {
                repository.cancelProfile(id, kind)
            }
            throw cancelled
        } catch (interrupted: InterruptedException) {
            settleProfile(runtime, kind, WorkspaceProfileStatus.INTERRUPTED, "Операция прервана; повторите проверку.") {
                repository.interruptProfile(id, kind)
            }
        } catch (error: Throwable) {
            settleProfile(runtime, kind, WorkspaceProfileStatus.ERROR, error.message ?: "Операция профиля завершилась с ошибкой.") {
                repository.failProfile(id, kind, error.message ?: "Операция профиля завершилась с ошибкой.")
            }
        } finally {
            finishOperation(id, runtime)
        }
    }

    private fun runtime(id: String): Runtime = synchronized(lock) {
        runtimes.getOrPut(id) {
            Runtime().also { runtime ->
                runtime.job = scope.launch(start = CoroutineStart.LAZY) { initialize(id, runtime) }
                observeDeletion(id, runtime)
                runtime.job?.start()
            }
        }
    }

    private fun observeDeletion(id: String, runtime: Runtime) {
        scope.launch {
            repository.listFlow().first { workspaces -> workspaces.none { it.id == id } }
            val operation = synchronized(lock) {
                runtime.deleted = true
                runtime.revision++
                runtime.flow.value = WorkspaceProfilesSnapshot.empty()
                runtime.job
            }
            operation?.cancelAndJoin()
            synchronized(lock) {
                if (runtimes[id] === runtime) runtimes.remove(id)
            }
        }
    }

    private suspend fun initialize(id: String, runtime: Runtime) {
        try {
            val snapshot = repository.profileStates(id)
            runtime.flow.value = snapshot
            val interruptedKinds = WorkspaceProfileKind.entries.filter {
                snapshot.get(it).status in ACTIVE_STATUSES
            }
            // After process death there is no owned Job to resume: mark interruption and verify only.
            for (kind in interruptedKinds) {
                repository.interruptProfile(id, kind)
                runtime.flow.value = runtime.flow.value.with(
                    runtime.flow.value.get(kind).operationCopy(WorkspaceProfileStatus.CHECKING)
                )
                repository.verifyProfile(id, kind)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val snapshot = try {
                    repository.profileStates(id)
                } catch (_: Exception) {
                    runtime.flow.value
                }
                for (kind in WorkspaceProfileKind.entries) {
                    if (snapshot.get(kind).status in ACTIVE_STATUSES || runtime.flow.value.get(kind).status in ACTIVE_STATUSES) {
                        settleProfile(runtime, kind, WorkspaceProfileStatus.CANCELLED, "Проверка остановлена пользователем.") {
                            repository.cancelProfile(id, kind)
                        }
                    }
                }
            }
            throw cancelled
        } catch (_: Throwable) {
            // A missing workspace or unreadable state must not restart installation or crash the page.
        } finally {
            finishOperation(id, runtime)
        }
    }

    private suspend fun finishOperation(id: String, runtime: Runtime) {
        val owner = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val localOutcome = runtime.flow.value
            try {
                publishSnapshot(id, runtime)
            } finally {
                synchronized(lock) {
                    if (runtimes[id] === runtime && runtime.job === owner) {
                        if (!runtime.deleted) {
                            var snapshot = runtime.flow.value
                            for (kind in WorkspaceProfileKind.entries) {
                                if (snapshot.get(kind).status !in ACTIVE_STATUSES) continue
                                val fallback = localOutcome.get(kind).takeIf {
                                    it.status in setOf(WorkspaceProfileStatus.CANCELLED, WorkspaceProfileStatus.INTERRUPTED, WorkspaceProfileStatus.ERROR)
                                } ?: snapshot.get(kind).operationCopy(WorkspaceProfileStatus.ERROR).copy(
                                    errorReason = "Операция завершилась без подтверждённого состояния. Повторите проверку профиля.",
                                )
                                snapshot = snapshot.with(fallback)
                            }
                            runtime.flow.value = snapshot
                        }
                        runtime.job = null
                    }
                }
            }
        }
    }

    private suspend fun settleProfile(
        runtime: Runtime,
        kind: WorkspaceProfileKind,
        status: WorkspaceProfileStatus,
        reason: String,
        persist: suspend () -> WorkspaceProfileState?,
    ) = withContext(NonCancellable) {
        val persisted = try {
            persist()
            true
        } catch (_: Exception) {
            false
        }
        synchronized(lock) {
            if (!runtime.deleted && runtime.flow.value.get(kind).status in ACTIVE_STATUSES) {
                runtime.flow.value = runtime.flow.value.with(
                    runtime.flow.value.get(kind).operationCopy(status).copy(
                        errorReason = reason + if (persisted) "" else " Состояние не удалось сохранить; повторите проверку после завершения замены Rootfs.",
                    ),
                )
            }
        }
    }

    private suspend fun publishSnapshot(id: String, runtime: Runtime, expectedRevision: Long? = null) {
        try {
            val snapshot = if (synchronized(lock) { runtime.deleted }) WorkspaceProfilesSnapshot.empty()
            else repository.profileStates(id)
            synchronized(lock) {
                if (runtime.deleted) {
                    runtime.flow.value = WorkspaceProfilesSnapshot.empty()
                } else if (expectedRevision == null ||
                    (runtime.revision == expectedRevision && runtime.job?.isCompleted != false)
                ) {
                    runtime.flow.value = snapshot
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deletion can win the race with a final refresh; no state file may be recreated.
        }
    }

    private fun WorkspaceProfileState.operationCopy(status: WorkspaceProfileStatus) = copy(
        status = status,
        version = null,
        packageManagerVersion = null,
        executablePath = null,
        packageManagerPath = null,
        exitCode = null,
        diagnostic = null,
        errorReason = null,
        updatedAt = System.currentTimeMillis(),
    )

    private class Runtime {
        val flow = MutableStateFlow(WorkspaceProfilesSnapshot.empty())
        var job: Job? = null
        var revision = 0L
        var deleted = false
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(
            WorkspaceProfileStatus.PREPARING,
            WorkspaceProfileStatus.CHECKING,
        )
    }
}
