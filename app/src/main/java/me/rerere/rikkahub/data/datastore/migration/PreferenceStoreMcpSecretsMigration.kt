package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.secureMcpConfigForPersistence
import me.rerere.rikkahub.data.ai.mcp.protectedSecrets
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.ai.mcp.control.mcpCredentialOrigin
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/** Runs before DataStore emits any settings, including after restoring an older backup. */
class PreferenceStoreMcpSecretsMigration(
    private val secretStore: McpControlSecretStore,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = configs(currentData).let {
        it.any(::hasLegacySecrets) || sharedLegacyValues(it, secretStore).isNotEmpty()
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val original = configs(currentData)
        if (original.none(::hasLegacySecrets) && sharedLegacyValues(original, secretStore).isEmpty()) return currentData
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
): List<McpServerConfig> {
    // Snapshot all shared ownerless values before binding anything. Each saved owner gets
    // its own reference; one server cannot claim a legacy alias needed by another server.
    val legacy = sharedLegacyValues(configs, secretStore)
    val forks = mutableMapOf<Pair<String, String>, String>()
    return configs.map { config ->
        val origin = runCatching { mcpCredentialOrigin(config.serverUrl) }.getOrNull()
        if (origin == null) {
            // Old settings allowed incomplete addresses. Encrypt raw values without
            // assigning any network owner; do not fork or bind pre-existing aliases.
            // Runtime refuses credentials for an invalid origin until the user edits it.
            return@map if (!hasLegacySecrets(config)) config else config.clone(commonOptions = config.commonOptions.copy(
                headers = config.commonOptions.headers.map { (name, value) ->
                    name to if (value.isBlank() || McpControlSecretStore.isReference(value)) value else secretStore.put(value)
                },
                oauth = config.commonOptions.oauth?.protectedSecrets(secretStore),
            ))
        }
        fun fork(value: String?): String? = value?.let {
            val plaintext = legacy[it] ?: return@let it
            val owner = "${config.id}\n$origin"
            forks.getOrPut(it to owner) { secretStore.put(plaintext, config.id, config.serverUrl) }
        }
        val prepared = if (legacy.isEmpty()) config else config.clone(commonOptions = config.commonOptions.copy(
            headers = config.commonOptions.headers.map { (name, value) -> name to fork(value)!! },
            oauth = config.commonOptions.oauth?.let { oauth -> oauth.copy(
                clientSecret = fork(oauth.clientSecret), accessToken = fork(oauth.accessToken), refreshToken = fork(oauth.refreshToken),
            ) },
        ))
        if (hasLegacySecrets(prepared)) secureMcpConfigForPersistence(prepared, secretStore) else prepared
    }
}

private fun sharedLegacyValues(configs: List<McpServerConfig>, store: McpControlSecretStore): Map<String, String> {
    val owners = mutableMapOf<String, MutableSet<String>>()
    configs.forEach { config ->
        // Older settings allowed incomplete URLs. Discovery must not block DataStore;
        // these configs stay editable, and runtime credential resolution remains strict.
        val origin = runCatching { mcpCredentialOrigin(config.serverUrl) }.getOrNull() ?: return@forEach
        val values = config.commonOptions.headers.map { it.second } + config.commonOptions.oauth?.let {
            listOfNotNull(it.clientSecret, it.accessToken, it.refreshToken)
        }.orEmpty()
        values.filter(McpControlSecretStore::isReference).forEach { reference ->
            owners.getOrPut(reference) { mutableSetOf() }.add("${config.id}\n$origin")
        }
    }
    return owners.filterValues { it.size > 1 }.keys.mapNotNull { reference ->
        store.legacyValue(reference)?.let { reference to it }
    }.toMap()
}

private fun hasLegacySecrets(config: McpServerConfig): Boolean {
    fun plain(value: String?): Boolean = !value.isNullOrBlank() && !McpControlSecretStore.isReference(value)
    val common = config.commonOptions
    return common.headers.any { plain(it.second) } || common.oauth?.let {
        plain(it.clientSecret) || plain(it.accessToken) || plain(it.refreshToken)
    } == true
}
