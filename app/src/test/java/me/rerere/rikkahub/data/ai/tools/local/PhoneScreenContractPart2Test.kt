package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.root.RootProcessResult
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal fun part2PhoneCreators(context: Context): List<Tool> = listOf(
    torchTool(context), vibrateTool(context), getBrightnessTool(context), setBrightnessTool(context),
    getVolumeTool(context), setVolumeTool(context), setWallpaperTool(context), nfcStatusTool(context),
    nfcReadTagTool(context), nfcWriteTagTool(context), buildClipboardTool(context),
) + systemIntentTools(context) + appLauncherTools(context) + alarmClockTools(context)

internal fun part2ScreenCreators(context: Context, service: RootScreenService): List<Tool> = listOf(
    tapTool(service), longPressTool(service), swipeTool(service), readWindowTreeTool(service),
    findNodeTool(service), clickNodeTool(service), setTextTool(service), scrollTool(service),
    globalActionTool(service), takeScreenshotTool(context, service), wakeScreenTool(context, service),
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PhoneScreenContractPart2Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun screens(): List<Tool> = part2ScreenCreators(context,
        RootScreenService(context, { true }) { _, _, _, _ -> RootProcessResult(error = "unused_schema_fixture") })
    private fun extra(): List<Tool> = qrCodeTools(context, LocalFileAccess(context)) + httpRequestTool(OkHttpClient())
    private fun all(): List<Tool> = part2PhoneCreators(context) + screens() + extra()
    private fun tool(name: String): Tool = all().single { it.name == name }
    private fun schema(name: String): InputSchema.Obj = tool(name).parameters() as InputSchema.Obj
    private fun field(name: String, key: String): JsonObject = schema(name).properties.getValue(key).jsonObject

    @Test fun `inventory covers all 39 PHONE SCREEN alarm QR and HTTP creators`() {
        val expected = setOf("set_torch", "vibrate", "get_brightness", "set_brightness", "get_volume", "set_volume",
            "set_wallpaper", "nfc_status", "nfc_read_tag", "nfc_write_tag", "clipboard_tool",
            "create_calendar_event", "create_contact", "send_email_intent", "send_sms_intent", "open_wifi_settings",
            "show_location_on_map", "launch_app", "open_url", "list_installed_apps", "list_app_activities", "launch_activity",
            "set_alarm", "set_timer", "show_alarms", "tap", "long_press", "swipe", "read_window_tree", "find_node",
            "click_node", "set_text", "scroll", "global_action", "take_screenshot", "wake_screen", "qr_generate", "qr_scan", "http_request")
        assertEquals(expected, all().map { it.name }.toSet())
        assertEquals(39, all().size)
        all().forEach { assertTrue(it.name, it.parameters() is InputSchema.Obj) }
    }

    private fun russianDescriptions(tools: List<Tool>) {
        val missing = tools.filterNot { Regex("[А-Яа-яЁё]{3}").containsMatchIn(it.description) }.map { it.name }
        assertTrue("The actual tool descriptions need Russian purpose, limits and usage guidance: $missing", missing.isEmpty())
    }
    private fun russianFields(tools: List<Tool>) {
        val missing = mutableListOf<String>()
        fun visit(path: String, fields: JsonObject) {
            for ((name, value) in fields) {
                val property = value.jsonObject
                val description = (property["description"] as? JsonPrimitive)?.content.orEmpty()
                if (!Regex("[А-Яа-яЁё]{3}").containsMatchIn(description)) missing += "$path.$name"
                (property["properties"] as? JsonObject)?.let { visit("$path.$name", it) }
                ((property["items"] as? JsonObject)?.get("properties") as? JsonObject)?.let { visit("$path.$name[]", it) }
            }
        }
        tools.forEach { visit(it.name, (it.parameters() as InputSchema.Obj).properties) }
        assertTrue("Every actual parameter, including NFC record properties, needs a Russian description: $missing", missing.isEmpty())
    }
    @Test fun `PHONE descriptions are Russian at actual creator boundary`() = russianDescriptions(part2PhoneCreators(context))
    @Test fun `SCREEN descriptions are Russian at actual creator boundary`() = russianDescriptions(screens())
    @Test fun `PHONE schemas describe every parameter in Russian`() = russianFields(part2PhoneCreators(context))
    @Test fun `SCREEN schemas describe every parameter in Russian`() = russianFields(screens())
    @Test fun `new QR and HTTP schemas describe every parameter in Russian`() = russianFields(extra())

    private fun number(name: String, key: String, minimum: Int, maximum: Int, default: Int? = null) {
        val property = field(name, key)
        assertEquals("$name.$key.minimum", JsonPrimitive(minimum), property["minimum"])
        assertEquals("$name.$key.maximum", JsonPrimitive(maximum), property["maximum"])
        if (default != null) assertEquals("$name.$key.default", JsonPrimitive(default), property["default"])
    }
    @Test fun `alarm and timer schemas expose the exact execute bounds and message limit`() {
        number("set_alarm", "hour", 0, 23)
        number("set_alarm", "minute", 0, 59)
        number("set_timer", "seconds", 1, 86400)
        for (name in listOf("set_alarm", "set_timer")) {
            assertEquals("$name.message.maxLength", JsonPrimitive(4096), field(name, "message")["maxLength"])
            assertEquals(JsonPrimitive(true), field(name, "skip_ui")["default"])
        }
        assertEquals(setOf("hour", "minute"), schema("set_alarm").required.orEmpty().toSet())
        assertEquals(listOf("seconds"), schema("set_timer").required)
        assertTrue(schema("show_alarms").properties.isEmpty())
    }
    @Test fun `screen schemas expose durations tree limits and effective defaults`() {
        number("long_press", "duration_ms", 100, 5000, 600)
        number("swipe", "duration_ms", 50, 5000, 300)
        number("wake_screen", "hold_ms", 500, 30000, 3000)
        number("read_window_tree", "max_nodes", 1, 2000, 500)
        assertEquals(JsonPrimitive(false), field("read_window_tree", "verbose")["default"])
        for (name in listOf("click_node", "set_text")) {
            assertEquals(JsonPrimitive(0), field(name, "nth")["minimum"])
            assertEquals(JsonPrimitive(0), field(name, "nth")["default"])
        }
        assertEquals(JsonPrimitive(0), field("take_screenshot", "display_id")["minimum"])
        assertEquals(JsonPrimitive(0), field("take_screenshot", "display_id")["default"])
        assertEquals(JsonPrimitive(false), field("take_screenshot", "save_to_gallery")["default"])
        assertEquals(JsonArray(listOf(JsonPrimitive(0))), field("take_screenshot", "display_id")["enum"])
        for ((name, keys) in mapOf("tap" to listOf("x", "y"), "long_press" to listOf("x", "y"),
            "swipe" to listOf("start_x", "start_y", "end_x", "end_y"), "scroll" to listOf("x", "y"))) {
            keys.forEach { key -> assertEquals("$name.$key.minimum", JsonPrimitive(0), field(name, key)["minimum"]) }
        }
    }
    @Test fun `phone enum and NFC nested record schema agree with execution`() {
        assertEquals(JsonArray(listOf("home", "lock", "both").map(::JsonPrimitive)), field("set_wallpaper", "target")["enum"])
        assertEquals(JsonPrimitive("both"), field("set_wallpaper", "target")["default"])
        assertEquals(JsonPrimitive("media"), field("get_volume", "stream")["default"])
        assertEquals(JsonPrimitive(500), field("vibrate", "duration_ms")["default"])
        assertEquals(JsonPrimitive(false), field("clipboard_tool", "reveal")["default"])
        for (name in listOf("nfc_read_tag", "nfc_write_tag")) number(name, "timeout_seconds", 5, 120, 30)
        val item = field("nfc_write_tag", "records").getValue("items").jsonObject
        assertEquals(setOf("kind", "value"), item["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet())
        val nested = item.getValue("properties").jsonObject
        assertEquals(JsonArray(listOf("text", "uri", "raw").map(::JsonPrimitive)), nested.getValue("kind").jsonObject["enum"])
        assertEquals(JsonPrimitive(0), nested.getValue("tnf").jsonObject["minimum"])
        assertEquals(JsonPrimitive(5), nested.getValue("tnf").jsonObject["maximum"])
        assertEquals(JsonPrimitive("ru"), nested.getValue("language").jsonObject["default"])
        assertEquals(JsonPrimitive(63), nested.getValue("language").jsonObject["maxLength"])
    }
    @Test fun `native listing schemas expose real bounds defaults and text limits`() {
        number("list_installed_apps", "limit", 1, 1000, 200)
        number("list_app_activities", "limit", 1, 500, 100)
        assertEquals(JsonPrimitive(true), field("list_installed_apps", "user_only")["default"])
        for (key in listOf("include_no_launcher", "include_permissions")) {
            assertEquals(JsonPrimitive(false), field("list_installed_apps", key)["default"])
        }
        assertEquals(JsonPrimitive(false), field("list_app_activities", "exported_only")["default"])
        assertEquals(JsonPrimitive(false), field("create_calendar_event", "all_day")["default"])
        assertEquals(JsonPrimitive(1024), field("create_calendar_event", "title")["maxLength"])
        assertEquals(JsonPrimitive(8192), field("open_url", "url")["maxLength"])
        assertEquals(JsonPrimitive(255), field("launch_app", "package_name")["maxLength"])
        assertEquals(JsonPrimitive(512), field("launch_activity", "activity_name")["maxLength"])
    }
    @Test fun `reading alternatives and outcome limitations stay explicit in Russian descriptions`() {
        assertTrue("HTTP is for API requests; page-reading alternative must be named", tool("http_request").description.contains("browser_get_text"))
        assertTrue("open_url only opens an app; reading alternative must be named", tool("open_url").description.contains("browser_get_text"))
        assertTrue(tool("set_text").description.contains("termux_run_command"))
        assertTrue(tool("take_screenshot").description.contains("after"))
        assertTrue(tool("show_alarms").description.contains("Не читает"))
    }
}
