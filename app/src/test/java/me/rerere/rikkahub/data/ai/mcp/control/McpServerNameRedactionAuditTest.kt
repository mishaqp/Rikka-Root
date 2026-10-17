package me.rerere.rikkahub.data.ai.mcp.control

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.ai.mcp.secureMcpConfigForPersistence
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.pages.setting.protectMcpSettingsSecrets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Model-facing names are redacted; saved names, ownership and deletion retain their actual values. */
class McpServerNameRedactionAuditTest {
    @Test fun listingHidesAnApiKeyInAServerNameWithoutChangingTheSavedConfiguration() = runBlocking {
        val fixture = Fixture("Рабочий $SECRET")
        val before = fixture.state.value

        val result = payload(mcpListTool(fixture.store, fixture.manager, fixture.caller).execute(JsonObject(emptyMap())))
        val server = result["servers"]!!.jsonArray.single().jsonObject

        assertRedactedName(server, fixture.config.id)
        assertEquals(before, fixture.state.value)
        assertEquals("Рабочий $SECRET", fixture.state.value.mcpServers.single().commonOptions.name)
        coVerify(exactly = 0) { fixture.store.update(any()) }
    }

    @Test fun gettingAServerHidesTheCompleteProjectKeyAndLeavesTheSavedNameIntact() = runBlocking {
        val fixture = Fixture("Рабочий $SECRET")
        val before = fixture.state.value

        val result = payload(mcpGetTool(fixture.store, fixture.manager, fixture.caller).execute(fixture.idArgs))

        assertRedactedName(result, fixture.config.id)
        assertEquals(before, fixture.state.value)
        coVerify(exactly = 0) { fixture.store.update(any()) }
    }

    @Test fun deletingAServerRedactsItsResultButDisconnectsTheOriginalConfiguration() = runBlocking {
        val fixture = Fixture("Рабочий $SECRET")

        val result = payload(mcpDeleteTool(fixture.store, fixture.manager, fixture.caller).execute(fixture.idArgs))

        assertRedactedName(result, fixture.config.id)
        assertTrue(result["deleted"]!!.jsonPrimitive.boolean)
        assertTrue(fixture.state.value.mcpServers.isEmpty())
        assertEquals(listOf(fixture.config.id), fixture.removedIds)
        assertEquals("Рабочий $SECRET", fixture.config.commonOptions.name)
        coVerify(exactly = 1) { fixture.manager.removeClient(fixture.config) }
    }

    @Test fun ordinaryServerNamesAndUuidsArePreservedInListAndDeleteResults() = runBlocking {
        val name = "Рабочий сервер 11111111-2222-3333-4444-555555555555"
        val fixture = Fixture(name)
        val before = fixture.state.value

        val listResult = payload(mcpListTool(fixture.store, fixture.manager, fixture.caller).execute(JsonObject(emptyMap())))
        val listed = listResult["servers"]!!.jsonArray.single().jsonObject
        assertEquals(name, listed["name"]!!.jsonPrimitive.content)
        assertEquals(fixture.config.id.toString(), listed["id"]!!.jsonPrimitive.content)
        assertEquals(before, fixture.state.value)

        val deleted = payload(mcpDeleteTool(fixture.store, fixture.manager, fixture.caller).execute(fixture.idArgs))
        assertEquals(name, deleted["name"]!!.jsonPrimitive.content)
        assertEquals(fixture.config.id.toString(), deleted["id"]!!.jsonPrimitive.content)
        assertTrue(deleted["deleted"]!!.jsonPrimitive.boolean)
    }

    private fun assertRedactedName(result: JsonObject, id: Uuid) {
        assertEquals(id.toString(), result["id"]!!.jsonPrimitive.content)
        val name = result["name"]!!.jsonPrimitive.content
        assertTrue("The ordinary Russian part of the name must remain readable", name.startsWith("Рабочий "))
        assertFalse("The model must not receive a secret in a server name", result.toString().contains(SECRET))
        assertFalse("The project-key prefix must not leak after partial redaction", name.contains("sk-proj-"))
        assertFalse("The secret suffix must not remain visible", name.contains("Canary_987654321"))
    }

    private fun payload(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private class Fixture(name: String) {
        val config = McpServerConfig.StreamableHTTPServer(
            url = "https://8.8.8.8/mcp",
            commonOptions = McpCommonOptions(name = name, enable = false),
        )
        val state = MutableStateFlow(Settings(mcpServers = listOf(config)))
        val store = mockk<SettingsStore>()
        val manager = mockk<McpManager>()
        val removedIds = mutableListOf<Uuid>()
        val caller = McpControlCaller({ setOf(config.id) }, {}, { removedIds += it })
        val idArgs = buildJsonObject { put("id", config.id.toString()) }

        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any()) } coAnswers {
                firstArg<(Settings) -> Settings>()(state.value).also { state.value = it }
            }
            every { manager.syncingStatus } returns MutableStateFlow(mapOf(config.id to McpStatus.Connected))
            coEvery { manager.removeClient(any()) } returns Unit
        }
    }

    companion object {
        private const val SECRET = "sk-proj-abcdef_key_Canary_987654321"
    }
}


