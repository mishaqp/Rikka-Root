package me.rerere.rikkahub.data.ai.tools.contracts

import android.app.Application
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SearchToolMetadataPart2Test {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private fun options(): List<SearchServiceOptions> = SearchServiceOptions.TYPES.keys.map { type ->
        type.constructors.single { ctor -> ctor.parameters.all { it.isOptional } }.callBy(emptyMap())
    } + SearchServiceOptions.CustomJsOptions(scrapeScript = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT)
    private fun tools(option: SearchServiceOptions) = createSearchTools(Settings(searchServices = listOf(option), searchServiceSelected = 0))

    @Test fun allActualSearchProvidersExposeRussianToolAndParameterDescriptions() {
        val errors = mutableListOf<String>()
        for (option in options()) for (tool in tools(option)) {
            val prefix = "${option::class.simpleName}/${tool.name}"
            if (!RegisteredToolSchemaContract.hasRussian(tool.description)) errors += "$prefix: описание инструмента"
            errors += RegisteredToolSchemaContract.parameterLanguageIssues(RegisteredToolSchemaContract.schema(tool)).map { "$prefix: $it" }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun localizationPreservesEverySdkFieldTypeRequiredListEnumAndConstraintWithoutMutatingTheSdk() {
        val keys = mutableSetOf<String>()
        for (option in options()) {
            val service = SearchService.getService(option)
            val originals = mapOf("search_web" to service.parameters(option), "scrape_web" to service.scrapingParameters(option))
            val snapshots = originals.mapValues { (_, schema) -> schema?.let(::serialize) }
            for (tool in tools(option)) {
                val original = snapshots.getValue(tool.name)!!
                val actual = RegisteredToolSchemaContract.schema(tool)
                assertEquals("${option::class.simpleName}/${tool.name}: only descriptions may change",
                    withoutDescriptions(original), withoutDescriptions(actual))
                keys += actual["properties"]!!.jsonObject.keys
            }
            assertEquals(snapshots["search_web"], service.parameters(option)?.let(::serialize))
            assertEquals(snapshots["scrape_web"], service.scrapingParameters(option)?.let(::serialize))
        }
        assertEquals(setOf("query", "url", "urls", "topic", "type", "startPublishedDate", "endPublishedDate",
            "includeDomains", "excludeDomains", "maxAgeHours", "sources", "categories", "onlyMainContent"), keys)
    }

    @Test fun providerSpecificMeaningsAndRealBoundsStayVisibleInTheLocalizedSchemas() {
        fun property(option: SearchServiceOptions, tool: String, key: String): JsonObject =
            tools(option).single { it.name == tool }.parameters().let { it as InputSchema.Obj }.properties.getValue(key).jsonObject
        val grok = property(SearchServiceOptions.GrokOptions(), "search_web", "query")
        assertTrue(grok["description"]!!.jsonPrimitive.content.contains("вопрос", ignoreCase = true))
        val duck = property(SearchServiceOptions.DuckDuckGoOptions(), "scrape_web", "url")
        assertTrue(duck["description"]!!.jsonPrimitive.content.contains("Абсолют", ignoreCase = true))
        assertTrue(duck["description"]!!.jsonPrimitive.content.contains("HTTP"))
        val exa = SearchServiceOptions.ExaOptions()
        for (tool in listOf("search_web", "scrape_web")) {
            val age = property(exa, tool, "maxAgeHours")
            assertEquals(JsonPrimitive("integer"), age["type"])
            assertEquals(JsonPrimitive(-1), age["minimum"])
            assertEquals(JsonPrimitive(720), age["maximum"])
            assertTrue(age["description"]!!.jsonPrimitive.content.contains("час"))
        }
        assertEquals(Json.parseToJsonElement("""["fast","auto","deep"]"""), property(exa, "search_web", "type")["enum"])
        assertEquals(Json.parseToJsonElement("""["general","news","finance"]"""),
            property(SearchServiceOptions.TavilyOptions(), "search_web", "topic")["enum"])
        val custom = SearchServiceOptions.CustomJsOptions(scrapeScript = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT)
        val urls = property(custom, "scrape_web", "urls")
        assertEquals(JsonPrimitive("array"), urls["type"])
        assertEquals(JsonPrimitive("string"), urls["items"]!!.jsonObject["type"])
        assertTrue(urls["description"]!!.jsonPrimitive.content.contains("массив", ignoreCase = true))
        val firecrawl = SearchServiceOptions.FirecrawlOptions()
        val main = property(firecrawl, "scrape_web", "onlyMainContent")
        assertEquals(JsonPrimitive("boolean"), main["type"])
        assertTrue(main["description"]!!.jsonPrimitive.content.contains("true"))
        assertTrue(property(firecrawl, "search_web", "categories")["description"]!!.jsonPrimitive.content.contains("research"))
    }

    private fun serialize(schema: InputSchema): JsonObject = json.encodeToJsonElement(schema).jsonObject
    private fun withoutDescriptions(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "description" }.mapValues { (_, child) -> withoutDescriptions(child) })
        is JsonArray -> JsonArray(value.map(::withoutDescriptions))
        else -> value
    }
}
