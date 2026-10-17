package me.rerere.rikkahub.data.ai.mcp

import android.app.Application
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.control.McpControlCaller
import me.rerere.rikkahub.data.ai.mcp.control.mcpListToolsTool
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.isHeadlessMcpToolEnabled
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class McpSecretAliasAuditTest {
    private val id = Uuid.parse("12345678-1111-2222-3333-444444444444")

    @Test fun apiAndJwtShapedServerNamesNeverAppearInCallableAliases() {
        for (secret in secrets) {
            for (serverName in listOf(secret, "Сервер $secret")) {
                val alias = buildMcpToolName(id, serverName, "read_document")
                assertSafeAlias(alias, secret)
                assertEquals(alias, buildMcpToolName(id, serverName, "read_document"))
            }
        }
    }

    @Test fun apiAndJwtShapedRemoteToolNamesNeverAppearInCallableAliases() {
        for (secret in secrets) {
            for (toolName in listOf(secret, "remote $secret")) {
                val alias = buildMcpToolName(id, "ordinary-server", toolName)
                assertSafeAlias(alias, secret)
                assertEquals(alias, buildMcpToolName(id, "ordinary-server", toolName))
            }
        }
    }

    @Test fun apiComponentsAreClassifiedBeforeAliasUnderscoresHideTheirWordBoundaries() {
        // Both fit the old 64-char rule. Prefixing a whole component with mcp__slug_
        // or suffixing it with __tool must not turn a recognized API key into plain text.
        val secret = "sk-" + "aB3".repeat(7)
        assertSafeAlias(buildMcpToolName(id, secret, "read"), secret)
        assertSafeAlias(buildMcpToolName(id, "server", secret), secret)
    }

    @Test fun secretAliasesRetainIdentityDifferencesPastTruncationAndSharedUuidPrefixes() {
        val first = "sk-proj-" + "aB9_".repeat(20) + "first"
        val second = "sk-proj-" + "aB9_".repeat(20) + "second"
        val samePrefixId = Uuid.parse("12345678-9999-2222-3333-444444444444")
        val aliases = listOf(
            buildMcpToolName(id, first, "tool"),
            buildMcpToolName(id, second, "tool"),
            buildMcpToolName(samePrefixId, first, "tool"),
            buildMcpToolName(id, first, "other-tool"),
            buildMcpToolName(id, "server", first),
            buildMcpToolName(id, "other-server", first),
        )
        assertEquals("Every distinct original identity needs a distinct alias", aliases.size, aliases.toSet().size)
        for (alias in aliases) {
            assertSafeAlias(alias, first)
            assertFalse("Do not retain a truncated credential prefix", alias.contains("sk-proj-"))
        }
        assertEquals(aliases.first(), buildMcpToolName(id, first, "tool"))
    }

    @Test fun ordinaryValidOpaqueAndShortPrefixNamesKeepTheirExistingAliases() {
        for ((server, tool) in listOf(
            "my-server" to "do_thing",
            "abcdef0123456789abcdef0123456789" to "t",
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN" to "t",
            "4111111111111111" to "read",
            "sk-proj-short" to "build",
            "sk-svcacct-short" to "build",
        )) {
            val expected = "mcp__12345678_${server}__${tool}"
            assertTrue(expected.length <= 64)
            assertEquals("Do not treat generic long/hex/order text as an API key", expected,
                buildMcpToolName(id, server, tool))
        }
    }

    @Test fun aSecretAliasCannotShadowAnOrdinaryPrivateServerAndHashNamedTool() {
        val secret = "sk-proj-" + "aB9_".repeat(8)
        val hidden = buildMcpToolName(id, secret, "tool")
        val digest = hidden.substringAfterLast('_')
        val ordinary = buildMcpToolName(id, "private", digest)
        assertEquals("mcp__12345678_private__$digest", ordinary)
        assertNotEquals("The safe namespace must not collide with an unchanged ordinary alias", ordinary, hidden)
    }

    @Test fun safeRegistrationAndDiagnosticAliasesAgreeAndExecutionUsesTheExactRemoteName() = runBlocking {
        val serverName = "sk-svcacct-" + "aB9_".repeat(9)
        val remoteName = "sk-proj-" + "cD7_".repeat(9)
        val remoteTool = McpTool(name = remoteName, description = "Читает документ")
        val manager = mockk<McpManager>()
        every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(id, serverName, remoteTool))
        coEvery { manager.callTool(id, remoteName, any()) } returns listOf(UIMessagePart.Text("remote-result"))
        val factory = ChatToolFactory(
            json = Json,
            memoryRepository = mockk(), conversationRepository = mockk(), localTools = mockk<LocalTools>(relaxed = true),
            mcpManager = manager, skillManager = mockk(), workspaceRepository = mockk(),
            rootAccessStore = mockk(), toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
            subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
        )
        val registered = factory.createTools(Settings(), Assistant(localTools = emptyList()), Model()).single()
        assertSafeAlias(registered.name, serverName)
        assertSafeAlias(registered.name, remoteName)

        val config = McpServerConfig.StreamableHTTPServer(id = id, url = "https://8.8.8.8/mcp",
            commonOptions = McpCommonOptions(name = serverName, tools = listOf(remoteTool)))
        val state = MutableStateFlow(Settings(mcpServers = listOf(config)))
        val store = mockk<SettingsStore> { every { settingsFlow } returns state }
        val parts = mcpListToolsTool(store, manager, McpControlCaller({ setOf(id) }, {}, {}))
            .execute(JsonObject(emptyMap()))
        val row = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject["tools"]!!
            .jsonArray.single().jsonObject
        assertEquals(registered.name, row["dispatchable_name"]!!.jsonPrimitive.content)
        assertEquals(config, state.value.mcpServers.single())

        val args = buildJsonObject { put("path", "readme.md") }
        val result = registered.execute(args)
        assertEquals("remote-result", (result.single() as UIMessagePart.Text).text)
        coVerify(exactly = 1) { manager.callTool(id, remoteName, args) }
    }

    private fun assertSafeAlias(alias: String, secret: String) {
        assertTrue("Alias must stay provider-compatible: $alias", alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        assertTrue(alias.startsWith("mcp__"))
        assertFalse("A credential must not be advertised as a callable name", alias.contains(secret))
        assertFalse("A truncated credential prefix must not remain visible", alias.contains(secret.take(12)))
    }

    companion object {
        private val secrets = listOf(
            "sk-proj-" + "aB9_".repeat(8) + "-Zx7",
            "sk-svcacct-" + "aB9_".repeat(8) + "-Zx7",
            "sk-" + "aB3".repeat(8),
            "ghp_" + "aB3".repeat(8),
            "AIza" + "aB3".repeat(8),
            "AKIAIOSFODNN7EXAMPLE",
            "xoxb-" + "abc123-".repeat(4) + "xyz",
            "tp-" + "fixture_Key_abc123DEF456ghi789",
            "gsk_" + "fixture_Key_abc123DEF456ghi789",
            "xai-" + "fixture_Key_abc123DEF456ghi789",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno",
        )
    }
}