class McpKnownCredentialDisplayAuditTest {
    @Test fun theTextHelperHidesWholeGithubAwsAndJwtCredentials() {
        for (secret in secrets) {
            val output = redactedMcpText("Сервер $secret готов")
            assertHidden(output, secret)
            assertTrue(output.startsWith("Сервер "))
            assertTrue(output.endsWith(" готов"))
        }
    }

    @Test fun listAndGetHideKnownCredentialsInNamesAndNeutralHeadersWithoutSaving() = runBlocking {
        for (secret in secrets) {
            val fixture = Fixture(secret)
            val listed = payload(mcpListTool(fixture.store, fixture.manager, fixture.caller).execute(JsonObject(emptyMap())))
                .getValue("servers").jsonArray.single().jsonObject
            val fetched = payload(mcpGetTool(fixture.store, fixture.manager, fixture.caller).execute(
                buildJsonObject { put("id", fixture.config.id.toString()) }))
            for (server in listOf(listed, fetched)) {
                assertEquals(fixture.config.id.toString(), server["id"]!!.jsonPrimitive.content)
                assertHidden(server["name"]!!.jsonPrimitive.content, secret)
                val header = server["headers"]!!.jsonArray.single().jsonObject
                assertEquals("X-Mode", header["name"]!!.jsonPrimitive.content)
                assertHidden(header["value"]!!.jsonPrimitive.content, secret)
            }
            fixture.assertUnchanged()
        }
    }

    @Test fun listToolsHidesKnownCredentialServerDisplayNames() = runBlocking {
        for (secret in secrets) {
            val fixture = Fixture(secret)
            val output = payload(mcpListToolsTool(fixture.store, fixture.manager, fixture.caller)
                .execute(JsonObject(emptyMap())))
            val row = output["tools"]!!.jsonArray.single().jsonObject
            assertHidden(row["server_name"]!!.jsonPrimitive.content, secret)
            assertEquals(fixture.config.id.toString(), row["server_id"]!!.jsonPrimitive.content)
            assertEquals("read_file", row["tool_name"]!!.jsonPrimitive.content)
            fixture.assertUnchanged()
        }
    }

    @Test fun aNeutralHeaderCannotExposeRecognizedCredentialShapes() {
        for (secret in secrets + listOf(
            "sk-proj-" + "aB9_".repeat(8),
            "tp-" + "aB9_".repeat(8),
            "gsk_" + "aB9_".repeat(8),
            "xai-" + "aB9_".repeat(8),
        )) {
            assertHidden(McpHeaderRedactor.redactHeaderValue("X-Mode", secret), secret)
        }
    }

    @Test fun ordinaryOpaqueHeaderValuesAndShortProviderLikeLabelsRemainUnchanged() {
        for (ordinary in listOf(
            "application/json", "tp-demo", "gsk_demo", "xai-server",
            "abcdef0123456789abcdef0123456789",
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN",
        )) assertEquals(ordinary, McpHeaderRedactor.redactHeaderValue("X-Mode", ordinary))
    }

    private fun assertHidden(output: String, secret: String) {
        assertFalse("The raw credential must not be returned", output.contains(secret))
        assertFalse("A recognizable credential prefix must not remain after partial masking", output.contains(secret.take(12)))
    }

    private fun payload(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private class Fixture(private val secret: String) {
        val config = McpServerConfig.StreamableHTTPServer(url = "https://8.8.8.8/mcp",
            commonOptions = McpCommonOptions(name = "Сервер $secret", headers = listOf("X-Mode" to secret),
                tools = listOf(McpTool(name = "read_file"))))
        private val before = Settings(mcpServers = listOf(config))
        private val state = MutableStateFlow(before)
        val store = mockk<SettingsStore> { every { settingsFlow } returns state }
        val manager = mockk<McpManager> {
            every { syncingStatus } returns MutableStateFlow(mapOf(config.id to McpStatus.Connected))
        }
        val caller = McpControlCaller({ setOf(config.id) }, {}, {})

        suspend fun assertUnchanged() {
            assertEquals(before, state.value)
            assertEquals("Сервер $secret", state.value.mcpServers.single().commonOptions.name)
            assertEquals(secret, state.value.mcpServers.single().commonOptions.headers.single().second)
            coVerify(exactly = 0) { store.update(any()) }
        }
    }

    companion object {
        private val secrets = listOf(
            "ghp_" + "aB3".repeat(8),
            "AKIAIOSFODNN7EXAMPLE",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno",
        )
    }
}


class McpKnownHeaderCredentialAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun vault() = McpControlSecretStore(temporary.newFolder()) { key }

