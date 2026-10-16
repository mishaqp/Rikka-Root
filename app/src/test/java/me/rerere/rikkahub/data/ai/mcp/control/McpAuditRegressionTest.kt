package me.rerere.rikkahub.data.ai.mcp.control

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.oauth.OAuthHttpClient
import me.rerere.rikkahub.data.ai.mcp.McpOAuthCoordinator
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.secureMcpConfigForPersistence
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/** Regression cases for audit 9b74e541, findings 3 and 9. */
class McpAuditRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun vault() = McpControlSecretStore(temporary.newFolder()) { key }
    private fun server() = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "Remote", enable = false),
        url = "https://8.8.8.8/mcp",
    )

    private class Fixture(config: McpServerConfig) {
        val state = MutableStateFlow(Settings(mcpServers = listOf(config)))
        val store = mockk<SettingsStore>()
        val manager = mockk<McpManager>()
        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any()) } coAnswers {
                firstArg<(Settings) -> Settings>()(state.value).also { state.value = it }
            }
            every { manager.syncingStatus } returns MutableStateFlow(mapOf(config.id to McpStatus.Connected))
            coEvery { manager.removeClient(any()) } returns Unit
            coEvery { manager.addClient(any()) } returns Unit
        }
    }

    private fun updateArgs(config: McpServerConfig.StreamableHTTPServer, url: String = config.url) = buildJsonObject {
        put("id", config.id.toString())
        put("name", config.commonOptions.name)
        put("transport", "streamable_http")
        put("url", url)
    }

    @Test fun `audit 3 omitted enabled and headers retain previous values on same host`() = runBlocking {
        val vault = vault()
        val old = secureMcpConfigForPersistence(server().copy(commonOptions = McpCommonOptions(
            name = "Remote", enable = false,
            headers = listOf("X-Auth" to "private-canary", "Accept" to "application/json"),
        )), vault) as McpServerConfig.StreamableHTTPServer
        val fixture = Fixture(old)
        mcpUpdateTool(fixture.store, fixture.manager, vault).execute(updateArgs(old))
        val saved = fixture.state.value.mcpServers.single()
        assertFalse("An omitted enabled must not enable a disabled server", saved.commonOptions.enable)
        assertEquals(old.commonOptions.headers, saved.commonOptions.headers)
    }

    @Test fun `audit 3 changing host clears OAuth before connecting new endpoint`() = runBlocking {
        val old = server().copy(commonOptions = McpCommonOptions(name = "Remote", enable = false,
            oauth = McpOAuthState(enabled = true, accessToken = "old-host-access", refreshToken = "old-host-refresh")))
        val fixture = Fixture(old)
        mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(updateArgs(old, "https://1.1.1.1/mcp"))
        assertNull("OAuth from the old host must not be forwarded", fixture.state.value.mcpServers.single().commonOptions.oauth)
    }

    @Test fun `audit 3 changing host drops old secret references even when repeated in headers`() = runBlocking {
        val vault = vault()
        val old = secureMcpConfigForPersistence(server().copy(commonOptions = McpCommonOptions(
            name = "Remote", enable = false,
            headers = listOf("X-Auth" to "old-host-secret"),
        )), vault).let { protected -> protected.clone(commonOptions = protected.commonOptions.copy(
            headers = protected.commonOptions.headers + ("Accept" to "application/json"),
        )) } as McpServerConfig.StreamableHTTPServer
        val fixture = Fixture(old)
        val args = JsonObject(updateArgs(old, "https://1.1.1.1/mcp").toMutableMap().apply {
            put("enabled", kotlinx.serialization.json.JsonPrimitive(false))
            put("headers", kotlinx.serialization.json.buildJsonArray {
                old.commonOptions.headers.forEach { (name, value) -> addJsonObject {
                    put("name", name); put("value", value)
                } }
            })
        })
        mcpUpdateTool(fixture.store, fixture.manager, vault).execute(args)
        val saved = fixture.state.value.mcpServers.single() as McpServerConfig.StreamableHTTPServer
        assertEquals("https://1.1.1.1/mcp", saved.url)
        assertEquals(listOf("Accept" to "application/json"), saved.commonOptions.headers)
    }

    @Test fun `audit 3 OAuth refresh completing after host change cannot restore old host tokens`() = runBlocking {
        val old = server().copy(commonOptions = McpCommonOptions(name = "Remote", oauth = McpOAuthState(
            enabled = true, clientId = "client", accessToken = "expired-old-host", refreshToken = "refresh-old-host",
            tokenEndpoint = "https://8.8.8.8/token", expiresAt = 1,
        )))
        val fixture = Fixture(old)
        val oauth = mockk<OAuthHttpClient>()
        coEvery { oauth.refreshToken(any()) } coAnswers {
            fixture.state.value = fixture.state.value.copy(mcpServers = listOf(old.copy(
                url = "https://1.1.1.1/mcp", commonOptions = old.commonOptions.copy(oauth = null),
            )))
            OAuthHttpClient.TokenResponse(accessToken = "refreshed-old-host")
        }
        val coordinator = McpOAuthCoordinator(
            settingsStore = fixture.store, appScope = mockk(), oauthClient = oauth,
            discoveryClient = mockk(), callbackServer = mockk(), authorizationLauncher = mockk(),
            updateStatus = { _, _ -> }, secretStore = vault(), publicOAuthClient = oauth,
            publicDiscoveryClient = mockk(),
        )
        coordinator.ensureFreshToken(old)
        assertNull("An old in-flight OAuth operation must not reattach credentials after host change",
            fixture.state.value.mcpServers.single().commonOptions.oauth)
    }

    @Test fun `audit 9 server views redact legacy URL userinfo and query secrets`() = runBlocking {
        val old = server().copy(url = "https://login:private-password@8.8.8.8/mcp?api_key=private-query-secret")
        val fixture = Fixture(old)
        val caller = McpControlCaller({ setOf(old.id) }, {}, {})
        for (parts in listOf(
            mcpGetTool(fixture.store, fixture.manager, caller).execute(buildJsonObject { put("id", old.id.toString()) }),
            mcpListTool(fixture.store, fixture.manager, caller).execute(JsonObject(emptyMap())),
        )) {
            val result = (parts.single() as UIMessagePart.Text).text
            assertFalse(result, result.contains("private-password"))
            assertFalse(result, result.contains("private-query-secret"))
        }
    }

    @Test fun `audit 9 server views redact provider tokens embedded in URL paths`() = runBlocking {
        val old = server().copy(url = "https://8.8.8.8/mcp/sk-private-path-secret-canary")
        val fixture = Fixture(old)
        val caller = McpControlCaller({ setOf(old.id) }, {}, {})
        for (parts in listOf(
            mcpGetTool(fixture.store, fixture.manager, caller).execute(buildJsonObject { put("id", old.id.toString()) }),
            mcpListTool(fixture.store, fixture.manager, caller).execute(JsonObject(emptyMap())),
        )) {
            val result = (parts.single() as UIMessagePart.Text).text
            assertFalse(result, result.contains("sk-private-path-secret-canary"))
        }
    }

    @Test fun `audit 9 opaque MCP references are hidden for every header name`() {
        val reference = "rikka-keystore:mcp:11111111-2222-3333-4444-555555555555"
        for (name in listOf("apikey", "X-Auth", "X-Custom", "Accept")) {
            val redacted = McpHeaderRedactor.redactHeaderValue(name, reference)
            assertFalse("$name exposed its reference", redacted.contains("rikka-keystore:"))
            assertFalse("$name exposed the UUID", redacted.contains("11111111-2222"))
        }
    }

    @Test fun `audit 9 secret references cannot move to another server id`() {
        val directory = temporary.newFolder()
        val vault = McpControlSecretStore(directory) { key }
        val owner = secureMcpConfigForPersistence(server().copy(commonOptions = McpCommonOptions(
            name = "Remote", headers = listOf("X-Auth" to "owner-only-secret"))), vault)
        val copied = owner.clone(id = Uuid.random())
        assertTrue("Foreign references must be rejected before transport use",
            runCatching { secureMcpConfigForPersistence(copied, McpControlSecretStore(directory) { key }) }.isFailure)
    }

    @Test fun `audit 9 secret references cannot move to another host with same id`() {
        val vault = vault()
        val owner = secureMcpConfigForPersistence(server().copy(commonOptions = McpCommonOptions(
            name = "Remote", headers = listOf("X-Auth" to "owner-only-secret"))), vault) as McpServerConfig.StreamableHTTPServer
        assertTrue("A changed endpoint must not reuse references bound to the old host",
            runCatching { secureMcpConfigForPersistence(owner.copy(url = "https://1.1.1.1/mcp"), vault) }.isFailure)
    }

    @Test fun `audit 9 add rejects reference owned by an existing server`() = runBlocking {
        val vault = vault()
        val owner = secureMcpConfigForPersistence(server().copy(commonOptions = McpCommonOptions(
            name = "Remote", enable = false, headers = listOf("Authorization" to "Bearer owner-only-secret"))), vault)
        val fixture = Fixture(owner)
        val caller = McpControlCaller({ emptySet() }, {}, {})
        val result = mcpAddTool(fixture.store, fixture.manager, caller, vault).execute(buildJsonObject {
            put("transport", "streamable_http")
            put("name", "Other")
            put("url", "https://1.1.1.1/mcp")
            put("enabled", false)
            putJsonArray("headers") { addJsonObject {
                put("name", "Authorization")
                put("value", owner.commonOptions.headers.single().second)
            } }
        })
        val response = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertTrue("Adding a server must reject another server's secret", response.containsKey("error"))
        assertEquals(listOf(owner), fixture.state.value.mcpServers)
    }

    @Test fun `audit 3 update approval warns that changing endpoint requires fresh credentials`() {
        val rendered = McpApprovalRenderer.render("mcp_update", updateArgs(server(), "https://1.1.1.1/mcp"))!!
        assertTrue(rendered, rendered.contains("авторизац", ignoreCase = true) || rendered.contains("секрет", ignoreCase = true))
    }
}
