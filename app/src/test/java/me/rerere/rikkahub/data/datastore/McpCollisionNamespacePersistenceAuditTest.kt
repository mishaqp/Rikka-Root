package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.control.McpControlCaller
import me.rerere.rikkahub.data.ai.mcp.control.mcpListToolsTool
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.resolveToolAutoApproval
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.service.isHeadlessMcpToolEnabled
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class McpCollisionNamespacePersistenceAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val firstId = Uuid.parse("12345678-1111-2222-3333-444444444444")
    private val secondId = Uuid.parse("12345678-9999-2222-3333-444444444444")
    private val markerField = "mcpToolNameCollisionPrefixes"
    private val markerKey = stringSetPreferencesKey("mcp_tool_name_collision_prefixes")
    private val legacyAlias = "mcp__12345678_shared__read"

    @Before fun setup() {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("no-backup")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        singletonField().set(null, null)
    }

    @After fun cleanup() = runBlocking {
        persisted()?.let { store ->
            var delegate: Any = store
            while (delegate.javaClass.declaredFields.none { it.name == "scope" }) {
                delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate) as Any
            }
            val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        singletonField().set(null, null)
    }

    @Test fun `old backup defaults to an empty namespace marker and explicit marker roundtrips`() {
        val old = JsonInstant.decodeFromString<Settings>("{}")
        assertTrue(markers(old).isEmpty())
        // JSON construction lets this regression compile before the Settings field exists.
        val restored = JsonInstant.decodeFromString<Settings>("""{"mcpToolNameCollisionPrefixes":["12345678"]}""")
        assertEquals(setOf("12345678"), markers(restored))
        assertEquals(setOf("12345678"), markers(JsonInstant.decodeFromString(JsonInstant.encodeToString(restored))))
    }

    @Test fun `adding a collision persists a monotonic marker across removal clear attempt restore and reload`() = runBlocking {
        SettingsStore.restoreBeforeInitialization(context, Settings(mcpServers = listOf(server(firstId))))
        val store = frozen()
        val added = store.update { it.copy(mcpServers = listOf(server(firstId), server(secondId))) }
        assertEquals(setOf("12345678"), markers(added))
        val removed = store.update { it.copy(mcpServers = listOf(server(secondId))) }
        assertEquals(setOf("12345678"), markers(removed))
        val cleared = store.update { withoutMarkers(it.copy(mcpServers = emptyList())) }
        assertEquals("A caller cannot erase known ambiguous namespaces", setOf("12345678"), markers(cleared))
        assertEquals(setOf("12345678"), markers(frozen().update { it }))
        SettingsStore.restoreBeforeInitialization(context, Settings())
        assertEquals("Restoring an old backup cannot resurrect the legacy namespace", setOf("12345678"), markers(frozen().update { it }))
    }

    @Test fun `first deletion from a legacy collision remembers the previous namespace inside the write transaction`() = runBlocking {
        seedLegacyCollision()
        val removed = frozen().update { withoutMarkers(it.copy(mcpServers = listOf(server(secondId)))) }
        assertEquals("The previous servers must be examined before applying deletion", setOf("12345678"), markers(removed))
        assertEquals(setOf("12345678"), markers(frozen().update { it }))
        assertEquals(listOf(secondId), removed.mcpServers.map { it.id })
    }

    @Test fun `an old grant for the removed server never authorizes the surviving server after reload`() = runBlocking {
        ToolApprovalTestStore(temporary.newFolder()).use { grants ->
            grants.preferences.grantAlways(legacyAlias)
            seedLegacyCollision()
            frozen().update { it.copy(mcpServers = listOf(server(secondId))) }
            val settings = frozen().update { it }
            val assistant = Assistant(localTools = emptyList(), mcpServers = setOf(secondId))
            val manager = mockk<McpManager>()
            every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(secondId, "shared", McpTool(name = "read")))
            coEvery { manager.callTool(secondId, "read", any()) } returns listOf(UIMessagePart.Text("second-result"))
            val factory = ChatToolFactory(
                json = Json, memoryRepository = mockk(), conversationRepository = mockk(),
                localTools = mockk<LocalTools>(relaxed = true), mcpManager = manager,
                skillManager = mockk(), workspaceRepository = mockk(), rootAccessStore = mockk(),
                toolApprovalPreferences = grants.preferences, runtimeBindings = mockk(),
                subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
            )
            val registered = factory.createTools(settings, assistant, Model()).single()
            assertNotEquals("Deleting the first server must not resurrect its legacy alias on another UUID", legacyAlias, registered.name)
            assertTrue("The original stored grant is retained as data", legacyAlias in grants.preferences.current())
            assertFalse(resolveToolAutoApproval(grants.preferences, Uuid.random(), registered.name, false))
            val store = mockk<SettingsStore> { every { settingsFlow } returns MutableStateFlow(settings) }
            val parts = mcpListToolsTool(store, manager, McpControlCaller({ setOf(secondId) }, {}, {})).execute(buildJsonObject {})
            val row = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject["tools"]!!.jsonArray.single().jsonObject
            assertEquals(registered.name, row["dispatchable_name"]!!.jsonPrimitive.content)
            val execution = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.WORKFLOW,
                callerConversationConfig = ConversationConfig(mcpServers = setOf(secondId)))
            assertTrue(isHeadlessMcpToolEnabled(settings, assistant, execution, registered.name))
            assertFalse(isHeadlessMcpToolEnabled(settings, assistant, execution, legacyAlias))
        }
    }

    @Test fun `atomic replacement by another uuid sharing its prefix cannot inherit the old grant`() = runBlocking {
        ToolApprovalTestStore(temporary.newFolder()).use { grants ->
            grants.preferences.grantAlways(legacyAlias)
            SettingsStore.restoreBeforeInitialization(context, Settings(mcpServers = listOf(server(firstId))))
            val replaced = frozen().update { it.copy(mcpServers = listOf(server(secondId))) }
            assertEquals("The combined old and new UUID sets must reveal the replacement collision",
                setOf("12345678"), markers(replaced))
            val settings = frozen().update { it }
            assertEquals(listOf(secondId), settings.mcpServers.map { it.id })
            val assistant = Assistant(localTools = emptyList(), mcpServers = setOf(secondId))
            val manager = mockk<McpManager>()
            every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(secondId, "shared", McpTool(name = "read")))
            val factory = ChatToolFactory(
                json = Json, memoryRepository = mockk(), conversationRepository = mockk(),
                localTools = mockk<LocalTools>(relaxed = true), mcpManager = manager,
                skillManager = mockk(), workspaceRepository = mockk(), rootAccessStore = mockk(),
                toolApprovalPreferences = grants.preferences, runtimeBindings = mockk(),
                subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
            )
            val alias = factory.createTools(settings, assistant, Model()).single().name
            assertNotEquals(legacyAlias, alias)
            assertTrue("Keep existing grants as data", legacyAlias in grants.preferences.current())
            assertFalse(resolveToolAutoApproval(grants.preferences, Uuid.random(), alias, false))
            val execution = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.WORKFLOW,
                callerConversationConfig = ConversationConfig(mcpServers = setOf(secondId)))
            assertTrue(isHeadlessMcpToolEnabled(settings, assistant, execution, alias))
            assertFalse(isHeadlessMcpToolEnabled(settings, assistant, execution, legacyAlias))
        }
    }

    @Test fun `removing the only server then reloading cannot transfer its old grant to a later uuid with the same prefix`() = runBlocking {
        ToolApprovalTestStore(temporary.newFolder()).use { grants ->
            grants.preferences.grantAlways(legacyAlias)
            SettingsStore.restoreBeforeInitialization(context, Settings(mcpServers = listOf(server(firstId))))
            frozen().update { it.copy(mcpServers = emptyList()) }
            val emptyAfterReload = frozen().update { it }
            assertTrue(emptyAfterReload.mcpServers.isEmpty())
            frozen().update { it.copy(mcpServers = listOf(server(secondId))) }
            val settings = frozen().update { it }
            val (assistant, alias) = registeredAlias(settings)
            assertNotEquals("The namespace must remember its earlier UUID across an empty list and reload", legacyAlias, alias)
            assertTrue(legacyAlias in grants.preferences.current())
            assertFalse(resolveToolAutoApproval(grants.preferences, Uuid.random(), alias, false))
            val execution = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.WORKFLOW,
                callerConversationConfig = ConversationConfig(mcpServers = setOf(secondId)))
            assertTrue(isHeadlessMcpToolEnabled(settings, assistant, execution, alias))
            assertFalse(isHeadlessMcpToolEnabled(settings, assistant, execution, legacyAlias))
            val expected = setOf(firstId.toString(), secondId.toString())
            assertEquals(expected, seenServerIds(settings))
            assertEquals(expected, seenServerIds(JsonInstant.decodeFromString(JsonInstant.encodeToString(settings))))
            val attemptedClear = frozen().update { current ->
                val json = Json.parseToJsonElement(JsonInstant.encodeToString(current)).jsonObject.toMutableMap()
                json["mcpToolNameSeenServerIds"] = JsonArray(emptyList())
                JsonInstant.decodeFromString(JsonObject(json).toString())
            }
            assertEquals("Model-independent transforms cannot erase namespace ownership history", expected, seenServerIds(attemptedClear))
            SettingsStore.restoreBeforeInitialization(context, Settings())
            assertEquals(expected, seenServerIds(frozen().update { it }))
        }
    }

    @Test fun `removing then readding the same uuid keeps the ordinary alias and its grant`() = runBlocking {
        ToolApprovalTestStore(temporary.newFolder()).use { grants ->
            grants.preferences.grantAlways(legacyAlias)
            SettingsStore.restoreBeforeInitialization(context, Settings(mcpServers = listOf(server(firstId))))
            frozen().update { it.copy(mcpServers = emptyList()) }
            assertTrue(frozen().update { it }.mcpServers.isEmpty())
            frozen().update { it.copy(mcpServers = listOf(server(firstId))) }
            val settings = frozen().update { it }
            val (assistant, alias) = registeredAlias(settings)
            assertEquals("The same original identity must not be treated as a collision", legacyAlias, alias)
            assertTrue(resolveToolAutoApproval(grants.preferences, Uuid.random(), alias, false))
            val execution = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.WORKFLOW,
                callerConversationConfig = ConversationConfig(mcpServers = setOf(firstId)))
            assertTrue(isHeadlessMcpToolEnabled(settings, assistant, execution, legacyAlias))
        }
    }

    private suspend fun registeredAlias(settings: Settings): Pair<Assistant, String> {
        val configured = settings.mcpServers.single()
        val assistant = Assistant(localTools = emptyList(), mcpServers = setOf(configured.id))
        val manager = mockk<McpManager>()
        every { manager.getAllAvailableTools(any(), any()) } returns listOf(Triple(configured.id, "shared", McpTool(name = "read")))
        val factory = ChatToolFactory(
            json = Json, memoryRepository = mockk(), conversationRepository = mockk(),
            localTools = mockk<LocalTools>(relaxed = true), mcpManager = manager,
            skillManager = mockk(), workspaceRepository = mockk(), rootAccessStore = mockk(),
            toolApprovalPreferences = mockk(), runtimeBindings = mockk(),
            subAgentEngine = { error("Subagents disabled") }, subAgentRegistry = mockk(),
        )
        val alias = factory.createTools(settings, assistant, Model()).single().name
        val store = mockk<SettingsStore> { every { settingsFlow } returns MutableStateFlow(settings) }
        val parts = mcpListToolsTool(store, manager, McpControlCaller({ setOf(configured.id) }, {}, {})).execute(buildJsonObject {})
        val row = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals(alias, row["dispatchable_name"]!!.jsonPrimitive.content)
        return assistant to alias
    }

    private fun seenServerIds(settings: Settings): Set<String> =
        Json.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject["mcpToolNameSeenServerIds"]
            ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private suspend fun seedLegacyCollision() {
        SettingsStore.restoreBeforeInitialization(context, Settings())
        // Simulate an existing pre-marker DataStore, bypassing only initialization writes.
        persisted()!!.edit {
            it[SettingsStore.MCP_SERVERS] = JsonArray(listOf(server(firstId), server(secondId)).map { config ->
                JsonInstant.encodeToJsonElement(McpServerConfig.serializer(), config)
            }).toString()
            it.remove(markerKey)
        }
    }

    private fun markers(settings: Settings): Set<String> =
        Json.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject[markerField]
            ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun withoutMarkers(settings: Settings): Settings {
        val value = Json.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject.toMutableMap()
        value[markerField] = JsonArray(emptyList())
        return JsonInstant.decodeFromString(JsonObject(value).toString())
    }

    private fun server(id: Uuid) = McpServerConfig.StreamableHTTPServer(id = id,
        url = "https://example.test/${id}", commonOptions = McpCommonOptions(name = "shared", tools = listOf(McpTool(name = "read"))))

    private fun frozen() = SettingsStore(context, AppScope().apply { cancel() })
    private fun singletonField() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun persisted() = singletonField().get(null) as? DataStore<Preferences>
}
