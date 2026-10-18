package me.rerere.rikkahub.data.ai.mcp.control

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpMetadataCompatibilityReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun vault() = McpControlSecretStore(temporary.newFolder()) { key }

    @Test fun ordinaryNamesDescriptionsAndIdentifiersArePreservedExactly() {
        for (ordinary in ordinaryValues + listOf("Basic dXNlcjpwYXNz", "Bearer Notes", "Basic Memory")) {
            assertEquals(ordinary, maskMcpCredentialShapes(ordinary))
            assertEquals(ordinary, redactedMcpText(ordinary))
            assertFalse("Metadata is not a neutral HTTP header: $ordinary", isMcpKnownCredentialValue(ordinary))
        }
    }

    @Test fun ordinaryLongUrlPathsStayExactWithoutLosingActualCredentialProtection() {
        val path = "https://8.8.8.8/mcp/" + CAMEL + "/tp-link/manual.html"
        assertEquals(path, redactedMcpUrl(path))
        assertEquals(path, redactedMcpText(path))
        for (shortPath in listOf("tp-link/manual", "sk-private-path", "sk-private-path-secret-canary", "sk-proj-short", "gsk_demo", "xai-server")) {
            val ordinary = "https://8.8.8.8/mcp/$shortPath"
            assertEquals(ordinary, redactedMcpUrl(ordinary))
            assertEquals(ordinary, redactedMcpText(ordinary))
        }
        assertFalse(redactedMcpUrl("https://8.8.8.8/$API_KEY").contains(API_KEY))
        assertEquals("https://8.8.8.8/mcp?[скрыто]", redactedMcpUrl("$OLD_URL?token=private-query"))
    }

    @Test fun basicAndBearerDisplayNamesUseTheirStableOrdinaryAliases() {
        assertEquals(BASIC_ALIAS, buildMcpToolName(ID, "Basic Memory", "fetch"))
        assertEquals("mcp__12345678_Remote123__Basic_Memory_ab0fbf6391d97a80",
            buildMcpToolName(ID, "Remote123", "Basic Memory"))
        assertEquals("mcp__12345678_Bearer_Notes__fetch_610eff5b903eb14a",
            buildMcpToolName(ID, "Bearer Notes", "fetch"))
        assertEquals("mcp__12345678_tp-link__read", buildMcpToolName(ID, "tp-link", "read"))
        for (name in listOf("Basic Memory", "Bearer Notes", "tp-link", CAMEL)) {
            val alias = buildMcpToolName(ID, name, "fetch")
            assertTrue(alias, alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
            assertEquals(alias, buildMcpToolName(ID, redactedMcpText(name), "fetch"))
        }
    }

    @Test fun metadataRoundtripPreservesOrdinaryGrantsWithoutTransferringAnOldFalsePositiveGrant() = runBlocking {
        val directory = temporary.newFolder()
        val ordinaryAlias = "mcp__12345678_Remote123__read"
        val oldFalsePositiveAlias = "mcp__12345678_private_02b4b2542908afc7"
        ToolApprovalTestStore(directory).use {
            it.preferences.grantAlways(ordinaryAlias)
            it.preferences.grantAlways(oldFalsePositiveAlias)
        }
        ToolApprovalTestStore(directory).use {
            val before = it.preferences.current()
            assertTrue(buildMcpToolName(ID, "Remote123", "read") in before)
            val roundtrip = buildMcpToolName(ID, redactedMcpText("Basic Memory"), "fetch")
            assertEquals(BASIC_ALIAS, roundtrip)
            assertFalse("A prior false-positive private capability must not be silently reassigned", roundtrip in before)
            assertEquals(setOf(ordinaryAlias, oldFalsePositiveAlias), it.preferences.current())
        }
    }

    @Test fun listGetAndApprovalSetterKeepOrdinaryRemoteNamesExact() = runBlocking {
        for (ordinary in ordinaryValues + "Basic Memory") {
            val target = McpTool(name = ordinary, description = ordinary, needsApproval = false)
            val config = server("Basic Memory", listOf(target, McpTool(name = "other", needsApproval = true)))
            val fixture = Fixture(listOf(config))
            val before = fixture.state.value
            val got = payload(mcpGetTool(fixture.store, fixture.manager, fixture.caller).execute(
                buildJsonObject { put("id", config.id.toString()) }))
            assertEquals(config.commonOptions.name, got["name"]!!.jsonPrimitive.content)
            val gotTool = got["tools"]!!.jsonArray.first().jsonObject
            assertEquals(ordinary, gotTool["name"]!!.jsonPrimitive.content)
            assertEquals(ordinary, gotTool["description"]!!.jsonPrimitive.content)
            val listed = payload(mcpListToolsTool(fixture.store, fixture.manager, fixture.caller)
                .execute(buildJsonObject { put("id", config.id.toString()) }))["tools"]!!.jsonArray.first().jsonObject
            assertEquals(config.id.toString(), listed["server_id"]!!.jsonPrimitive.content)
            assertEquals(ordinary, listed["tool_name"]!!.jsonPrimitive.content)
            assertEquals(ordinary, listed["description"]!!.jsonPrimitive.content)
            assertEquals(before, fixture.state.value)
            assertEquals(0, fixture.writes)

            val setter = mcpSetToolApprovalTool(fixture.store)
            val args = buildJsonObject {
                put("server_id", config.id.toString()); put("tool_name", listed["tool_name"]!!.jsonPrimitive.content)
                put("needs_approval", true)
            }
            val result = payload(setter.execute(args))
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(ordinary, result["tool_name"]!!.jsonPrimitive.content)
            assertTrue(result["needs_approval"]!!.jsonPrimitive.boolean)
            assertEquals(before.copy(mcpServers = listOf(config.copy(commonOptions = config.commonOptions.copy(
                tools = listOf(target.copy(needsApproval = true), config.commonOptions.tools.last()))))), fixture.state.value)
            val enabledState = fixture.state.value
            val disabled = payload(setter.execute(JsonObject(args + ("needs_approval" to kotlinx.serialization.json.JsonPrimitive(false)))))
            assertEquals("manual_tool_approval_disable_required", disabled["error"]!!.jsonPrimitive.content)
            assertEquals(enabledState, fixture.state.value)
            assertEquals(1, fixture.writes)
        }
    }

    @Test fun ordinaryDisplayNamesCanBeAddedAndRoundtrippedWithoutChangingTheirAliases() = runBlocking {
        for (name in listOf("Basic Memory", "tp-link", "Сервер памяти", CAMEL)) {
            val fixture = Fixture(emptyList())
            val secrets = vault()
            val created = payload(mcpAddTool(fixture.store, fixture.manager, fixture.caller, secrets)
                .execute(request(null, name)))
            assertFalse(created.toString(), created.containsKey("error"))
            val saved = fixture.state.value.mcpServers.single() as McpServerConfig.StreamableHTTPServer
            assertEquals(name, saved.commonOptions.name)
            val aliasBefore = buildMcpToolName(saved.id, name, "fetch")
            val roundtrip = payload(mcpUpdateTool(fixture.store, fixture.manager, secrets)
                .execute(request(saved, created["name"]!!.jsonPrimitive.content)))
            assertFalse(roundtrip.toString(), roundtrip.containsKey("error"))
            assertEquals(saved, fixture.state.value.mcpServers.single())
            assertEquals(aliasBefore, buildMcpToolName(saved.id, fixture.state.value.mcpServers.single().commonOptions.name, "fetch"))
            coVerify(exactly = 0) { fixture.manager.addClient(any()) }
        }
    }

    @Test fun ordinaryPreviouslyOvermaskedNamesCanBeRenamedWithoutManualSecretRefusal() = runBlocking {
        for ((old, next) in listOf("tp-link" to "Basic Memory", CAMEL to "Сервер памяти")) {
            val config = server(old)
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config, next)))
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(next, fixture.state.value.mcpServers.single().commonOptions.name)
            assertFalse(fixture.state.value.mcpServers.single().commonOptions.enable)
        }
    }

    @Test fun theActualSettingsNameValidatorAcceptsTheSameDisplayNamesAndRejectsControlOrTraversal() {
        val validator = Class.forName("me.rerere.rikkahub.ui.pages.setting.SettingMcpPageKt")
            .getDeclaredMethod("isValidMcpName", String::class.java).apply { isAccessible = true }
        fun valid(name: String): Boolean = try { validator.invoke(null, name) as Boolean }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        for (name in listOf("Basic Memory", "tp-link", "Сервер памяти", CAMEL)) assertTrue(name, valid(name))
        for (name in listOf("../local", "safe/../local", "x\ny", "x\u0000y", "x".repeat(61))) assertFalse(name, valid(name))
    }

    @Test fun actualApiKeysJwtsAndLongBearerCredentialsStayMaskedInTextAndAliases() {
        for (secret in listOf(API_KEY, "ghp_" + "aB3".repeat(8), "AKIAIOSFODNN7EXAMPLE",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno", "tp-" + "aB9_".repeat(8),
            "Bearer aB3dEf9_xYz7aB3dEf9_xYz7")) {
            val payload = secret.substringAfter("Bearer ")
            val text = redactedMcpText("Описание $secret доступно")
            assertFalse(text, text.contains(payload))
            assertTrue(text.startsWith("Описание "))
            assertTrue(text.endsWith(" доступно"))
            val alias = buildMcpToolName(ID, secret, "fetch")
            assertFalse(alias, alias.contains(payload.take(12)))
            assertTrue(alias, alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        }
    }

    @Test fun bearerAndBasicAuthenticationRemainProtectedOnlyAsHeaderValues() {
        for (value in listOf("Bearer short-fixture", "Basic dXNlcjpwYXNz", "Basic Memory")) {
            assertFalse(McpHeaderRedactor.redactHeaderValue("X-Mode", value).contains(value.substringAfter(' ')))
            val validation = McpControlValidation.validateSecureHeaders(listOf("X-Mode" to value))
            assertEquals("plaintext_secret_forbidden", (validation as McpControlValidation.Result.Reject).error)
        }
        val reference = "rikka-keystore:mcp:11111111-2222-3333-4444-555555555555"
        assertFalse(McpHeaderRedactor.redactHeaderValue("X-Mode", reference).contains(reference))
    }

    @Test fun neutralAuthValuesAndScopedReferencesNeverMoveToAnotherOrigin() = runBlocking {
        val secrets = vault()
        val reference = secrets.put("header-secret", ID, OLD_URL)
        val config = server("Basic Memory").copy(commonOptions = McpCommonOptions(name = "Basic Memory", enable = false,
            headers = listOf("X-Mode" to "Basic dXNlcjpwYXNz", "X-Ref" to reference, "Accept" to "application/json"),
            oauth = McpOAuthState(enabled = true, accessToken = "old-origin-oauth")))
        val fixture = Fixture(listOf(config))
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, secrets).execute(request(config, "Basic Memory", NEW_URL)))
        assertFalse(result.toString(), result.containsKey("error"))
        val saved = fixture.state.value.mcpServers.single()
        assertEquals(listOf("Accept" to "application/json"), saved.commonOptions.headers)
        assertNull(saved.commonOptions.oauth)
        assertEquals("header-secret", secrets.resolve(reference, ID, OLD_URL))
        assertTrue(runCatching { secrets.resolve(reference, ID, NEW_URL) }.isFailure)
        assertFalse(result.toString().contains(reference))
        assertEquals("Basic Memory", result["name"]!!.jsonPrimitive.content)
    }

    @Test fun genuineCredentialNamesStillRequireManualSettingsBeforeAnyWrite() = runBlocking {
        for (name in listOf(API_KEY, "AKIAIOSFODNN7EXAMPLE", "Bearer aB3dEf9_xYz7aB3dEf9_xYz7")) {
            val fixture = Fixture(emptyList())
            val result = payload(mcpAddTool(fixture.store, fixture.manager, fixture.caller, vault()).execute(request(null, name)))
            assertEquals("manual_protected_field_update_required", result["error"]!!.jsonPrimitive.content)
            assertEquals(0, fixture.writes)
            assertTrue(fixture.state.value.mcpServers.isEmpty())
            assertFalse(result.toString().contains(name))
        }
    }

    @Test fun transportCredentialHeadersAreHiddenInActualGetListAndTestDiagnostics() = runBlocking {
        for ((header, secret) in listOf(
            "Authorization: Basic short-basic-canary" to "short-basic-canary",
            "Authorization: Bearer brief-canary" to "brief-canary",
            "X-Api-Key: tiny-key-canary" to "tiny-key-canary",
        )) assertTransportDiagnostics("Transport failed\n$header", listOf(secret))
    }

    @Test fun opaqueTransportPayloadIsHiddenWithoutBroadMaskingOfOrdinaryMetadata() = runBlocking {
        val opaque = "VHJhbnNwb3J0Q3JlZGVudGlhbENhbmFyeTEyMzQ1Njc4OTA="
        assertTransportDiagnostics("Transport rejected the response payload: $opaque", listOf(opaque))
    }

    @Test fun addRollbackHidesTransportDiagnosticsButPreservesTheReturnedOrdinaryName() = runBlocking {
        val opaque = "VHJhbnNwb3J0Q3JlZGVudGlhbENhbmFyeTEyMzQ1Njc4OTA="
        val shortSecret = "short-basic-canary"
        val raw = "Ошибка транспорта. Проверьте ответ сервера.\nAuthorization: Basic $shortSecret\nPayload: $opaque"
        val status = McpStatus.Error.from(java.io.IOException(raw))
        assertTrue(status.message.contains(shortSecret))
        assertTrue(status.detail!!.contains(opaque))
        val fixture = Fixture(emptyList())
        val statuses = MutableStateFlow(emptyMap<Uuid, McpStatus>())
        every { fixture.manager.syncingStatus } returns statuses
        var added: McpServerConfig? = null
        coEvery { fixture.manager.addClient(any()) } coAnswers {
            val config = firstArg<McpServerConfig>()
            added = config
            statuses.value = mapOf(config.id to status)
            Unit
        }
        val args = buildJsonObject {
            put("transport", "streamable_http"); put("name", CAMEL); put("url", OLD_URL)
            put("enabled", true); put("connect_timeout_seconds", 1)
        }
        val result = payload(mcpAddTool(fixture.store, fixture.manager, fixture.caller, vault()).execute(args))
        assertTrue(result.toString(), result.containsKey("error"))
        assertTrue("The actual transport error field must remain present", result.containsKey("raw_error"))
        assertEquals("Diagnostic redaction must not mask the ordinary result name", CAMEL, result["name"]!!.jsonPrimitive.content)
        assertEquals(OLD_URL, result["url"]!!.jsonPrimitive.content)
        for (secret in listOf(shortSecret, opaque)) assertFalse("Rollback diagnostic leaked a secret", result.toString().contains(secret))
        assertTrue(result["detail"]!!.jsonPrimitive.content.contains("Конфигурация удалена"))
        assertTrue(fixture.state.value.mcpServers.isEmpty())
        assertEquals(2, fixture.writes)
        assertNotNull(added)
        coVerify(exactly = 1) { fixture.manager.removeClient(added!!) }
    }

    private suspend fun assertTransportDiagnostics(raw: String, secrets: List<String>) {
        val metadataDescription = "tp-link $CAMEL"
        val original = server(CAMEL, listOf(McpTool(name = "tp-link", description = metadataDescription)))
        val config = original.copy(commonOptions = original.commonOptions.copy(enable = true, publicAddressOnly = true))
        val fixture = Fixture(listOf(config))
        val status = McpStatus.Error.from(java.io.IOException(raw, java.io.IOException("Underlying cause: $raw")))
        for (secret in secrets) {
            assertTrue("The real status source is intentionally unredacted", status.message.contains(secret))
            assertTrue(status.detail!!.contains(secret))
        }
        every { fixture.manager.syncingStatus } returns MutableStateFlow(mapOf(ID to status))
        coEvery { fixture.manager.forceResync(ID) } returns Unit
        val before = fixture.state.value
        val idArgs = buildJsonObject { put("id", ID.toString()); put("wait_seconds", 1) }
        val got = payload(mcpGetTool(fixture.store, fixture.manager, fixture.caller).execute(buildJsonObject { put("id", ID.toString()) }))
        val listResult = payload(mcpListTool(fixture.store, fixture.manager, fixture.caller).execute(JsonObject(emptyMap())))
        val listed = listResult["servers"]!!.jsonArray.single().jsonObject
        val tested = payload(mcpTestTool(fixture.store, fixture.manager, vault()).execute(idArgs))
        for (envelope in listOf(got, listed, tested)) {
            assertEquals("ERROR", envelope["status"]!!.jsonPrimitive.content)
            assertEquals(CAMEL, envelope["name"]!!.jsonPrimitive.content)
            assertTrue(envelope["raw_error"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(envelope["error_detail"]!!.jsonPrimitive.content.contains("IOException"))
            for (secret in secrets) assertFalse("Diagnostic field leaked transport data", envelope.toString().contains(secret))
        }
        val tool = got["tools"]!!.jsonArray.single().jsonObject
        assertEquals("tp-link", tool["name"]!!.jsonPrimitive.content)
        assertEquals(metadataDescription, tool["description"]!!.jsonPrimitive.content)
        assertEquals(before, fixture.state.value)
        assertEquals(0, fixture.writes)
        coVerify(exactly = 1) { fixture.manager.forceResync(ID) }
    }

    private fun server(name: String, tools: List<McpTool> = emptyList()) = McpServerConfig.StreamableHTTPServer(
        id = ID, url = OLD_URL, commonOptions = McpCommonOptions(name = name, enable = false, tools = tools))

    private fun request(config: McpServerConfig.StreamableHTTPServer?, name: String, url: String = OLD_URL) = buildJsonObject {
        config?.let { put("id", it.id.toString()) }
        put("transport", "streamable_http"); put("name", name); put("url", url); put("enabled", false)
    }

    private fun payload(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private class Fixture(configs: List<McpServerConfig>) {
        val state = MutableStateFlow(Settings(mcpServers = configs))
        var writes = 0
        val store = mockk<SettingsStore>()
        val manager = mockk<McpManager>()
        val caller = McpControlCaller({ state.value.mcpServers.map { it.id }.toSet() }, {}, {})
        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any()) } coAnswers { firstArg<(Settings) -> Settings>()(state.value).also { state.value = it; writes++ } }
            every { manager.syncingStatus } returns MutableStateFlow(emptyMap<Uuid, McpStatus>())
            coEvery { manager.removeClient(any()) } returns Unit
            coEvery { manager.addClient(any()) } returns Unit
        }
    }

    companion object {
        private val ID = Uuid.parse("12345678-1234-1234-1234-123456789abc")
        private const val OLD_URL = "https://8.8.8.8/mcp"
        private const val NEW_URL = "https://1.1.1.1/mcp"
        private const val API_KEY = "sk-proj-abcdef_key_Canary_987654321"
        private const val CAMEL = "readCustomerProfileFromPrimaryDatabase"
        private const val BASIC_ALIAS = "mcp__12345678_Basic_Memory__fetch_02b4b2542908afc7"
        private val ordinaryValues = listOf("tp-link", CAMEL, "abcdef0123456789abcdef0123456789",
            "https://8.8.8.8/manual/" + CAMEL + "/reference.html")
    }
}