    @Test fun addingRawKnownCredentialsInANeutralHeaderIsRejectedBeforeAnyMutation() = runBlocking {
        for (secret in secrets) {
            val fixture = Fixture(emptyList())
            val result = payload(mcpAddTool(fixture.store, fixture.manager, fixture.caller, vault())
                .execute(args(null, headers = listOf("X-Mode" to secret))))
            assertError(result, "plaintext_secret_forbidden")
            assertTrue(fixture.state.value.mcpServers.isEmpty())
            fixture.assertNoMutation()
        }
    }

    @Test fun aNewRawNeutralCredentialCannotBeIntroducedByUpdateOnEitherOrigin() = runBlocking {
        for (secret in secrets) for (url in listOf(OLD_URL, NEW_URL)) {
            val config = server(listOf("Accept" to "application/json"))
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
                .execute(args(config, url, listOf("X-Mode" to secret))))
            assertError(result, "plaintext_secret_forbidden")
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun changingOriginDropsOldKnownNeutralCredentialsEvenWhenExplicitlyRepeated() = runBlocking {
        for (secret in secrets) for (repeatHeaders in listOf(false, true)) {
            val config = server(listOf("X-Mode" to secret, "Accept" to "application/json"))
                .copy(commonOptions = McpCommonOptions(name = "Remote123", enable = false,
                    headers = listOf("X-Mode" to secret, "Accept" to "application/json"),
                    oauth = McpOAuthState(enabled = true, accessToken = "old-access")))
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(
                args(config, NEW_URL, if (repeatHeaders) config.commonOptions.headers else null)))
            assertFalse(result.toString(), result.containsKey("error"))
            val saved = fixture.state.value.mcpServers.single()
            assertEquals(NEW_URL, (saved as McpServerConfig.StreamableHTTPServer).url)
            assertEquals(listOf("Accept" to "application/json"), saved.commonOptions.headers)
            assertNull(saved.commonOptions.oauth)
        }
    }

