package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class PreferenceStoreMcpSecretsMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = McpControlSecretStore(temporary.newFolder(), { key })
    private fun config() = McpServerConfig.StreamableHTTPServer(
        url = "https://example.org/mcp",
        commonOptions = McpCommonOptions(name = "legacy", headers = listOf("Authorization" to "Bearer migration-canary"),
            oauth = McpOAuthState(enabled = true, clientId = "public-id", clientSecret = "client-secret-canary",
                accessToken = "access-token-canary", refreshToken = "refresh-token-canary", expiresAt = 123456)),
    )
    private fun json(config: McpServerConfig) = JsonInstant.encodeToString<List<McpServerConfig>>(listOf(config))

    @Test fun migrationReplacesHeadersAndAllOAuthSecretsBeforePreferencesAreReturned() = runBlocking {
        val vault = store()
        val migration = PreferenceStoreMcpSecretsMigration(vault)
        val original = config()
        val preferences = preferencesOf(SettingsStore.MCP_SERVERS to json(original))
        assertTrue(migration.shouldMigrate(preferences))
        val result = migration.migrate(preferences)
        val persisted = result[SettingsStore.MCP_SERVERS]!!
        listOf("migration-canary", "client-secret-canary", "access-token-canary", "refresh-token-canary").forEach {
            assertFalse("Plaintext remains in stored MCP JSON", persisted.contains(it))
        }
        val restored = JsonInstant.decodeFromString<List<McpServerConfig>>(persisted).single()
        assertEquals(original.id, restored.id)
        assertEquals(original.url, (restored as McpServerConfig.StreamableHTTPServer).url)
        assertEquals(original.commonOptions.tools, restored.commonOptions.tools)
        assertEquals("Bearer migration-canary", vault.resolve(restored.commonOptions.headers.single().second))
        val oauth = restored.commonOptions.oauth!!
        assertEquals("public-id", oauth.clientId)
        assertEquals(123456L, oauth.expiresAt)
        assertEquals("client-secret-canary", vault.resolve(oauth.clientSecret!!))
        assertEquals("access-token-canary", vault.resolve(oauth.accessToken!!))
        assertEquals("refresh-token-canary", vault.resolve(oauth.refreshToken!!))
        assertFalse(migration.shouldMigrate(result))
        assertEquals(result, migration.migrate(result))
    }

    @Test fun keyStoreFailureDoesNotReturnOrOverwritePlaintextPreferences() = runBlocking {
        val migration = PreferenceStoreMcpSecretsMigration(McpControlSecretStore(temporary.newFolder()) {
            error("Unavailable key")
        })
        val serialized = json(config())
        val preferences = preferencesOf(SettingsStore.MCP_SERVERS to serialized)
        val failure = runCatching { migration.migrate(preferences) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure!!.message.orEmpty().contains("canary"))
        assertEquals(serialized, preferences[SettingsStore.MCP_SERVERS])
    }

    @Test fun ordinaryAndRestoredConfigWritesUseSameIdempotentProtection() {
        val vault = store()
        val original = config()
        val safe = protectLegacyMcpConfigs(listOf(original), vault)
        assertEquals(safe, protectLegacyMcpConfigs(safe, vault))
        assertTrue(safe.single().commonOptions.headers.all { McpControlSecretStore.isReference(it.second) })
        assertEquals("Bearer migration-canary", vault.resolve(safe.single().commonOptions.headers.single().second))
    }

    @Test fun noMcpConfigsDoNotCreateOrRequestEncryptionKey() = runBlocking {
        var calls = 0
        val migration = PreferenceStoreMcpSecretsMigration(McpControlSecretStore(temporary.newFolder()) {
            calls++
            error("Must not access Keystore")
        })
        val preferences = preferencesOf(SettingsStore.MCP_SERVERS to "[]")
        assertFalse(migration.shouldMigrate(preferences))
        assertEquals(preferences, migration.migrate(preferences))
        assertEquals(0, calls)
    }

    @Test fun malformedLegacySecretsFailWithoutLeakingJsonInMessageCauseOrStack() = runBlocking {
        val canary = "sk-malformed-mcp-private-canary-123456789"
        val malformed = """[{"commonOptions":{"headers":[["Authorization","Bearer $canary"]]},"broken":}]"""
        val preferences = preferencesOf(SettingsStore.MCP_SERVERS to malformed)
        val migration = PreferenceStoreMcpSecretsMigration(store())
        val failures = listOf(
            runCatching { migration.shouldMigrate(preferences) }.exceptionOrNull(),
            runCatching { migration.migrate(preferences) }.exceptionOrNull(),
        )
        failures.forEach { failure ->
            assertNotNull("Malformed JSON must fail closed", failure)
            assertNull("Original decoder cause may contain private JSON", failure!!.cause)
            assertFalse(failure.message.orEmpty().contains(canary))
            assertFalse(failure.stackTraceToString().contains(canary))
            assertFalse(failure.stackTraceToString().contains("Authorization"))
            assertTrue(failure.message.orEmpty().contains("конфигурацию MCP"))
        }
        assertEquals("Failed migration must retain the original transaction", malformed, preferences[SettingsStore.MCP_SERVERS])
    }
}
