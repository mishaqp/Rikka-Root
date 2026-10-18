package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.mockk
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.parametersOrEmpty
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.*
import org.junit.Test

/** Actual creators: inspecting a contract must not open Android UI or read personal data. */
class PersonalDeviceMediaContractPart2Test {
    private val context = mockk<Context>(relaxed = true)
    private val files = mockk<FilesManager>(relaxed = true)
    private val access = mockk<LocalFileAccess>(relaxed = true)
    private val events = AppEventBus()
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val speech = mockk<TTSManager>(relaxed = true)

    private fun personal() = listOf(
        listContactsTool(context), searchContactsTool(context), callLogTool(context),
        listSmsInboxTool(context), searchSmsTool(context), smsSendTool(context),
        cameraPhotoTool(context, files), micRecorderTool(context, files), speechToTextTool(context),
        fingerprintTool(context), locationTool(context), buildCalendarQueryTool(context),
        buildCalendarCreateTool(context), buildScreenTimeTool(context, events),
    )
    private fun information() = listOf(
        batteryTool(context), audioInfoTool(context), telephonyInfoTool(context), wifiInfoTool(context),
        listSensorsTool(context), readSensorTool(context), storageTool(), buildTimeInfoTool(),
        listStorageVolumesTool(context), listGrantedDirectoriesTool(context),
        grantDirectoryAccessTool(context), revokeDirectoryAccessTool(context),
    )
    private fun mediaAndUi() = listOf(
        playMediaTool(context, access), pauseMediaTool(context), resumeMediaTool(context, access),
        stopMediaTool(context), seekMediaTool(context), getMediaStatusTool(), mediaScannerTool(context, access),
        toastTool(context), notificationTool(context), shareTool(context),
        buildTextToSpeechTool(events, speech, settings), buildAskUserTool(), buildChartDisplayTool(), buildJavascriptTool(),
    )
    private fun crypto() = keystoreTools(context, mockk(relaxed = true))
    private fun catalog() = personal() + information() + mediaAndUi() + crypto()

    @Test fun personalToolsExplainTheirPurposeAndEachParameterInRussian() = russianContracts(personal())
    @Test fun informationToolsExplainTheirPurposeAndEachParameterInRussian() = russianContracts(information())
    @Test fun mediaAndUiToolsExplainTheirPurposeAndEachParameterInRussian() = russianContracts(mediaAndUi())
    @Test fun keystoreToolsExplainTheirPurposeAndEachParameterInRussian() = russianContracts(crypto())

    @Test fun personalSchemasPublishTheRealDefaultsAndValidatedBounds() {
        val tools = catalog().associateBy { it.name }
        bounds(tools.getValue("list_contacts"), "limit", 1, 500, 50)
        bounds(tools.getValue("search_contacts"), "limit", 1, 100, 20)
        for (name in listOf("list_call_log", "list_sms_inbox", "search_sms")) {
            bounds(tools.getValue(name), "limit", 1, 200, 20)
            assertEquals(JsonPrimitive(0), property(tools.getValue(name), "since_ms")["minimum"])
        }
        bounds(tools.getValue("record_audio"), "duration_ms", 1000, 300000, 10000)
        bounds(tools.getValue("speech_to_text"), "timeout_ms", 1000, 60000, 30000)
        bounds(tools.getValue("get_location"), "timeout_ms", 1000, 60000, 30000)
        assertEquals(JsonPrimitive("balanced"), property(tools.getValue("get_location"), "accuracy")["default"])
        assertEquals(buildJsonArray { add("incoming"); add("outgoing"); add("missed") },
            property(tools.getValue("list_call_log"), "type")["enum"])
        for (name in listOf("search_contacts", "search_sms")) {
            assertEquals(JsonPrimitive(1), property(tools.getValue(name), "query")["minLength"])
            assertEquals(JsonPrimitive(256), property(tools.getValue(name), "query")["maxLength"])
        }
    }

