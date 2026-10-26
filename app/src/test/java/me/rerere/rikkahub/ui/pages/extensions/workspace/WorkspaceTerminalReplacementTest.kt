package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.RootfsBusyException
import me.rerere.workspace.RootfsInstallException
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceTerminalReplacementTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<AppScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        // Only the Android/native process boundary is replaced; admission and ownership are real.
        mockkStatic(::createWorkspaceTerminalSession, ::prepareWorkspaceTerminalSession, ::workspaceRootfsReady)
        every { workspaceRootfsReady(any(), any()) } returns true
        every { prepareWorkspaceTerminalSession(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        unmockkStatic(::createWorkspaceTerminalSession, ::prepareWorkspaceTerminalSession, ::workspaceRootfsReady)
        Dispatchers.resetMain()
    }

    @Test
    fun `terminal stop timeout keeps live tab reachable for a retry`() = runTest {
        val fixture = fixture()
        var failure: Throwable? = null
        val closing = backgroundScope.launch {
            try {
                fixture.terminals.closeWorkspace(ROOT)
            } catch (error: Exception) {
                failure = error
            }
        }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()

        assertTrue(closing.isCompleted)
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(fixture.tabId), fixture.terminals.observeWorkspace(ROOT).first().tabs.map { it.id })
        assertReplacementBlocked(fixture.workspaces)

        fixture.client.onSessionFinished(fixture.session)
        fixture.terminals.closeWorkspace(ROOT)
        assertTrue(fixture.terminals.observeWorkspace(ROOT).first().tabs.isEmpty())
    }

    @Test
    fun `closing a hidden tab still waits for its process before replacement`() = runTest {
        val fixture = fixture()
        fixture.terminals.closeTab(ROOT, fixture.tabId)
        assertTrue(fixture.terminals.observeWorkspace(ROOT).first().tabs.isEmpty())

        val closing = backgroundScope.launch { fixture.terminals.closeWorkspace(ROOT) }
        runCurrent()

        assertFalse("A removed tab still owns its running process", closing.isCompleted)
        assertReplacementBlocked(fixture.workspaces)
        fixture.client.onSessionFinished(fixture.session)
        runCurrent()
        assertTrue(closing.isCompleted)
    }

    @Test
    fun `closing an uninitialized session never signals pid zero or waits for a nonexistent process`() = runTest {
        val fixture = fixture(pid = 0)
        val closing = backgroundScope.launch { fixture.terminals.closeWorkspace(ROOT) }
        runCurrent()

        assertEquals("Termux finishIfRunning treats PID 0 as running", 0, fixture.finishRequests.get())
        assertTrue("There is no process whose completion needs an acknowledgement", closing.isCompleted)
        assertTrue(fixture.terminals.observeWorkspace(ROOT).first().tabs.isEmpty())
        val failure = runCatching {
            RootfsInstaller(fixture.workspaces).install(ROOT, "http://127.0.0.1:1/unused.tar.gz")
        }.exceptionOrNull()
        assertTrue(failure is RootfsInstallException)
        assertFalse("The unused terminal lease must be released", failure?.cause is RootfsBusyException)
    }

    @Test
    fun `cancelling terminal close retains handles and live leases`() = runTest {
        val fixture = fixture()
        val closing = backgroundScope.launch { fixture.terminals.closeWorkspace(ROOT) }
        runCurrent()

        closing.cancelAndJoin()

        assertEquals(listOf(fixture.tabId), fixture.terminals.observeWorkspace(ROOT).first().tabs.map { it.id })
        assertReplacementBlocked(fixture.workspaces)
        fixture.client.onSessionFinished(fixture.session)
        fixture.terminals.closeWorkspace(ROOT)
    }

    private suspend fun TestScope.fixture(pid: Int = 1234): Fixture {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val workspaces = WorkspaceManager(temp.newFolder())
        workspaces.ensureWorkspace(ROOT)
        val appScope = AppScope().also(scopes::add)
        val terminals = WorkspaceTerminalSessionManager(context, appScope, workspaces)
        val session = mockk<TerminalSession>(relaxed = true)
        every { session.pid } returns pid
        val finishRequests = AtomicInteger()
        every { session.finishIfRunning() } answers { finishRequests.incrementAndGet(); Unit }
        lateinit var client: WorkspaceTerminalSessionClient
        every { createWorkspaceTerminalSession(any(), ROOT, any(), any()) } answers {
            client = thirdArg()
            session
        }

        terminals.ensureSession(ROOT, shellCompatibilityMode = false)
        val tab = terminals.observeWorkspace(ROOT).first { it.tabs.isNotEmpty() }.tabs.single()

        return Fixture(workspaces, terminals, session, client, tab.id, finishRequests)
    }

    private fun assertReplacementBlocked(manager: WorkspaceManager) {
        val failure = runCatching {
            RootfsInstaller(manager).install(ROOT, "http://127.0.0.1:1/unused.tar.gz")
        }.exceptionOrNull()
        assertTrue("Live terminal must retain its Rootfs lease: $failure", failure is RootfsInstallException)
        assertTrue(failure?.cause is RootfsBusyException)
    }

    private data class Fixture(
        val workspaces: WorkspaceManager,
        val terminals: WorkspaceTerminalSessionManager,
        val session: TerminalSession,
        val client: WorkspaceTerminalSessionClient,
        val tabId: Long,
        val finishRequests: AtomicInteger,
    )

    companion object {
        private const val ROOT = "terminal-workspace"
    }
}