    @Test fun maskedReplacementOfAnOldRawNeutralSecretRequiresManualSettings() = runBlocking {
        for (secret in secrets) {
            val config = server(listOf("X-Mode" to secret))
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
                .execute(args(config, headers = listOf("X-Mode" to "••••"))))
            assertError(result, "manual_secret_update_required")
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun clearingHeadersCannotDeleteAnOldRawNeutralSecretOnTheSameOrigin() = runBlocking {
        for (secret in secrets) {
            val config = server(listOf("X-Mode" to secret))
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
                .execute(args(config, headers = emptyList())))
            assertError(result, "manual_secret_update_required")
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun omittedHeadersDoNotSilentlyDiscardAnUnchangedLegacyNeutralSecret() = runBlocking {
        val config = server(listOf("X-Mode" to secrets.first()))
        val fixture = Fixture(listOf(config))
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(args(config)))
        assertError(result, "manual_secret_update_required")
        assertEquals(config, fixture.state.value.mcpServers.single())
        fixture.assertNoMutation()
    }

    @Test fun redactionPlaceholdersCannotBeAddedAsNeutralHeaderValues() = runBlocking {
        for (placeholder in placeholders) {
            val fixture = Fixture(emptyList())
            val result = payload(mcpAddTool(fixture.store, fixture.manager, fixture.caller, vault())
                .execute(args(null, headers = listOf("X-Mode" to placeholder))))
            assertError(result, "redacted_header_value")
            fixture.assertNoMutation()
        }
    }

    @Test fun redactionPlaceholdersCannotReplaceOrdinaryHeaders() = runBlocking {
        for (placeholder in placeholders) {
            val config = server(listOf("Accept" to "application/json"))
            val fixture = Fixture(listOf(config))
            val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
                .execute(args(config, headers = listOf("X-Mode" to placeholder))))
            assertError(result, "redacted_header_value")
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun trustedPersistenceProtectsNeutralKnownCredentialsWithTheExactOwnerAndOrigin() {
        for (secret in secrets) {
            val config = server(listOf("X-Mode" to secret))
            val vault = vault()
            val saved = secureMcpConfigForPersistence(config, vault)
            val reference = saved.commonOptions.headers.single().second
            assertTrue(McpControlSecretStore.isReference(reference))
            assertEquals(secret, vault.resolve(reference, config.id, OLD_URL))
            assertTrue(runCatching { vault.resolve(reference, Uuid.random(), OLD_URL) }.isFailure)
            assertTrue(runCatching { vault.resolve(reference, config.id, NEW_URL) }.isFailure)
            assertEquals(secret, config.commonOptions.headers.single().second)
        }
    }

    @Test fun trustedPersistenceAlsoRefusesToSaveAMaskingPlaceholder() {
        for (placeholder in placeholders) {
            val config = server(listOf("X-Mode" to placeholder))
            assertTrue("A display placeholder must not become a vault secret",
                runCatching { secureMcpConfigForPersistence(config, vault()) }.isFailure)
        }
    }

    @Test fun ordinaryOpaqueHexAndShortProviderLikeValuesStillPassPlaintextValidation() {
        val values = listOf("application/json", "tp-demo", "gsk_demo", "xai-server",
            "abcdef0123456789abcdef0123456789", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN")
        for (value in values) {
            val headers = listOf("X-Mode" to value)
            val result = McpControlValidation.validateSecureHeaders(headers)
            assertTrue(value, result is McpControlValidation.Result.Ok)
            assertEquals(headers, (result as McpControlValidation.Result.Ok).value)
        }
    }

    @Test fun neutralBearerAndBasicValuesAreMaskedOnReadRegardlessOfHeaderName() {
        for (value in listOf("Bearer short-fixture", "Basic dXNlcjpwYXNz")) {
            val output = McpHeaderRedactor.redactHeaderValue("X-Mode", value)
            assertFalse("Known authentication payload must not leak", output.contains(value.substringAfter(' ')))
        }
    }

    @Test fun settingsOriginChangeCannotRebindAnInheritedLegacyNeutralSecretToTheNewOrigin() {
        for (secret in secrets) {
            val before = server(listOf("X-Mode" to secret, "Accept" to "application/json"))
            val saved = protectMcpSettingsSecrets(before.copy(url = NEW_URL), emptyMap(), vault(), before)
            assertEquals(listOf("Accept"), saved.commonOptions.headers.map { it.first })
            assertEquals(secret, before.commonOptions.headers.first().second)
        }
    }

    @Test fun anExplicitFreshNeutralSettingsDraftIsProtectedForTheNewOrigin() {
        val before = server(listOf("X-Mode" to secrets.first()))
        val fresh = secrets.last()
        val vault = vault()
        val saved = protectMcpSettingsSecrets(before.copy(url = NEW_URL), mapOf(0 to fresh), vault, before)
        val reference = saved.commonOptions.headers.single().second
        assertTrue(McpControlSecretStore.isReference(reference))
        assertEquals(fresh, vault.resolve(reference, saved.id, NEW_URL))
        assertTrue(runCatching { vault.resolve(reference, saved.id, OLD_URL) }.isFailure)
    }

    private fun server(headers: List<Pair<String, String>>) = McpServerConfig.StreamableHTTPServer(
        url = OLD_URL, commonOptions = McpCommonOptions(name = "Remote123", enable = false, headers = headers))

    private fun args(config: McpServerConfig? = null, url: String = OLD_URL, headers: List<Pair<String, String>>? = null) =
        buildJsonObject {
            config?.let { put("id", it.id.toString()) }
            put("transport", "streamable_http")
            put("name", "Remote123")
            put("url", url)
            put("enabled", false)
            headers?.let { values -> putJsonArray("headers") {
                values.forEach { (name, value) -> addJsonObject { put("name", name); put("value", value) } }
            } }
        }

    private fun payload(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private fun assertError(result: JsonObject, expected: String) {
        assertEquals(result.toString(), expected, result["error"]?.jsonPrimitive?.content)
        val detail = result["detail"]!!.jsonPrimitive.content
        assertTrue("Explain the safe manual recovery in Russian", detail.any { it in 'А'..'я' })
        for (secret in secrets) assertFalse(result.toString().contains(secret))
    }

    private class Fixture(configs: List<McpServerConfig>) {
        val state = MutableStateFlow(Settings(mcpServers = configs))
        val store = mockk<SettingsStore>()
        val manager = mockk<McpManager>(relaxed = true)
        val caller = McpControlCaller({ configs.map { it.id }.toSet() }, {}, {})
        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any()) } coAnswers {
                firstArg<(Settings) -> Settings>()(state.value).also { state.value = it }
            }
            every { manager.syncingStatus } returns MutableStateFlow(configs.associate { it.id to McpStatus.Connected })
        }
        suspend fun assertNoMutation() {
            coVerify(exactly = 0) { store.update(any()) }
            coVerify(exactly = 0) { manager.removeClient(any()) }
            coVerify(exactly = 0) { manager.addClient(any()) }
        }
    }

    companion object {
        private const val OLD_URL = "https://8.8.8.8/mcp"
        private const val NEW_URL = "https://1.1.1.1/mcp"
        private val secrets = listOf(
            "sk-proj-" + "aB9_".repeat(8), "ghp_" + "aB3".repeat(8), "AKIAIOSFODNN7EXAMPLE",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghijklmno",
            "tp-" + "aB9_".repeat(8), "gsk_" + "aB9_".repeat(8), "xai-" + "aB9_".repeat(8),
            "Bearer short-fixture", "Basic dXNlcjpwYXNz",
        )
        private val placeholders = listOf("MASK", "••••", "…", "[redacted-provider-key]", "Bearer [redacted]")
    }
}


class McpListToolsServerNameRedactionAuditTest {
    @Test fun toolListingRedactsServerDisplayNamesAndPreservesDispatchIdentities() = runBlocking {
        val secret = "sk-proj-abcdef_key_Canary_987654321"
        val tool = McpTool(name = "read_document", description = "Читает документ")
        val sensitive = McpServerConfig.StreamableHTTPServer(
            url = "https://8.8.8.8/mcp",
            commonOptions = McpCommonOptions(name = "Рабочий $secret", tools = listOf(tool)),
        )
        val ordinary = McpServerConfig.StreamableHTTPServer(
            url = "https://1.1.1.1/mcp",
            commonOptions = McpCommonOptions(name = "Обычный сервер", tools = listOf(tool)),
        )
        val state = MutableStateFlow(Settings(mcpServers = listOf(sensitive, ordinary)))
        val before = state.value
        val store = mockk<SettingsStore> { every { settingsFlow } returns state }
        val manager = mockk<McpManager>()
        val caller = McpControlCaller({ setOf(sensitive.id, ordinary.id) }, {}, {})

        val parts = mcpListToolsTool(store, manager, caller).execute(JsonObject(emptyMap()))
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        val listed = output["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, listed.size)
        val hidden = listed.single { it["server_id"]!!.jsonPrimitive.content == sensitive.id.toString() }
        val displayName = hidden["server_name"]!!.jsonPrimitive.content
        assertTrue(displayName.startsWith("Рабочий "))
        assertFalse("The server display name must not expose a project API key", displayName.contains(secret))
        assertFalse("Do not leave a partial project-key prefix", displayName.contains("sk-proj-"))
        assertFalse("Do not leave the key's recognizable suffix", displayName.contains("Canary_987654321"))

        for (config in listOf(sensitive, ordinary)) {
            val row = listed.single { it["server_id"]!!.jsonPrimitive.content == config.id.toString() }
            assertEquals(tool.name, row["tool_name"]!!.jsonPrimitive.content)
            assertEquals(buildMcpToolName(config.id, config.commonOptions.name, tool.name),
                row["dispatchable_name"]!!.jsonPrimitive.content)
        }
        val visible = listed.single { it["server_id"]!!.jsonPrimitive.content == ordinary.id.toString() }
        assertEquals(ordinary.commonOptions.name, visible["server_name"]!!.jsonPrimitive.content)
        assertEquals(before, state.value)
        assertEquals("Рабочий $secret", state.value.mcpServers.first().commonOptions.name)
        coVerify(exactly = 0) { store.update(any()) }
    }
}


class McpToolMetadataRedactionAuditTest {
    @Test fun gettingAServerRedactsToolDisplayNamesAndDescriptionsWithoutChangingStoredMetadata() = runBlocking {
        val fixture = Fixture()
        val parts = mcpGetTool(fixture.store, fixture.manager, fixture.caller).execute(
            buildJsonObject { put("id", fixture.config.id.toString()) })
        val rows = payload(parts)["tools"]!!.jsonArray.map { it.jsonObject }

        assertRedacted(rows.first(), "name")
        assertEquals("read_file", rows.last()["name"]!!.jsonPrimitive.content)
        assertEquals("Читает документ", rows.last()["description"]!!.jsonPrimitive.content)
        fixture.assertUnchanged()
    }