    @Test fun sensorMediaAndCalendarSchemasPublishActualLimitsWithoutInventingDynamicDefaults() {
        val tools = catalog().associateBy { it.name }
        bounds(tools.getValue("read_sensor"), "duration_ms", 1, 5000, 200)
        bounds(tools.getValue("calendar_query"), "limit", 1, 100, 20)
        assertEquals(JsonPrimitive("today"), property(tools.getValue("calendar_query"), "range")["default"])
        assertEquals(JsonPrimitive(0), property(tools.getValue("seek_media"), "position_ms")["minimum"])
        val paths = property(tools.getValue("scan_media"), "paths")
        assertEquals(JsonPrimitive(1), paths["minItems"])
        assertEquals(JsonPrimitive(64), paths["maxItems"])
    }

    @Test fun keystoreSchemasPublishRealKeyChoicesWithoutAcceptingSecretPlaintext() {
        val tools = crypto().associateBy { it.name }
        assertEquals(7, tools.size)
        val generate = tools.getValue("keystore_generate_key")
        assertEquals(buildJsonArray { add("rsa_2048"); add("aes_256_gcm") }, property(generate, "type")["enum"])
        assertEquals(JsonPrimitive(1), property(generate, "alias")["minLength"])
        assertEquals(JsonPrimitive(64), property(generate, "alias")["maxLength"])
        val purposes = property(generate, "purposes")
        assertEquals(JsonPrimitive(1), purposes["minItems"])
        assertEquals(JsonPrimitive(2), purposes["maxItems"])
        assertEquals(JsonPrimitive(true), purposes["uniqueItems"])
        val encrypt = tools.getValue("keystore_encrypt")
        assertFalse((encrypt.parametersOrEmpty() as InputSchema.Obj).properties.containsKey("plaintext"))
        assertFalse((encrypt.parametersOrEmpty() as InputSchema.Obj).properties.containsKey("plaintext_b64"))
        assertEquals(JsonPrimitive("utf8"), property(encrypt, "input_encoding")["default"])
        assertEquals(buildJsonArray { add("utf8"); add("base64") }, property(encrypt, "input_encoding")["enum"])
    }

    @Test fun everyCatalogNameIsUniqueAndEveryRequiredFieldIsDeclared() {
        val tools = catalog()
        assertEquals(47, tools.size)
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        for (tool in tools) {
            assertTrue(tool.name, Regex("^[a-zA-Z0-9_-]{1,64}$").matches(tool.name))
            val schema = tool.parametersOrEmpty() as InputSchema.Obj
            assertTrue(tool.name, schema.required.orEmpty().all { it in schema.properties })
        }
    }

    private fun property(tool: Tool, name: String): JsonObject =
        (tool.parametersOrEmpty() as InputSchema.Obj).properties.getValue(name).jsonObject
    private fun bounds(tool: Tool, name: String, min: Int, max: Int, default: Int) {
        val field = property(tool, name)
        assertEquals("${tool.name}.$name.minimum", JsonPrimitive(min), field["minimum"])
        assertEquals("${tool.name}.$name.maximum", JsonPrimitive(max), field["maximum"])
        assertEquals("${tool.name}.$name.default", JsonPrimitive(default), field["default"])
    }
    private fun russianContracts(tools: List<Tool>) {
        val failures = mutableListOf<String>()
        val russian = Regex("[А-Яа-яЁё]")
        fun inspect(path: String, properties: JsonObject) {
            for ((name, raw) in properties) {
                val field = raw.jsonObject
                val type = field["type"]
                if (type == null && field["anyOf"] == null && field["oneOf"] == null) failures += "$path.$name.type"
                if (!russian.containsMatchIn(field["description"]?.jsonPrimitive?.contentOrNull.orEmpty())) failures += "$path.$name.description"
                field["properties"]?.jsonObject?.let { inspect("$path.$name", it) }
                field["items"]?.jsonObject?.get("properties")?.jsonObject?.let { inspect("$path.$name[]", it) }
            }
        }
        for (tool in tools) {
            if (!russian.containsMatchIn(tool.description.take(160))) failures += "${tool.name}.description"
            inspect(tool.name, (tool.parametersOrEmpty() as InputSchema.Obj).properties)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
