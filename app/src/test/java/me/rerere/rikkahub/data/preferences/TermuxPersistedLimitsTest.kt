package me.rerere.rikkahub.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.mockk.coEvery
import io.mockk.mockk
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxPersistedLimitsTest {
    @get:Rule val folder = TemporaryFolder()
    private val savedSteps = ToolRuntimeLimits.maxToolSteps
    private val savedBudget = ToolRuntimeLimits.turnBudgetMs
    private val savedCommandTimeout = TermuxRuntime.commandTimeoutMs
    private val savedVerifyTimeout = TermuxRuntime.verifyTimeoutMs
    private val savedWorkingDir = TermuxRuntime.defaultWorkingDir
    private val savedMaxStdout = TermuxRuntime.maxStdoutBytes
    private val savedMaxStderr = TermuxRuntime.maxStderrBytes
    private val savedAptWrap = TermuxRuntime.aptWrapEnabled
    private val savedPersister = TermuxIntegration.persister
    private val savedVerified = TermuxIntegration.lastVerifiedOkAtMs

    @After fun restoreRuntime() {
        ToolRuntimeLimits.maxToolSteps = savedSteps
        ToolRuntimeLimits.turnBudgetMs = savedBudget
        TermuxRuntime.commandTimeoutMs = savedCommandTimeout
        TermuxRuntime.verifyTimeoutMs = savedVerifyTimeout
        TermuxRuntime.defaultWorkingDir = savedWorkingDir
        TermuxRuntime.maxStdoutBytes = savedMaxStdout
        TermuxRuntime.maxStderrBytes = savedMaxStderr
        TermuxRuntime.aptWrapEnabled = savedAptWrap
        TermuxIntegration.persister = savedPersister
        TermuxIntegration.restoreVerifiedAt(savedVerified)
    }

    @Test fun `cold start restores unlimited steps and user timeout before first Root invocation`() = runBlocking {
        val file = File(folder.root, "termux.preferences_pb")
        Fixture(file).use { f -> f.store.edit {
            it[intPreferencesKey("max_tool_steps")] = 147
            it[booleanPreferencesKey("unlimited_tool_steps")] = true
            it[longPreferencesKey("command_timeout_ms")] = 120_000
            it[longPreferencesKey("turn_budget_ms")] = 300_000
        } }
        // Emulate process holders before any settings page or tool has been opened.
        TermuxRuntime.commandTimeoutMs = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS
        ToolRuntimeLimits.maxToolSteps = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
        Fixture(file).use { f ->
            val preferences = TermuxPreferences(f.store, f.scope)
            assertEquals(0, ToolRuntimeLimits.maxToolSteps)
            assertEquals(147, preferences.snapshot().maxToolSteps)
            assertEquals(300_000L, ToolRuntimeLimits.turnBudgetMs)
            val observed = mutableListOf<Int>()
            val manager = mockk<RootShellManager>()
            coEvery { manager.exec(any(), capture(observed), any(), any()) } returns RootProcessResult(exitCode = 0)
            buildRootTool(manager).execute(buildJsonObject { put("command", "printf first-root") })
            assertEquals(listOf(120_000), observed)
        }
    }

    @Test fun `legacy numeric setting survives restart without opting into unlimited mode`() = runBlocking {
        val file = File(folder.root, "legacy.preferences_pb")
        Fixture(file).use { f -> f.store.edit { it[intPreferencesKey("max_tool_steps")] = 219 } }
        Fixture(file).use { f ->
            val preferences = TermuxPreferences(f.store, f.scope)
            assertEquals(219, preferences.maxToolStepsFlow().first())
            assertEquals(219, ToolRuntimeLimits.maxToolSteps)
        }
    }

    @Test fun `switch persists across restarts and restores the previous numeric cap`() = runBlocking {
        val file = File(folder.root, "switch.preferences_pb")
        Fixture(file).use { f ->
            val preferences = TermuxPreferences(f.store, f.scope)
            preferences.setMaxToolSteps(147)
            preferences.setUnlimitedToolSteps(true)
            val config = preferences.snapshot()
            assertTrue(config.unlimitedToolSteps)
            assertEquals(147, config.maxToolSteps)
            withTimeout(5_000) { while (ToolRuntimeLimits.maxToolSteps != 0) delay(1) }
        }
        ToolRuntimeLimits.maxToolSteps = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
        Fixture(file).use { f ->
            val preferences = TermuxPreferences(f.store, f.scope)
            assertTrue(preferences.snapshot().unlimitedToolSteps)
            assertEquals(147, preferences.snapshot().maxToolSteps)
            assertEquals(0, ToolRuntimeLimits.maxToolSteps)
            preferences.setUnlimitedToolSteps(false)
            assertFalse(preferences.snapshot().unlimitedToolSteps)
            assertEquals(147, preferences.snapshot().maxToolSteps)
            withTimeout(5_000) { while (ToolRuntimeLimits.maxToolSteps != 147) delay(1) }
        }
        ToolRuntimeLimits.maxToolSteps = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
        Fixture(file).use { f ->
            val preferences = TermuxPreferences(f.store, f.scope)
            assertFalse(preferences.snapshot().unlimitedToolSteps)
            assertEquals(147, preferences.snapshot().maxToolSteps)
            assertEquals(147, ToolRuntimeLimits.maxToolSteps)
        }
    }

    private class Fixture(file: File) : Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        override fun close() { scope.cancel(); runBlocking { scope.coroutineContext[Job]?.join() } }
    }
}