class McpDecoratedCredentialAliasAuditTest {
    @Test fun decoratedKnownCredentialsUseOpaqueAliasesWhileOrdinaryShortLabelsStayUnchanged() {
        val id = Uuid.parse("12345678-1111-2222-3333-444444444444")
        val material = "A1b2".repeat(5)
        val credentials = listOf(
            "gsk_$material", "tp-$material", "xai-$material",
            "sk-$material", "sk-proj-$material", "sk-svcacct-$material",
            "ghp_$material", "AIza$material", "AKIAIOSFODNN7EXAMPLE",
            "eyJabcdefgh.abcdefgh.abcdefgh",
        )
        for (credential in credentials) {
            for (decorated in listOf("_${credential}_", "--${credential}--", "[${credential}]")) {
                val header = me.rerere.rikkahub.data.ai.mcp.control.McpHeaderRedactor
                    .redactHeaderValue("X-Mode", decorated)
                val displays = listOf(
                    header,
                    me.rerere.rikkahub.data.ai.mcp.control.redactedMcpText("Значение $decorated"),
                    me.rerere.rikkahub.data.ai.mcp.control.redactedMcpText("https://example.test/$decorated"),
                )
                for (display in displays) {
                    assertFalse("A decorated known credential must not appear in MCP display: $decorated",
                        display.contains(credential))
                    assertFalse("A truncated decorated credential must also remain hidden in MCP display: $decorated",
                        display.contains(credential.take(12)))
                }
                val validation = me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation
                    .validateSecureHeaders(listOf("X-Mode" to decorated))
                assertTrue("A decorated credential in a neutral header requires a secret reference: $decorated",
                    validation is me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation.Result.Reject)
                assertEquals("plaintext_secret_forbidden",
                    (validation as me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation.Result.Reject).error)
                for (alias in listOf(buildMcpToolName(id, "s", decorated), buildMcpToolName(id, decorated, "t"))) {
                    assertFalse("A decorated known credential must not appear in a callable alias: $decorated",
                        alias.contains(credential))
                    assertFalse("A truncated decorated credential must also remain hidden: $decorated",
                        alias.contains(credential.take(12)))
                    assertTrue(alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
                }
            }
        }
        for (ordinary in listOf("_gsk_demo_", "_tp-demo_", "_xai-server_", "_sk-proj-short_", "_ordinary_tool_")) {
            assertEquals(ordinary, me.rerere.rikkahub.data.ai.mcp.control.McpHeaderRedactor
                .redactHeaderValue("X-Mode", ordinary))
            assertEquals(ordinary, me.rerere.rikkahub.data.ai.mcp.control.redactedMcpText(ordinary))
            assertTrue(me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation
                .validateSecureHeaders(listOf("X-Mode" to ordinary)) is
                me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation.Result.Ok)
            assertEquals("mcp__12345678_s__${ordinary}", buildMcpToolName(id, "s", ordinary))
            assertEquals("mcp__12345678_${ordinary}__t", buildMcpToolName(id, ordinary, "t"))
        }
    }
}


class McpShortProviderAliasCompatibilityAuditTest {
    private val id = Uuid.parse("12345678-1111-2222-3333-444444444444")