    @Test fun listingToolsRedactsDisplayMetadataButPreservesSchemaAndDispatchIdentity() = runBlocking {
        val fixture = Fixture()
        val parts = mcpListToolsTool(fixture.store, fixture.manager, fixture.caller).execute(JsonObject(emptyMap()))
        val rows = payload(parts)["tools"]!!.jsonArray.map { it.jsonObject }

        assertRedacted(rows.first(), "tool_name")
        assertEquals("read_file", rows.last()["tool_name"]!!.jsonPrimitive.content)
        assertEquals("Читает документ", rows.last()["description"]!!.jsonPrimitive.content)
        assertEquals(buildMcpToolName(fixture.config.id, fixture.config.commonOptions.name, SECRET),
            rows.first()["dispatchable_name"]!!.jsonPrimitive.content)
        val schema = rows.first()["input_schema"]!!.jsonObject
        assertTrue("Schema property names are protocol fields and must not be masked",
            schema["properties"]!!.jsonObject.containsKey(SCHEMA_PROPERTY))
        assertEquals(SCHEMA_PROPERTY, schema["required"]!!.jsonArray.single().jsonPrimitive.content)
        fixture.assertUnchanged()
    }

    private fun assertRedacted(row: JsonObject, nameField: String) {
        val name = row[nameField]!!.jsonPrimitive.content
        val description = row["description"]!!.jsonPrimitive.content
        for (value in listOf(name, description)) {
            assertFalse("Metadata must not expose the raw provider key", value.contains(SECRET))
            assertFalse("Metadata must not expose the key prefix", value.contains("sk-proj-"))
            assertFalse("Metadata must not expose the key suffix", value.contains("Canary_987654321"))
        }
        assertTrue("The ordinary Russian description must remain readable", description.startsWith("Описание "))
    }

