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
import me.rerere.rikkahub.root.RootAccessStore

/** Real file-backed DataStore, with a lifetime bounded by each test. */
class ToolApprovalTestStore(directory: File, legacy: RootAccessStore? = null) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        migrations = legacy?.let { listOf(legacyToolApprovalMigration(it)) }.orEmpty(),
        produceFile = { File(directory, "tool_approval.preferences_pb") },
    )
    val preferences = ToolApprovalPreferences(dataStore)
    override fun close() {
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
    }
}