    @Test fun shortProviderLikeLabelsKeepTheirOriginalServerAndToolAliases() {
        for (label in listOf("tp-demo", "gsk_demo", "xai-server")) {
            assertEquals("mcp__12345678_${label}__read", buildMcpToolName(id, label, "read"))
            assertEquals("mcp__12345678_server__${label}", buildMcpToolName(id, "server", label))
        }
    }

    @Test fun nineteenMaterialCharactersAreStillOrdinaryProviderLikeNames() {
        for (prefix in listOf("tp-", "gsk_", "xai-")) {
            val label = prefix + "a".repeat(19)
            assertEquals("mcp__12345678_${label}__read", buildMcpToolName(id, label, "read"))
            assertEquals("mcp__12345678_server__${label}", buildMcpToolName(id, "server", label))
        }
    }

    @Test fun twentyMaterialCharactersStillQualifyAsAProviderCredential() {
        for (prefix in listOf("tp-", "gsk_", "xai-")) {
            val credential = prefix + "a".repeat(20)
            for (alias in listOf(buildMcpToolName(id, credential, "read"), buildMcpToolName(id, "server", credential))) {
                assertFalse(alias.contains(credential))
                assertFalse(alias.contains(prefix + "aaaa"))
                assertTrue(alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
            }
        }
    }
}


class McpRegisteredDescriptionRedactionAuditTest {
    @Test fun registeredToolDescriptionsHideKnownCredentialShapesButPreserveSchemaAndExecution() = runBlocking {
        for (secret in listOf(
            "sk-proj-" + "aB9_".repeat(8),
            "ghp_" + "aB3".repeat(8),
            "AKIAIOSFODNN7EXAMPLE",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno",
            "tp-" + "aB9_".repeat(8),
            "gsk_" + "aB9_".repeat(8),
            "xai-" + "aB9_".repeat(8),
        )) {
            val schemaProperty = "sk-proj-protocol_property_123456789"
            val schema = InputSchema.Obj(properties = buildJsonObject {
                put(schemaProperty, buildJsonObject { put("type", "string") })
            }, required = listOf(schemaProperty))
            val remote = McpTool(name = "read_file", description = "Описание $secret готово", inputSchema = schema)
            val manager = mockk<McpManager>()
            val id = Uuid.parse("12345678-1111-2222-3333-444444444444")
            every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(id, "server", remote))
            coEvery { manager.callTool(id, remote.name, any()) } returns listOf(UIMessagePart.Text("remote-result"))
            val factory = factory(manager)

            val registered = factory.createTools(Settings(), Assistant(localTools = emptyList()), Model()).single()
            assertEquals("mcp__12345678_server__read_file", registered.name)
            assertTrue(registered.description.startsWith("Описание "))
            assertTrue(registered.description.endsWith(" готово"))
            assertFalse("Provider-facing tool descriptions must not expose credentials", registered.description.contains(secret))
            assertFalse("Partial credential masking is insufficient", registered.description.contains(secret.take(12)))
            assertEquals(schema, registered.parameters())
            assertEquals("Описание $secret готово", remote.description)

            val args = buildJsonObject { put(schemaProperty, "readme.md") }
            val result = registered.execute(args)
            assertEquals("remote-result", (result.single() as UIMessagePart.Text).text)
            coVerify(exactly = 1) { manager.callTool(id, remote.name, args) }
        }
    }

