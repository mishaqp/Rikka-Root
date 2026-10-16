package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpSharedLegacyReferenceAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `refs only legacy config with blank URL remains editable and does not block DataStore`() = runBlocking {
        val vault = McpControlSecretStore(temporary.newFolder()) { error("Must not access Keystore") }
        val config = McpServerConfig.StreamableHTTPServer(url = "", commonOptions = McpCommonOptions(name = "Incomplete",
            headers = listOf("Authorization" to "rikka-keystore:mcp:11111111-2222-3333-4444-555555555555")))
        val original = preferencesOf(SettingsStore.MCP_SERVERS to JsonInstant.encodeToString<List<McpServerConfig>>(listOf(config)))
        val migration = PreferenceStoreMcpSecretsMigration(vault)
        assertFalse(migration.shouldMigrate(original))
        assertEquals(original, migration.migrate(original))
    }

    @Test fun `blank and malformed legacy URLs retain all credentials encrypted without blocking preferences`() = runBlocking {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val configs = listOf("", "not a URL").map { url ->
            McpServerConfig.StreamableHTTPServer(url = url, commonOptions = McpCommonOptions(name = "Incomplete",
                headers = listOf("Authorization" to "header-incomplete-canary"),
                oauth = McpOAuthState(enabled = true, clientSecret = "client-incomplete-canary",
                    accessToken = "access-incomplete-canary", refreshToken = "refresh-incomplete-canary")))
        }
        val original = preferencesOf(SettingsStore.MCP_SERVERS to JsonInstant.encodeToString<List<McpServerConfig>>(configs))
        val migration = PreferenceStoreMcpSecretsMigration(vault)
        assertTrue(migration.shouldMigrate(original))
        val result = migration.migrate(original)
        val json = result[SettingsStore.MCP_SERVERS]!!
        assertFalse(json, json.contains("incomplete-canary"))
        val saved = JsonInstant.decodeFromString<List<McpServerConfig>>(json)
        saved.forEachIndexed { index, config ->
            assertEquals(configs[index].url, (config as McpServerConfig.StreamableHTTPServer).url)
            assertEquals("header-incomplete-canary", vault.resolve(config.commonOptions.headers.single().second))
            val oauth = config.commonOptions.oauth!!
            assertEquals("client-incomplete-canary", vault.resolve(oauth.clientSecret!!))
            assertEquals("access-incomplete-canary", vault.resolve(oauth.accessToken!!))
            assertEquals("refresh-incomplete-canary", vault.resolve(oauth.refreshToken!!))
        }
        assertFalse(migration.shouldMigrate(result))
        assertEquals(result, migration.migrate(result))
    }

    @Test fun `invalid third owner preserves shared alias while valid owners receive separate references`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val shared = vault.put("shared-three-owner-canary")
        val configs = listOf("https://first.example.org/mcp", "https://second.example.org/mcp", "").map { url ->
            McpServerConfig.StreamableHTTPServer(url = url, commonOptions = McpCommonOptions(
                headers = listOf("Authorization" to shared, "X-New" to "third-owner-header-canary"),
                oauth = McpOAuthState(accessToken = "third-owner-oauth-canary")))
        }
        val saved = protectLegacyMcpConfigs(configs, vault)
        val refs = saved.map { it.commonOptions.headers[0].second }
        assertNotEquals(refs[0], refs[1])
        assertNotEquals(shared, refs[0])
        assertNotEquals(shared, refs[1])
        assertEquals(shared, refs[2])
        assertFalse(JsonInstant.encodeToString(saved).contains("canary"))
        saved.forEach { config ->
            assertEquals("shared-three-owner-canary", vault.resolve(config.commonOptions.headers[0].second))
            assertEquals("third-owner-header-canary", vault.resolve(config.commonOptions.headers[1].second))
            assertEquals("third-owner-oauth-canary", vault.resolve(config.commonOptions.oauth!!.accessToken!!))
        }
    }

    @Test fun `shared ownerless references trigger migration even without any plaintext fields`() = runBlocking {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val shared = vault.put("shared-legacy-canary")
        val configs = listOf("https://first.example.org/mcp", "https://second.example.org/mcp").map { url ->
            McpServerConfig.StreamableHTTPServer(url = url, commonOptions = McpCommonOptions(
                headers = listOf("Authorization" to shared)))
        }
        val original = preferencesOf(SettingsStore.MCP_SERVERS to JsonInstant.encodeToString<List<McpServerConfig>>(configs))
        val migration = PreferenceStoreMcpSecretsMigration(vault)
        assertTrue(migration.shouldMigrate(original))
        val result = migration.migrate(original)
        assertFalse(migration.shouldMigrate(result))
        val saved = JsonInstant.decodeFromString<List<McpServerConfig>>(result[SettingsStore.MCP_SERVERS]!!)
        assertNotEquals(saved[0].commonOptions.headers.single().second, saved[1].commonOptions.headers.single().second)
    }

    @Test fun `restored missing references do not block encryption of remaining plaintext settings`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val missing = "rikka-keystore:mcp:11111111-2222-3333-4444-555555555555"
        val config = McpServerConfig.StreamableHTTPServer(url = "https://example.org/mcp", commonOptions = McpCommonOptions(
            headers = listOf("Authorization" to missing, "X-New" to "new-secret-canary")))
        val protected = protectLegacyMcpConfigs(listOf(config), vault).single()
        assertEquals(missing, protected.commonOptions.headers[0].second)
        assertEquals("new-secret-canary", vault.resolve(protected.commonOptions.headers[1].second))
    }

    @Test fun `trusted migration forks shared ownerless references for each owner without blocking preferences`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val shared = vault.put("shared-legacy-canary")
        val configs = listOf("https://first.example.org/mcp", "https://second.example.org/mcp").mapIndexed { index, url ->
            McpServerConfig.StreamableHTTPServer(url = url, commonOptions = McpCommonOptions(name = "Legacy$index",
                headers = listOf("Authorization" to shared, "Accept" to "application/json")))
        }
        val migrated = protectLegacyMcpConfigs(configs, vault)
        val references = migrated.map { it.commonOptions.headers.first { header -> header.first == "Authorization" }.second }
        assertNotEquals(references[0], references[1])
        references.forEach { assertEquals("shared-legacy-canary", vault.resolve(it)) }
        assertEquals(migrated, protectLegacyMcpConfigs(migrated, vault))
    }
}
