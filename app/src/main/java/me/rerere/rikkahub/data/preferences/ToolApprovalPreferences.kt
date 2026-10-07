package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.root.RootAccessStore

internal fun isWorkspaceToolName(name: String): Boolean = name.startsWith("workspace_")

internal fun migrateWorkspaceToolsFrom(stored: Set<String>): Pair<Set<String>, Set<String>> {
    val removed = stored.filterTo(mutableSetOf(), ::isWorkspaceToolName)
    return (stored - removed) to removed
}

/** Agent's device-wide grants. Kept outside backups, like the fork's previous permissions. */
class ToolApprovalPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context, legacy: RootAccessStore) : this(
        PreferenceDataStoreFactory.create(
            migrations = listOf(legacyToolApprovalMigration(legacy)),
            produceFile = { File(context.noBackupFilesDir, "tool_approval.preferences_pb") },
        )
    )

    val alwaysAllowFlow = store.data.map { migrateWorkspaceToolsFrom(it[ALWAYS_ALLOW].orEmpty()).first }
    val globalYoloFlow = store.data.map { it[GLOBAL_YOLO] ?: false }
    val askAfterWebContentFlow = store.data.map { it[ASK_AFTER_WEB_CONTENT] ?: false }

    suspend fun current(): Set<String> {
        val stored = store.data.first()[ALWAYS_ALLOW].orEmpty()
        if (migrateWorkspaceToolsFrom(stored).second.isEmpty()) return stored
        // Return the transaction's snapshot so a concurrent grant is not lost.
        val updated = store.edit { it[ALWAYS_ALLOW] = migrateWorkspaceToolsFrom(it[ALWAYS_ALLOW].orEmpty()).first }
        return migrateWorkspaceToolsFrom(updated[ALWAYS_ALLOW].orEmpty()).first
    }

    suspend fun currentYolo(): Boolean = globalYoloFlow.first()
    suspend fun currentAskAfterWebContent(): Boolean = askAfterWebContentFlow.first()
    suspend fun setYolo(enabled: Boolean) { store.edit { it[GLOBAL_YOLO] = enabled } }
    suspend fun setAskAfterWebContent(enabled: Boolean) { store.edit { it[ASK_AFTER_WEB_CONTENT] = enabled } }

    suspend fun grantAlways(toolName: String) {
        if (isWorkspaceToolName(toolName) || toolName == "ask_user") return
        require(toolName.isNotBlank() && toolName.length <= 256 && toolName.none { it.isISOControl() })
        store.edit { it[ALWAYS_ALLOW] = it[ALWAYS_ALLOW].orEmpty() + toolName }
    }

    suspend fun revoke(toolName: String) { store.edit { it[ALWAYS_ALLOW] = it[ALWAYS_ALLOW].orEmpty() - toolName } }
    suspend fun revokeAll() { store.edit { it.remove(ALWAYS_ALLOW) } }

    internal companion object {
        val ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tool_names")
        val GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")
        val ASK_AFTER_WEB_CONTENT = booleanPreferencesKey("ask_after_web_content")
        val LEGACY_MIGRATED = booleanPreferencesKey("root_permissions_migrated")
    }
}

internal fun legacyToolApprovalMigration(legacy: RootAccessStore) = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[ToolApprovalPreferences.LEGACY_MIGRATED] != true

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        val permissions = legacy.permissions.value
        if (this[ToolApprovalPreferences.GLOBAL_YOLO] == null) this[ToolApprovalPreferences.GLOBAL_YOLO] = permissions.autoApproveAll
        this[ToolApprovalPreferences.ALWAYS_ALLOW] = migrateWorkspaceToolsFrom(
            this[ToolApprovalPreferences.ALWAYS_ALLOW].orEmpty() + permissions.alwaysAllow
        ).first - "ask_user"
        this[ToolApprovalPreferences.LEGACY_MIGRATED] = true
    }

    override suspend fun cleanUp() = Unit
}