    private fun payload(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private class Fixture {
        private val tools = listOf(
            McpTool(name = SECRET, description = "Описание $SECRET", inputSchema = InputSchema.Obj(
                properties = buildJsonObject { put(SCHEMA_PROPERTY, buildJsonObject { put("type", "string") }) },
                required = listOf(SCHEMA_PROPERTY),
            )),
            McpTool(name = "read_file", description = "Читает документ"),
        )
        val config = McpServerConfig.StreamableHTTPServer(url = "https://8.8.8.8/mcp",
            commonOptions = McpCommonOptions(name = "Рабочий сервер", tools = tools))
        private val before = Settings(mcpServers = listOf(config))
        private val state = MutableStateFlow(before)
        val store = mockk<SettingsStore> { every { settingsFlow } returns state }
        val manager = mockk<McpManager> {
            every { syncingStatus } returns MutableStateFlow(mapOf(config.id to McpStatus.Connected))
        }
        val caller = McpControlCaller({ setOf(config.id) }, {}, {})

        suspend fun assertUnchanged() {
            assertEquals(before, state.value)
            assertEquals(SECRET, state.value.mcpServers.single().commonOptions.tools.first().name)
            assertEquals("Описание $SECRET", state.value.mcpServers.single().commonOptions.tools.first().description)
            coVerify(exactly = 0) { store.update(any()) }
        }
    }

    companion object {
        private const val SECRET = "sk-proj-abcdef_key_Canary_987654321"
        private const val SCHEMA_PROPERTY = "sk-proj-protocol_property_123456789"
    }
}


class McpProjectionAndAtomicUpdateAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun vault() = McpControlSecretStore(temporary.newFolder()) { key }

    @Test fun exactMaskedNameProjectionPreservesTheTrustedOriginalWithoutReturningIt() = runBlocking {
        val config = server().copy(commonOptions = McpCommonOptions(name = SECRET, enable = false))
        val fixture = Fixture(config)
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
            .execute(request(config, name = redactedMcpText(config.commonOptions.name), enabled = false)))
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(SECRET, fixture.state.value.mcpServers.single().commonOptions.name)
        assertFalse(result.toString().contains(SECRET))
    }

    @Test fun changingAProtectedOldNameRequiresManualSettings() = runBlocking {
        val config = server().copy(commonOptions = McpCommonOptions(name = SECRET, enable = false))
        val fixture = Fixture(config)
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
            .execute(request(config, name = "Replacement123")))
        assertManual(result)
        assertEquals(config, fixture.state.value.mcpServers.single())
        fixture.assertNoMutation()
    }

