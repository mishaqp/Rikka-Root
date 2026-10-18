package me.rerere.rikkahub.data.ai.tools.local

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.workflow.tools.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ManagementWorkspaceSchemasPart2Test {
    private val russian = Regex("[А-Яа-яЁё]")
    private fun assertProperties(properties: JsonObject, path: String) {
        for ((name, value) in properties) {
            val schema = value.jsonObject
            val label = "$path.$name"
            val description = schema["description"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Missing Russian description: $label: $description", russian.containsMatchIn(description))
            assertTrue("Missing JSON type: $label", "type" in schema || "anyOf" in schema || "oneOf" in schema ||
                description.contains("Любое значение JSON"))
            (schema["properties"] as? JsonObject)?.let { assertProperties(it, label) }
            (schema["items"] as? JsonObject)?.get("properties")?.let { assertProperties(it.jsonObject, "$label[]") }
        }
    }
    private fun assertTool(tool: Tool) {
        assertTrue("Missing Russian tool description: ${tool.name}", russian.containsMatchIn(tool.description))
        val schema = tool.parameters() as? InputSchema.Obj ?: return
        assertProperties(schema.properties, tool.name)
        schema.required.orEmpty().forEach { assertTrue("Unknown required property: ${tool.name}.$it", it in schema.properties) }
    }
    private fun cronTools() = createCronJobTools(mockk(), mockk(), mockk(),
        Conversation(assistantId = Uuid.random(), messageNodes = emptyList()), { setOf("get_time_info") })

    @Test fun memoryToolAndEveryParameterHaveRussianTypedDescriptions() {
        buildMemoryTools(Json, { AssistantMemory(1, it) }, { id, text -> AssistantMemory(id, text) }, {}).forEach(::assertTool)
    }
    @Test fun conversationToolsHaveRussianTypedDescriptions() {
        createConversationTools(mockk(), Uuid.random()).forEach(::assertTool)
    }
    @Test fun workspaceToolsHaveRussianTypedDescriptions() = runBlocking {
        val repository = mockk<WorkspaceRepository> { coEvery { getById("w") } returns null }
        createWorkspaceTools("w", repository).forEach(::assertTool)
    }
    @Test fun cronToolsAndActionFieldsHaveRussianTypedDescriptions() {
        cronTools().forEach(::assertTool)
    }
    @Test fun workflowToolsHaveRussianTypedDescriptions() {
        listOf(workflowCreateTool(mockk(), { emptyList() }, "owner"), workflowUpdateTool(mockk(), { emptyList() }, "owner"),
            workflowListTool(mockk()), workflowGetTool(mockk()), workflowDeleteTool(mockk(), "owner"),
            workflowSetEnabledTool(mockk(), "owner"), workflowRunTool(mockk(), mockk(), "owner")).forEach(::assertTool)
    }
    @Test fun assistantManagementIncludesNestedParameterDescriptionsWithoutLosingRootMode() {
        val tool = manageAssistantsTool(mockk(), { AssistantManagerCatalog() }, AssistantManagerCaller({ null }, {}))
        assertTool(tool)
        val schema = (tool.parameters() as InputSchema.Obj).properties
        val settings = schema.getValue("settings").jsonObject.getValue("properties").jsonObject
        assertEquals("boolean", settings.getValue("rootMode").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("action"), (tool.parameters() as InputSchema.Obj).required)
    }
    @Test fun appSettingsActionIsDescribedInRussian() {
        assertTool(manageAppSettingsTool(AppSettingsEngine({ Settings() }, { it(Settings()) })))
    }
    @Test fun appSettingsRejectsTypedInvalidGetInsteadOfExpandingReadScope() = runBlocking {
        val tool = manageAppSettingsTool(AppSettingsEngine({ Settings() }, { error("Read must not mutate") }))
        for (args in listOf("""{"action":"get","key":123}""", """{"action":"get","section":true}""")) {
            val output = tool.execute(Json.parseToJsonElement(args))
            val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(args, "error", result["status"]?.jsonPrimitive?.content)
            assertTrue(result.toString(), russian.containsMatchIn(result["message"]?.jsonPrimitive?.content.orEmpty()))
        }
    }
    @Test fun workflowDefinitionExposesActualFieldsAndRequiredInput() {
        val properties = (workflowCreateTool(mockk(), { emptyList() }, "owner").parameters() as InputSchema.Obj).properties
        val definition = properties.getValue("definition").jsonObject
        val fields = definition["properties"] as? JsonObject
        assertNotNull("definition must expose typed fields, not only prose", fields)
        for (name in listOf("name", "enabled", "trigger", "actions", "conditions", "cooldown_seconds", "max_runs_per_day")) {
            assertTrue("Missing definition.$name", name in fields!!)
        }
        assertEquals(setOf("name", "trigger", "actions"), definition["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet())
        assertEquals(JsonPrimitive(true), fields!!.getValue("enabled").jsonObject["default"])
        assertEquals(JsonPrimitive(0), fields.getValue("cooldown_seconds").jsonObject["default"])
    }
    @Test fun cronSchemaAdvertisesActualDefaultsAndBounds() {
        val schedule = (cronTools().single { it.name == "schedule_job" }.parameters() as InputSchema.Obj).properties
        assertEquals(JsonPrimitive("fire_once"), schedule.getValue("catchup").jsonObject["default"])
        assertEquals(JsonPrimitive(1), schedule.getValue("max_runs").jsonObject["minimum"])
        assertEquals(JsonPrimitive(100000), schedule.getValue("max_runs").jsonObject["maximum"])
        val history = (cronTools().single { it.name == "get_job_history" }.parameters() as InputSchema.Obj).properties
        assertEquals(JsonPrimitive(20), history.getValue("limit").jsonObject["default"])
        assertEquals(JsonPrimitive(100), history.getValue("limit").jsonObject["maximum"])
    }
}
