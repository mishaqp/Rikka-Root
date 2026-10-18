package me.rerere.rikkahub.data.ai.tools.contracts

import android.app.Application
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.RootMode
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiRegistry
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RegisteredToolContractsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun actualAggregateCoversEveryLocalAndContextualCreatorInNormalRootAndFallbackViews() = fixture { fixture ->
        assertEquals("Every declared option must have a real UI registration", fixture.options.toSet(),
            LocalToolUiRegistry.entries.map { it.option }.toSet())
        assertEquals(SearchServiceOptions::class.sealedSubclasses.toSet(), fixture.searchOptions.map { it::class }.toSet())
        val views = fixture.views()
        writeInventory(fixture, views + fixture.searchViews())
        for (view in views) {
            val sources = view.sources
            // The only intentionally empty LocalTools branches belong to ChatToolFactory context.
            val contextOptions = setOf(LocalToolOption.CronJobs::class.simpleName, LocalToolOption.SubAgents::class.simpleName)
            sources.filterKeys { it !in contextOptions }.forEach { (source, tools) ->
                assertTrue("${view.label}/$source silently omitted its actual creator", tools.isNotEmpty())
            }
            assertEquals("${view.label}: aggregate lost or invented a creator's tools",
                sources.values.flatten().map { it.name }.toSet(), view.tools.map { it.name }.toSet())
            assertTrue("${view.label}: " + RegisteredToolSchemaContract.names(view.tools).joinToString("\n"),
                RegisteredToolSchemaContract.names(view.tools).isEmpty())
            assertEquals(BrowserToolDefaults.ALL_TOOLS.toSet(), sources["Browser"]!!.map { it.name }.toSet())
        }
        val normal = views.single { it.label == "normal" }
        val root = views.single { it.label == "root" }
        val fallback = views.single { it.label == "root-unavailable" }
        assertEquals(normal.tools.map { it.name }.toSet(), fallback.tools.map { it.name }.toSet())
        val replaced = RootMode.REPLACED.flatMap { option -> normal.sources[option::class.simpleName].orEmpty() }.map { it.name }.toSet()
        assertEquals(normal.tools.map { it.name }.toSet() - replaced + "root_exec", root.tools.map { it.name }.toSet())
        assertTrue(root.tools.single { it.name == "root_exec" }.systemPrompt(fixture.model, emptyList()).contains(RootMode.GUIDE))
        assertEquals(SearchServiceOptions.TYPES.size + 1, fixture.searchViews().size)
        fixture.searchViews().forEach { assertTrue(it.label, it.tools.isNotEmpty()) }
    }

    @Test fun everyActualRegisteredSchemaHasValidTypesAndResolvableRequiredProperties() = fixture { fixture ->
        val failures = allViews(fixture).flatMap { view -> view.tools.flatMap { tool ->
            RegisteredToolSchemaContract.schemaIssues(RegisteredToolSchemaContract.schema(tool))
                .map { "${view.label}/${view.origin(tool)}/${tool.name}: $it" }
        } }
        assertNoIssues(failures)
    }

    @Test fun everyBuiltInRegisteredToolHasANonemptyRussianDescription() = fixture { fixture ->
        val failures = allViews(fixture).flatMap { view -> view.tools.filterNot(view::isExternal).mapNotNull { tool ->
            if (RegisteredToolSchemaContract.hasRussian(tool.description)) null
            else "${view.label}/${view.origin(tool)}/${tool.name}: нужно русское описание инструмента"
        } }
        assertNoIssues(failures)
    }

    @Test fun everyBuiltInParameterIncludingNestedPropertiesHasARussianDescription() = fixture { fixture ->
        val failures = allViews(fixture).flatMap { view -> view.tools.filterNot(view::isExternal).flatMap { tool ->
            RegisteredToolSchemaContract.parameterLanguageIssues(RegisteredToolSchemaContract.schema(tool))
                .map { "${view.label}/${view.origin(tool)}/${tool.name}: $it" }
        } }
        assertNoIssues(failures)
    }

    @Test fun actualClaudeRequestConversionPreservesEveryRegisteredNameDescriptionAndSchema() = fixture { fixture ->
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest", ProviderSetting.Claude::class.java,
            List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        for (view in allViews(fixture)) {
            val body = method.invoke(ClaudeProvider(fixture.client), ProviderSetting.Claude(), messages,
                TextGenerationParams(model = fixture.model, tools = view.tools), false) as JsonObject
            val declarations = body["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(view.label, view.tools.map { it.name }, declarations.map { it["name"]!!.jsonPrimitive.content })
            view.tools.zip(declarations).forEach { (tool, declaration) ->
                assertEquals("${view.label}/${tool.name}: description", tool.description, declaration["description"]!!.jsonPrimitive.content)
                assertEquals("${view.label}/${tool.name}: input_schema", RegisteredToolSchemaContract.schema(tool), declaration["input_schema"])
            }
        }
    }

    @Test fun actualGeminiRequestConversionPreservesAllPropertyNamesTypesDescriptionsAndRequiredReferences() = fixture { fixture ->
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java,
            TextGenerationParams::class.java).apply { isAccessible = true }
        val failures = mutableListOf<String>()
        for (view in allViews(fixture)) {
            val body = method.invoke(GoogleProvider(fixture.client), messages,
                TextGenerationParams(model = fixture.model, tools = view.tools)) as JsonObject
            val declarations = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject }
            assertEquals(view.label, view.tools.map { it.name }, declarations.map { it["name"]!!.jsonPrimitive.content })
            view.tools.zip(declarations).forEach { (tool, declaration) ->
                assertEquals("${view.label}/${tool.name}: description", tool.description, declaration["description"]!!.jsonPrimitive.content)
                val converted = declaration["parameters"]!!.jsonObject
                // Gemini may remove unsupported keywords; it must retain actual input fields,
                // including fields literally named format/enum/const and their nested required refs.
                assertEquals("${view.label}/${tool.name}: property contract",
                    RegisteredToolSchemaContract.propertyProjection(RegisteredToolSchemaContract.schema(tool)),
                    RegisteredToolSchemaContract.propertyProjection(converted))
                failures += RegisteredToolSchemaContract.schemaIssues(converted).map { "${view.label}/${tool.name}: $it" }
            }
        }
        assertNoIssues(failures)
    }

    @Test fun actualRemoteMcpAdapterKeepsExternalMetadataAndExactDispatchWithoutImposingRussianOnRemoteServers() = fixture { fixture ->
        val view = fixture.views().first()
        val adapters = view.sources["external/MCP"]!!
        assertEquals(fixture.remoteTools.size, adapters.size)
        fixture.remoteTools.zip(adapters).forEach { (remote, adapter) ->
            assertEquals(remote.description, adapter.description)
            assertFalse(RegisteredToolSchemaContract.hasRussian(adapter.description))
            assertEquals(remote.inputSchema, adapter.parameters())
            val args = buildJsonObject { put("format", "2026-10-18"); put("enum", "ordinary") }
            assertEquals("remote-contract-result", (adapter.execute(args).single() as UIMessagePart.Text).text)
            coVerify(exactly = 1) { fixture.manager.callTool(fixture.remoteId, remote.name, args) }
        }
        assertTrue(RegisteredToolSchemaContract.names(adapters).isEmpty())
        val empty = adapters.single { it.parameters() == null }
        assertEquals(Json.parseToJsonElement("""{"type":"object","properties":{}}"""), RegisteredToolSchemaContract.schema(empty))
    }

    @Test fun schemaValidatorDetectsMissingTypesDanglingRequiredAndMalformedArraysWithoutRejectingCompositions() {
        fun schema(text: String) = Json.parseToJsonElement(text).jsonObject
        val malformed = schema("""{"type":"object","properties":{"bare":{},"array":{"type":"array"},"wrong":{"type":"text"}},"required":["missing","missing"]}""")
        val problems = RegisteredToolSchemaContract.schemaIssues(malformed)
        assertTrue(problems.joinToString("\n"), problems.any { "bare" in it && "type" in it })
        assertTrue(problems.joinToString("\n"), problems.any { "items" in it })
        assertTrue(problems.joinToString("\n"), problems.any { "required" in it && "missing" in it })
        assertTrue(problems.joinToString("\n"), problems.any { "wrong" in it && "type" in it })
        val valid = schema("""{"type":"object","properties":{"value":{"description":"Значение","anyOf":[{"type":"string"},{"type":"number"},{"type":"boolean"},{"type":"null"}]}},"required":["value"]}""")
        assertTrue(RegisteredToolSchemaContract.schemaIssues(valid).isEmpty())
        assertTrue(RegisteredToolSchemaContract.parameterLanguageIssues(valid).isEmpty())
        assertTrue(RegisteredToolSchemaContract.parameterLanguageIssues(malformed).isNotEmpty())
    }

    private fun writeInventory(fixture: RegisteredToolContractFixture, views: List<ToolView>) {
        val inventory = buildJsonObject {
            put("local_option_count", fixture.options.size)
            put("search_provider_count", fixture.searchOptions.size)
            put("views", buildJsonArray { views.forEach { view -> add(buildJsonObject {
                put("view", view.label)
                put("tool_count", view.tools.size)
                put("sources", buildJsonObject { view.sources.forEach { (source, tools) -> put(source, tools.size) } })
                put("tools", buildJsonArray { view.tools.forEach { tool -> add(buildJsonObject {
                    put("name", tool.name)
                    put("source", view.origin(tool))
                    put("external", view.isExternal(tool))
                    put("description", tool.description)
                    put("schema", RegisteredToolSchemaContract.schema(tool))
                }) } })
            }) } })
        }
        val report = java.io.File("build/reports/tool-contracts-inventory.json")
        report.parentFile.mkdirs()
        report.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), inventory))
    }

    private suspend fun allViews(fixture: RegisteredToolContractFixture) = fixture.views() + fixture.searchViews()
    private fun assertNoIssues(issues: List<String>) {
        assertTrue("${issues.size} нарушений контракта:\n${issues.joinToString("\n")}", issues.isEmpty())
    }
    private fun fixture(check: suspend (RegisteredToolContractFixture) -> Unit) =
        RegisteredToolContractFixture(folder.newFolder()).use { runBlocking { check(it) } }
    private val messages = listOf(UIMessage.user("Проверь схему инструмента без выполнения"))
}