    @Test fun ordinaryRegisteredDescriptionsRemainByteIdentical() = runBlocking {
        val manager = mockk<McpManager>()
        val description = "Читает документ из рабочей папки"
        every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(Uuid.random(), "server",
            McpTool(name = "read_file", description = description)))
        val registered = factory(manager).createTools(Settings(), Assistant(localTools = emptyList()), Model()).single()
        assertEquals(description, registered.description)
    }

    private fun factory(manager: McpManager) = ChatToolFactory(
        json = Json,
        memoryRepository = mockk(), conversationRepository = mockk(), localTools = mockk<LocalTools>(relaxed = true),
        mcpManager = manager, skillManager = mockk(), workspaceRepository = mockk(),
        rootAccessStore = mockk(), toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
        subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
    )
}


class McpPrefixCollisionDispatchAuditTest {
    private val firstId = Uuid.parse("12345678-1111-2222-3333-444444444444")
    private val secondId = Uuid.parse("12345678-9999-2222-3333-444444444444")
    private val legacyAlias = "mcp__12345678_shared__read"

    @Test fun `a listed second server dispatches to its exact original identity despite a shared uuid prefix`() = runBlocking {
        val fixture = fixture(listOf(server(firstId), server(secondId)), setOf(firstId, secondId))
        val secondRow = fixture.rows().single { it["server_id"]!!.jsonPrimitive.content == secondId.toString() }
        val alias = secondRow["dispatchable_name"]!!.jsonPrimitive.content
        // GenerationLoop uses find by name. Exercise the real registered closure selected
        // by the name mcp_list_tools tells the caller to use for the second server.
        val selected = fixture.tools.find { it.name == alias }
        assertNotNull("The listed alias must be registered", selected)
        val args = buildJsonObject { put("path", "readme.md") }
        val parts = selected!!.execute(args)
        assertEquals("A shared prefix must never route the second server to the first one", "second-result",
            (parts.single() as UIMessagePart.Text).text)
        coVerify(exactly = 1) { fixture.manager.callTool(secondId, "read", args) }
        coVerify(exactly = 0) { fixture.manager.callTool(firstId, any(), any()) }
    }

