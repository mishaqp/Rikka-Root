package me.rerere.rikkahub.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration

/** Own the real DataStore and every runtime collector; registration must not outlive its test. */
class TermuxPreferencesTestStore(directory: File) : Closeable {
    private val steps = ToolRuntimeLimits.maxToolSteps
    private val budget = ToolRuntimeLimits.turnBudgetMs
    private val commandTimeout = TermuxRuntime.commandTimeoutMs
    private val verifyTimeout = TermuxRuntime.verifyTimeoutMs
    private val workingDir = TermuxRuntime.defaultWorkingDir
    private val maxStdout = TermuxRuntime.maxStdoutBytes
    private val maxStderr = TermuxRuntime.maxStderrBytes
    private val aptWrap = TermuxRuntime.aptWrapEnabled
    private val persister = TermuxIntegration.persister
    private val verified = TermuxIntegration.lastVerifiedOkAtMs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(
        scope = scope, produceFile = { File(directory, "termux-test.preferences_pb") },
    )
    val preferences = TermuxPreferences(store, scope)
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
        // Restore only after all asynchronous publishers have stopped.
        ToolRuntimeLimits.maxToolSteps = steps
        ToolRuntimeLimits.turnBudgetMs = budget
        TermuxRuntime.commandTimeoutMs = commandTimeout
        TermuxRuntime.verifyTimeoutMs = verifyTimeout
        TermuxRuntime.defaultWorkingDir = workingDir
        TermuxRuntime.maxStdoutBytes = maxStdout
        TermuxRuntime.maxStderrBytes = maxStderr
        TermuxRuntime.aptWrapEnabled = aptWrap
        TermuxIntegration.persister = persister
        TermuxIntegration.restoreVerifiedAt(verified)
    }
}
