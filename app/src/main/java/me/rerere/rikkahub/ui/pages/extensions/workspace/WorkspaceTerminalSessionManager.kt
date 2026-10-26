package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.RootfsBusyException
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceRootfsLease
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val workspaceManager: WorkspaceManager,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()
    private val ownedTabs = mutableMapOf<String, MutableMap<Long, WorkspaceTerminalTab>>()
    private val closingRoots = mutableSetOf<String>()

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = true, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun createTab(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = false, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            requestStop(tab)
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        if (!closingRoots.add(root)) throw RootfsBusyException("Закрытие терминалов этой рабочей папки уже выполняется.")
        var previousState: WorkspaceTerminalTabsState? = null
        var stopping = emptyList<WorkspaceTerminalTab>()
        try {
            // A closed tab can still own a process even after it disappeared from the page.
            creationJobs[root]?.cancelAndJoin()
            previousState = workspaceStates.getAndUpdate { states -> states - root }[root]
            stopping = (previousState?.tabs.orEmpty() + ownedTabs[root]?.values.orEmpty()).distinctBy { it.id }
            stopping.forEach(::requestStop)
            withTimeout(TERMINAL_STOP_TIMEOUT_MS) {
                stopping.forEach { it.finishedSignal.await() }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                "Остановка терминала не подтверждена. Rootfs не изменялся; закройте терминал и повторите попытку.",
                error,
            )
        } finally {
            val liveTabs = stopping.filterNot { it.finishedSignal.isCompleted }
            if (liveTabs.isNotEmpty()) {
                updateState(root) { current ->
                    val tabs = (current.tabs + liveTabs).distinctBy { it.id }
                    current.copy(
                        tabs = tabs,
                        selectedTabId = previousState?.selectedTabId?.takeIf { id -> tabs.any { it.id == id } }
                            ?: tabs.first().id,
                        readiness = WorkspaceTerminalReadiness.Ready,
                        isCreating = false,
                        nextTabNumber = maxOf(current.nextTabNumber, previousState?.nextTabNumber ?: 1),
                    )
                }
            }
            closingRoots.remove(root)
        }
    }

    private fun requestStop(tab: WorkspaceTerminalTab) {
        val view = tab.client.terminalView
        tab.client.terminalView = null
        view?.attachSession(null)
        if (tab.finishedSignal.isCompleted) return
        if (tab.session.pid > 0) {
            tab.session.finishIfRunning()
        } else {
            // Termux sessions start lazily on first resize. PID 0 is not an owned child, and
            // finishIfRunning() would treat it as running and pass 0 to Os.kill().
            tab.client.onSessionFinished(tab.session)
        }
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) {
        if (root in creationJobs || root in closingRoots) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(root = root, onlyIfEmpty = onlyIfEmpty, shellCompatibilityMode = shellCompatibilityMode)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true, error = null) }

        var lease: WorkspaceRootfsLease? = null
        val finishedSignal = CompletableDeferred<Unit>()
        var leaseOwnedByTab = false
        try {
            try {
                // Recovery may rename directories and restore metadata. Keep it off Main, and
                // retain the lease even if cancellation wins while this context returns.
                withContext(Dispatchers.IO) { lease = workspaceManager.acquireTerminalLease(root) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateState(root) { it.copy(isCreating = false, error = error.message ?: "Не удалось открыть среду Rootfs.") }
                Log.i(TAG, "Terminal admission rejected for workspace $root: ${error.message}")
                return@withContext
            }
            val terminalLease = checkNotNull(lease)
            val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
                true
            } else {
                try {
                    withContext(Dispatchers.IO) {
                        if (!workspaceRootfsReady(appContext, root)) {
                            false
                        } else {
                            prepareWorkspaceTerminalSession(appContext, root)
                            true
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                    false
                }
            }

            if (!prepared) {
                updateState(root) {
                    it.copy(
                        readiness = WorkspaceTerminalReadiness.NotInstalled,
                        isCreating = false,
                    )
                }
                return@withContext
            }

            val tabId = nextTabId.getAndIncrement()
            val tabNumber = currentState(root).nextTabNumber
            val client = WorkspaceTerminalSessionClient(
                context = appContext,
                onTitleUpdated = { title -> updateTitle(root, tabId, title) },
                onFinished = {
                    terminalLease.close()
                    finishedSignal.complete(Unit)
                    ownedTabs[root]?.let { tabs ->
                        tabs.remove(tabId)
                        if (tabs.isEmpty()) ownedTabs.remove(root)
                    }
                    markFinished(root = root, tabId = tabId)
                },
            )
            val session = try {
                createWorkspaceTerminalSession(
                    context = appContext,
                    root = root,
                    client = client,
                    shellCompatibilityMode = shellCompatibilityMode,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to create terminal for workspace $root", error)
                null
            }

            if (session == null) {
                updateState(root) { it.copy(isCreating = false) }
                return@withContext
            }

            val tab = WorkspaceTerminalTab(
                id = tabId,
                number = tabNumber,
                session = session,
                client = client,
                finishedSignal = finishedSignal,
                finished = finishedSignal.isCompleted,
            )
            if (!finishedSignal.isCompleted) ownedTabs.getOrPut(root) { mutableMapOf() }[tabId] = tab
            updateState(root) { state ->
                state.copy(
                    tabs = state.tabs + tab,
                    selectedTabId = tab.id,
                    readiness = WorkspaceTerminalReadiness.Ready,
                    isCreating = false,
                    nextTabNumber = tabNumber + 1,
                )
            }
            leaseOwnedByTab = true
        } finally {
            if (!leaseOwnedByTab) {
                lease?.close()
                finishedSignal.complete(Unit)
                updateState(root) { it.copy(isCreating = false) }
            }
        }
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(title = normalizedTitle) else tab
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
        const val TERMINAL_STOP_TIMEOUT_MS = 10_000L
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
    val error: String? = null,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val finishedSignal: CompletableDeferred<Unit>,
    val title: String? = null,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