    @Test fun `registration listing and headless matching agree on distinct collision aliases`() = runBlocking {
        val fixture = fixture(listOf(server(firstId), server(secondId)), setOf(firstId, secondId))
        assertEquals("Two original identities must have two callable names", 2, fixture.tools.map { it.name }.toSet().size)
        assertTrue(fixture.tools.all { it.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")) })
        val rows = fixture.rows()
        assertEquals(2, rows.map { it["dispatchable_name"]!!.jsonPrimitive.content }.toSet().size)
        for (id in listOf(firstId, secondId)) {
            val row = rows.single { it["server_id"]!!.jsonPrimitive.content == id.toString() }
            val alias = row["dispatchable_name"]!!.jsonPrimitive.content
            assertNotEquals("An ambiguous legacy grant must not transfer to either new identity", legacyAlias, alias)
            assertEquals(1, fixture.tools.count { it.name == alias })
            val onlyThisServer = fixture.execution.copy(callerConversationConfig = ConversationConfig(mcpServers = setOf(id)))
            assertTrue(isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant, onlyThisServer, alias))
            val otherId = if (id == firstId) secondId else firstId
            assertFalse(isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant,
                onlyThisServer.copy(callerConversationConfig = ConversationConfig(mcpServers = setOf(otherId))), alias))
        }
        assertFalse("The old ambiguous alias is no longer a live capability",
            isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant, fixture.execution, legacyAlias))
    }

    @Test fun `a configured disabled sibling cannot make a colliding legacy alias safe for the selected server`() = runBlocking {
        val fixture = fixture(listOf(server(firstId, enabled = false), server(secondId)), setOf(secondId))
        val registered = fixture.tools.single()
        assertNotEquals("Collision context must include configured servers outside the advertised set", legacyAlias, registered.name)
        val row = fixture.rows(secondId).single()
        assertEquals(registered.name, row["dispatchable_name"]!!.jsonPrimitive.content)
        assertTrue(isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant, fixture.execution, registered.name))
        assertFalse(isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant, fixture.execution, legacyAlias))
        val result = registered.execute(JsonObject(emptyMap()))
        assertEquals("second-result", (result.single() as UIMessagePart.Text).text)
        coVerify(exactly = 1) { fixture.manager.callTool(secondId, "read", JsonObject(emptyMap())) }
        coVerify(exactly = 0) { fixture.manager.callTool(firstId, any(), any()) }
    }

    @Test fun `an ordinary alias stays unchanged when no configured uuid prefix collision exists`() = runBlocking {
        val fixture = fixture(listOf(server(firstId)), setOf(firstId))
        assertEquals(legacyAlias, fixture.tools.single().name)
        assertEquals(legacyAlias, fixture.rows().single()["dispatchable_name"]!!.jsonPrimitive.content)
        assertTrue(isHeadlessMcpToolEnabled(fixture.settings, fixture.assistant, fixture.execution, legacyAlias))
    }

    private fun server(id: Uuid, enabled: Boolean = true) = McpServerConfig.StreamableHTTPServer(
        id = id, url = if (id == firstId) "https://first.example/mcp" else "https://second.example/mcp",
        commonOptions = McpCommonOptions(name = "shared", enable = enabled, tools = listOf(McpTool(name = "read"))),
    )

    private suspend fun fixture(configs: List<McpServerConfig>, selected: Set<Uuid>): Fixture {
        val manager = mockk<McpManager>()
        every { manager.getAllAvailableTools(any(), any()) } returns configs
            .filter { it.id in selected && it.commonOptions.enable }
            .map { Triple(it.id, it.commonOptions.name, it.commonOptions.tools.single()) }
        coEvery { manager.callTool(firstId, "read", any()) } returns listOf(UIMessagePart.Text("first-result"))
        coEvery { manager.callTool(secondId, "read", any()) } returns listOf(UIMessagePart.Text("second-result"))
        val settings = Settings(mcpServers = configs)
        val assistant = Assistant(mcpServers = selected, localTools = emptyList())
        val store = mockk<SettingsStore> { every { settingsFlow } returns MutableStateFlow(settings) }
        val factory = ChatToolFactory(
            json = Json, memoryRepository = mockk(), conversationRepository = mockk(),
            localTools = mockk<LocalTools>(relaxed = true), mcpManager = manager,
            skillManager = mockk(), workspaceRepository = mockk(), rootAccessStore = mockk(),
            toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
            subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
        )
        val tools = factory.createTools(settings, assistant, Model())
        return Fixture(manager, settings, assistant, store, tools,
            RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.WORKFLOW,
                callerConversationConfig = ConversationConfig(mcpServers = selected)))
    }

    private data class Fixture(
        val manager: McpManager,
        val settings: Settings,
        val assistant: Assistant,
        val store: SettingsStore,
        val tools: List<Tool>,
        val execution: RunExecutionContext,
    ) {
        suspend fun rows(id: Uuid? = null): List<JsonObject> {
            val args = buildJsonObject { if (id != null) put("id", id.toString()) }
            val parts = mcpListToolsTool(store, manager, McpControlCaller({ assistant.mcpServers }, {}, {})).execute(args)
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            return result["tools"]!!.jsonArray.map { it.jsonObject }
        }
    }
}


