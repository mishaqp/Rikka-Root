package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.secureMcpConfigForPersistence
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/** Runs before DataStore emits any settings, including after restoring an older backup. */
class PreferenceStoreMcpSecretsMigration(
    private val secretStore: McpControlSecretStore,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = configs(currentData).any(::hasLegacySecrets)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val original = configs(currentData)
        if (original.none(::hasLegacySecrets)) return currentData
        // Build the complete protected value first. If Keystore fails, DataStore keeps its
        // original transaction and emits no plaintext settings to UI, MCP, or backup code.
        val protected = protectLegacyMcpConfigs(original, secretStore)
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.MCP_SERVERS] = JsonInstant.encodeToString(protected)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    private fun configs(preferences: Preferences): List<McpServerConfig> =
        preferences[SettingsStore.MCP_SERVERS]?.let {
            try { JsonInstant.decodeFromString(it) }
            catch (_: Exception) { throw IllegalStateException("Не удалось прочитать старую конфигурацию MCP. Настройки не изменены; защищённая миграция требует корректного JSON.") }
        } ?: emptyList()
}

internal fun protectLegacyMcpConfigs(
    configs: List<McpServerConfig>,
    secretStore: McpControlSecretStore,
): List<McpServerConfig> = configs.map { config ->
    if (hasLegacySecrets(config)) secureMcpConfigForPersistence(config, secretStore) else config
}

private fun hasLegacySecrets(config: McpServerConfig): Boolean {
    fun plain(value: String?): Boolean = !value.isNullOrBlank() && !McpControlSecretStore.isReference(value)
    val common = config.commonOptions
    return common.headers.any { plain(it.second) } || common.oauth?.let {
        plain(it.clientSecret) || plain(it.accessToken) || plain(it.refreshToken)
    } == true
}
