package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpOAuthSecretsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(): McpControlSecretStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return McpControlSecretStore(temporary.newFolder()) { key }
    }
    @Test fun `OAuth persist stores references and restores exact tokens only at request time`() {
        val vault = store()
        val state = McpOAuthState(enabled = true, clientId = "public-client-id", clientSecret = "client-secret-canary",
            accessToken = "access-token-canary", refreshToken = "refresh-token-canary", expiresAt = 1234)
        val protected = state.protectedSecrets(vault)
        for (value in listOf(protected.clientSecret, protected.accessToken, protected.refreshToken))
            assertTrue(McpControlSecretStore.isReference(value!!))
        val serialized = Json.encodeToString(protected)
        for (secret in listOf(state.clientSecret!!, state.accessToken!!, state.refreshToken!!)) assertFalse(serialized.contains(secret))
        assertEquals(state, protected.resolvedSecrets(vault))
        assertEquals(protected, protected.protectedSecrets(vault))
    }
    @Test fun `legacy raw values remain usable in memory and null optional secrets stay null`() {
        val vault = store()
        val legacy = McpOAuthState(accessToken = "legacy-token")
        assertEquals(legacy, legacy.resolvedSecrets(vault))
        val protected = legacy.protectedSecrets(vault)
        assertNull(protected.clientSecret)
        assertNull(protected.refreshToken)
        assertEquals(legacy, protected.resolvedSecrets(vault))
    }
    @Test fun `missing OAuth reference fails without returning or exposing a token`() {
        val missing = McpOAuthState(accessToken = "rikka-keystore:mcp:00000000-0000-0000-0000-000000000000")
        val error = runCatching { missing.resolvedSecrets(store()) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("настройках MCP"))
    }
}