@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class McpFactorySettingsSnapshotAuditTest {
    private val firstId = Uuid.parse("12345678-1111-2222-3333-444444444444")
    private val secondId = Uuid.parse("12345678-9999-2222-3333-444444444444")
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `factory cannot enumerate newly added live tools using an earlier empty namespace snapshot`() = runBlocking {
        val snapshot = Settings(mcpServers = emptyList())
        val newer = snapshot.copy(mcpServers = listOf(server(firstId), server(secondId)))
        val tools = registerAcrossLiveChange(snapshot, newer)
        assertTrue("The factory registry must use the same snapshot for enumeration and namespace metadata", tools.isEmpty())
    }

    @Test fun `factory keeps its selected snapshot and remembered namespace when the live list changes during setup`() = runBlocking {
        val value = Json.parseToJsonElement(JsonInstant.encodeToString(Settings(mcpServers = listOf(server(secondId)))))
            .jsonObject.toMutableMap()
        value["mcpToolNameCollisionPrefixes"] = JsonArray(listOf(JsonPrimitive("12345678")))
        val snapshot = JsonInstant.decodeFromString<Settings>(JsonObject(value).toString())
        val tools = registerAcrossLiveChange(snapshot, snapshot.copy(mcpServers = emptyList()))
        assertEquals("A live refresh must not replace the factory's enumeration snapshot", 1, tools.size)
        assertNotEquals("The snapshot's remembered namespace must accompany its tool list", "mcp__12345678_shared__read", tools.single().name)
        assertTrue(tools.single().name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }

    private suspend fun registerAcrossLiveChange(snapshot: Settings, newer: Settings): List<Tool> {
        val live = MutableStateFlow(snapshot)
        val store = mockk<SettingsStore> { every { settingsFlow } returns live }
        val scope = AppScope().apply { cancel() } // No network/session lifecycle starts.
        val manager = McpManager(store, scope, mockk(), mockk(relaxed = true))
        val workspace = mockk<WorkspaceRepository>()
        var changedDuringSetup = false
        coEvery { workspace.getById(any()) } coAnswers {
            // This call precedes MCP enumeration and can suspend in production. Force the
            // exact interleaving without scheduler timing, mocking no MCP enumeration code.
            live.value = newer
            changedDuringSetup = true
            null
        }
        val assistant = Assistant(localTools = emptyList(), mcpServers = setOf(firstId, secondId), workspaceId = Uuid.random())
        val factory = ChatToolFactory(
            json = Json, memoryRepository = mockk(), conversationRepository = mockk(),
            localTools = mockk<LocalTools>(relaxed = true), mcpManager = manager,
            skillManager = mockk(), workspaceRepository = workspace, rootAccessStore = mockk(),
            toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
            subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
        )
        try {
            val tools = factory.createTools(snapshot, assistant, Model())
            assertTrue("The live state must change before actual MCP enumeration", changedDuringSetup)
            assertEquals(newer, live.value)
            return tools.filter { it.name.startsWith("mcp__") }
        } finally {
            scope.cancel()
            for (name in listOf("httpClient", "publicHttpClient")) {
                (McpManager::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(manager) as HttpClient).close()
            }
            for (name in listOf("okHttpClient", "publicOkHttpClient")) {
                val client = McpManager::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(manager) as OkHttpClient
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun server(id: Uuid) = McpServerConfig.StreamableHTTPServer(id = id, url = "https://example.test/$id",
        commonOptions = McpCommonOptions(name = "shared", tools = listOf(McpTool(name = "read"))))
}