    @Test fun newApiShapedNamesAreManualOnlyInsteadOfBeingWrittenIntoSettings() = runBlocking {
        for (name in listOf(SECRET, "AKIAIOSFODNN7EXAMPLE")) {
            val config = server()
            val fixture = Fixture(config)
            assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config, name = name))))
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun newMaskedNamePlaceholdersAreNeverSaved() = runBlocking {
        for (name in listOf("MASK", "••••", "[redacted-provider-key]")) {
            val config = server()
            val fixture = Fixture(config)
            assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config, name = name))))
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun exactMaskedUrlProjectionRestoresATrustedLegacyCredentialQuery() = runBlocking {
        val config = server().copy(url = "$OLD_URL?api_key=legacy-query-secret")
        val fixture = Fixture(config)
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
            .execute(request(config, url = redactedMcpUrl(config.url), enabled = false)))
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(config.url, (fixture.state.value.mcpServers.single() as McpServerConfig.StreamableHTTPServer).url)
        assertFalse(result.toString().contains("legacy-query-secret"))
    }

    @Test fun movingAMaskedUrlProjectionToAnotherHostRequiresManualSettings() = runBlocking {
        val config = server().copy(url = "$OLD_URL?api_key=legacy-query-secret")
        val fixture = Fixture(config)
        val movedProjection = redactedMcpUrl(config.url).replace("8.8.8.8", "1.1.1.1")
        assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config, url = movedProjection))))
        assertEquals(config, fixture.state.value.mcpServers.single())
        fixture.assertNoMutation()
    }

    @Test fun newMaskedUrlPlaceholdersCannotBecomeAStoredEndpoint() = runBlocking {
        for (url in listOf("$NEW_URL?[скрыто]", "https://1.1.1.1/%5Bredacted%5D")) {
            val config = server()
            val fixture = Fixture(config)
            assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config, url = url))))
            assertEquals(config, fixture.state.value.mcpServers.single())
            fixture.assertNoMutation()
        }
    }

    @Test fun aNewApiKeyLiteralInTheEndpointRequiresManualSettings() = runBlocking {
        val config = server()
        val fixture = Fixture(config)
        assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault())
            .execute(request(config, url = "$NEW_URL/$SECRET"))))
        assertEquals(config, fixture.state.value.mcpServers.single())
        fixture.assertNoMutation()
    }

    @Test fun addingNewMaskedOrCredentialBearingNamesAndEndpointsIsAlsoManualOnly() = runBlocking {
        for ((name, url) in listOf("MASK" to NEW_URL, "AKIAIOSFODNN7EXAMPLE" to NEW_URL,
            "Added123" to "$NEW_URL/$SECRET", "Added123" to "$NEW_URL?[скрыто]")) {
            val config = server()
            val fixture = Fixture(config)
            val addArgs = JsonObject(request(config, name, url, enabled = false).filterKeys { it != "id" })
            val caller = McpControlCaller({ setOf(config.id) }, {}, {})
            assertManual(payload(mcpAddTool(fixture.store, fixture.manager, caller, vault()).execute(addArgs)))
            assertEquals(listOf(config), fixture.state.value.mcpServers)
            fixture.assertNoMutation()
        }
    }

    @Test fun omittedEnabledAndHeadersUseTheFreshDisabledRowAndDoNotReconnect() = runBlocking {
        val config = server().copy(commonOptions = McpCommonOptions(name = "Remote123", enable = true,
            headers = listOf("Accept" to "old-type")))
        val vault = vault()
        val freshHeaders = listOf("X-Mode" to vault.put("fresh-ui-secret", config.id, OLD_URL))
        val fixture = Fixture(config)
        fixture.onRemove = {
            fixture.state.value = fixture.state.value.copy(mcpServers = listOf(config.copy(
                commonOptions = config.commonOptions.copy(enable = false, headers = freshHeaders))))
        }
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault).execute(request(config)))
        assertFalse(result.toString(), result.containsKey("error"))
        val saved = fixture.state.value.mcpServers.single()
        assertFalse(saved.commonOptions.enable)
        assertEquals(freshHeaders, saved.commonOptions.headers)
        coVerify(exactly = 0) { fixture.manager.addClient(any()) }
    }

    @Test fun omittedEnabledUsesTheFreshEnabledRowAndReconnectsItsFreshHeaders() = runBlocking {
        val config = server()
        val vault = vault()
        val freshHeaders = listOf("X-Mode" to vault.put("fresh-ui-secret", config.id, OLD_URL))
        val fixture = Fixture(config)
        fixture.onBeforeUpdate = {
            fixture.state.value = fixture.state.value.copy(mcpServers = listOf(config.copy(
                commonOptions = config.commonOptions.copy(enable = true, headers = freshHeaders))))
        }
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault).execute(request(config)))
        assertFalse(result.toString(), result.containsKey("error"))
        val saved = fixture.state.value.mcpServers.single()
        assertTrue(saved.commonOptions.enable)
        assertEquals(freshHeaders, saved.commonOptions.headers)
        coVerify(exactly = 1) { fixture.manager.addClient(saved) }
    }

    @Test fun freshForeignOriginHeadersAndOauthAreNotCarriedIntoTheRequestedTarget() = runBlocking {
        val config = server()
        val vault = vault()
        val foreignHeader = vault.put("foreign-ui-header", config.id, NEW_URL)
        val foreignOauth = vault.put("foreign-ui-oauth", config.id, NEW_URL)
        val fixture = Fixture(config)
        fixture.onRemove = {
            fixture.state.value = fixture.state.value.copy(mcpServers = listOf(config.copy(url = NEW_URL,
                commonOptions = config.commonOptions.copy(headers = listOf("X-Mode" to foreignHeader, "Accept" to "fresh-type"),
                    oauth = McpOAuthState(enabled = true, accessToken = foreignOauth)))))
        }
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault).execute(request(config)))
        assertFalse(result.toString(), result.containsKey("error"))
        val saved = fixture.state.value.mcpServers.single() as McpServerConfig.StreamableHTTPServer
        assertEquals(OLD_URL, saved.url)
        assertEquals(listOf("Accept" to "fresh-type"), saved.commonOptions.headers)
        assertNull(saved.commonOptions.oauth)
        coVerify(exactly = 0) { fixture.manager.addClient(any()) }
    }

    @Test fun freshCredentialsAlreadyOwnedByTheRequestedOriginArePreservedWhenHeadersAreOmitted() = runBlocking {
        val config = server()
        val vault = vault()
        val freshHeaders = listOf("X-Mode" to vault.put("new-ui-header", config.id, NEW_URL))
        val freshOauth = McpOAuthState(enabled = true, accessToken = vault.put("new-ui-oauth", config.id, NEW_URL))
        val fixture = Fixture(config)
        fixture.onRemove = {
            fixture.state.value = fixture.state.value.copy(mcpServers = listOf(config.copy(url = NEW_URL,
                commonOptions = config.commonOptions.copy(headers = freshHeaders, oauth = freshOauth))))
        }
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault).execute(request(config, url = NEW_URL)))
        assertFalse(result.toString(), result.containsKey("error"))
        val saved = fixture.state.value.mcpServers.single()
        assertEquals(freshHeaders, saved.commonOptions.headers)
        assertEquals(freshOauth, saved.commonOptions.oauth)
    }

    @Test fun aConcurrentLegacyNeutralSecretCannotBeErasedByAnExplicitHeaderReplacement() = runBlocking {
        val config = server()
        val fixture = Fixture(config)
        val fresh = config.copy(commonOptions = config.commonOptions.copy(headers = listOf("X-Mode" to SECRET)))
        fixture.onRemove = { fixture.state.value = fixture.state.value.copy(mcpServers = listOf(fresh)) }
        val args = JsonObject(request(config) + ("headers" to kotlinx.serialization.json.JsonArray(emptyList())))
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(args))
        assertEquals(result.toString(), "manual_secret_update_required", result["error"]?.jsonPrimitive?.content)
        assertEquals(fresh, fixture.state.value.mcpServers.single())
        assertFalse(result.toString().contains(SECRET))
        coVerify(exactly = 0) { fixture.manager.addClient(any()) }
    }

    @Test fun aConcurrentProtectedNameCannotBeOverwrittenByTheEarlierPlainProjection() = runBlocking {
        val config = server()
        val fixture = Fixture(config)
        val fresh = config.copy(commonOptions = config.commonOptions.copy(name = SECRET))
        fixture.onRemove = { fixture.state.value = fixture.state.value.copy(mcpServers = listOf(fresh)) }
        assertManual(payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config))))
        assertEquals(fresh, fixture.state.value.mcpServers.single())
        coVerify(exactly = 0) { fixture.manager.addClient(any()) }
    }

    @Test fun aConcurrentProtectedUrlCannotBeOverwrittenByTheEarlierPlainEndpoint() = runBlocking {
        val config = server()
        val fixture = Fixture(config)
        val fresh = config.copy(url = "$OLD_URL?token=fresh-query-secret")
        fixture.onRemove = { fixture.state.value = fixture.state.value.copy(mcpServers = listOf(fresh)) }
        val result = payload(mcpUpdateTool(fixture.store, fixture.manager, vault()).execute(request(config)))
        assertManual(result)
        assertEquals(fresh, fixture.state.value.mcpServers.single())
        assertFalse(result.toString().contains("fresh-query-secret"))
        coVerify(exactly = 0) { fixture.manager.addClient(any()) }
    }

    private fun server() = McpServerConfig.StreamableHTTPServer(url = OLD_URL,
        commonOptions = McpCommonOptions(name = "Remote123", enable = false))

    private fun request(config: McpServerConfig.StreamableHTTPServer, name: String = config.commonOptions.name,
        url: String = config.url, enabled: Boolean? = null) = buildJsonObject {
        put("id", config.id.toString())
        put("name", name)
        put("transport", "streamable_http")
        put("url", url)
        enabled?.let { put("enabled", it) }
    }

    private fun payload(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private fun assertManual(result: JsonObject) {
        assertEquals(result.toString(), "manual_protected_field_update_required", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["detail"]!!.jsonPrimitive.content.any { it in 'А'..'я' })
        assertFalse(result.toString().contains(SECRET))
        assertFalse(result.toString().contains("legacy-query-secret"))
    }

    private class Fixture(config: McpServerConfig) {
        val state = MutableStateFlow(Settings(mcpServers = listOf(config)))
        val store = mockk<SettingsStore>()
        val manager = mockk<McpManager>()
        var onRemove: () -> Unit = {}
        var onBeforeUpdate: () -> Unit = {}
        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any()) } coAnswers {
                onBeforeUpdate()
                firstArg<(Settings) -> Settings>()(state.value).also { state.value = it }
            }
            every { manager.syncingStatus } returns MutableStateFlow(mapOf(config.id to McpStatus.Connected))
            coEvery { manager.removeClient(any()) } coAnswers { onRemove(); Unit }
            coEvery { manager.addClient(any()) } returns Unit
        }
        suspend fun assertNoMutation() {
            coVerify(exactly = 0) { store.update(any()) }
            coVerify(exactly = 0) { manager.removeClient(any()) }
            coVerify(exactly = 0) { manager.addClient(any()) }
        }
    }

    companion object {
        private const val SECRET = "sk-proj-" + "aB9_aB9_aB9_aB9_aB9_aB9_aB9_aB9_"
        private const val OLD_URL = "https://8.8.8.8/mcp"
        private const val NEW_URL = "https://1.1.1.1/mcp"
    }
}
