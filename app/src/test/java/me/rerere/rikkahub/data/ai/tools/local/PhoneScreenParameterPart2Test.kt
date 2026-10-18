package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootProcessResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PhoneScreenParameterPart2Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val commands = mutableListOf<String>()
    private fun screen() = RootScreenService(context, { true }) { command, _, _, _ ->
        commands += command
        RootProcessResult(error = "unused_backend")
    }
    private suspend fun invoke(tool: Tool, input: JsonElement): JsonObject {
        val result = runCatching { tool.execute(input) }
        assertTrue("Malformed arguments must return an actionable JSON error, not throw: ${result.exceptionOrNull()}", result.isSuccess)
        val text = result.getOrThrow().filterIsInstance<UIMessagePart.Text>().single().text
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun error(result: JsonObject) {
        assertTrue("Expected an actionable error envelope: $result", result.containsKey("error"))
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]{3}").containsMatchIn(result.toString()))
    }
    @Test fun `screenshot rejects non-object arguments as JSON without root execution`() = runBlocking {
        for (input in listOf<JsonElement>(JsonNull, JsonArray(emptyList()), JsonPrimitive("capture"))) {
            commands.clear()
            error(invoke(takeScreenshotTool(context, screen()), input))
            assertTrue("Invalid object shape must not reach root: $commands", commands.isEmpty())
        }
    }
    @Test fun `screenshot rejects malformed field types before any root or public write`() = runBlocking {
        val inputs = listOf(
            """{"display_id":[]}""", """{"display_id":{}}""", """{"display_id":"not-a-number"}""",
            """{"display_id":1.5}""", """{"save_to_gallery":"maybe"}""", """{"save_to_gallery":1}""",
            """{"save_to_gallery":{}}""",
        )
        for (input in inputs) {
            commands.clear()
            error(invoke(takeScreenshotTool(context, screen()), Json.parseToJsonElement(input)))
            assertTrue("Invalid screenshot args must fail before backend effects: $input / $commands", commands.isEmpty())
        }
    }
    @Test fun `unsupported screenshot display id is actionable before root commands`() = runBlocking {
        for (display in listOf(-1, 1, Int.MAX_VALUE)) {
            commands.clear()
            val result = invoke(takeScreenshotTool(context, screen()), buildJsonObject { put("display_id", display) })
            error(result)
            assertTrue("Only physical display0 is supported; reject before root: $commands", commands.isEmpty())
            assertTrue("Explain the supported display to the caller: $result", result.toString().contains("display_id"))
        }
    }
    @Test fun `screenshot rejects out of Int range display ids before root or platform effects`() = runBlocking {
        for (display in listOf(2147483648L, -2147483649L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            commands.clear()
            val result = invoke(takeScreenshotTool(context, screen()), buildJsonObject { put("display_id", display) })
            assertTrue("A nonzero Long display must not become default display0: $display / $commands", commands.isEmpty())
            error(result)
            assertTrue("Explain the supported display: $result", result.toString().contains("display_id"))
        }
    }
    private fun clipboard(): ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Test fun `clipboard refuses non-string write text without changing stored clipboard`() = runBlocking {
        val before = "Сохранить исходный текст"
        for (value in listOf<JsonElement>(JsonPrimitive(42), JsonPrimitive(false), JsonArray(emptyList()), JsonNull)) {
            clipboard().setPrimaryClip(ClipData.newPlainText("before", before))
            val result = invoke(buildClipboardTool(context), buildJsonObject { put("action", "write"); put("text", value) })
            error(result)
            assertEquals(before, clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun `clipboard refuses malformed reveal instead of silently ignoring it`() = runBlocking {
        val secret = "sk-proj-" + "a".repeat(48)
        for (value in listOf<JsonElement>(JsonPrimitive("maybe"), JsonPrimitive(1), JsonArray(emptyList()), JsonNull)) {
            clipboard().setPrimaryClip(ClipData.newPlainText("before", secret))
            val result = invoke(buildClipboardTool(context), buildJsonObject { put("action", "read"); put("reveal", value) })
            error(result)
            assertFalse("Type errors must not expose clipboard content", result.toString().contains(secret))
            assertEquals(secret, clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun `NFC optional language must not silently replace a malformed value with ru`() {
        for (value in listOf<JsonElement>(JsonPrimitive(false), JsonPrimitive(42), JsonArray(emptyList()), JsonNull)) {
            val result = runCatching { NfcNdefCodec.encodeRecords(buildJsonArray { add(buildJsonObject {
                put("kind", "text"); put("value", "Подписанный текст"); put("language", value)
            }) }) }
            assertTrue("Present malformed language must be rejected rather than defaulted: $value", result.exceptionOrNull() is IllegalArgumentException)
        }
    }
    private fun nativeContext(): Context {
        val pm = mockk<PackageManager>()
        val packages = listOf("org.part2.alpha", "org.part2.beta").map { pkg -> PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = 0 }
        } }
        every { pm.getInstalledPackages(any<Int>()) } returns packages
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
        every { pm.getApplicationLabel(any()) } answers { firstArg<ApplicationInfo>().packageName }
        every { pm.getPackageInfo("org.part2.alpha", PackageManager.GET_ACTIVITIES) } returns packages.first().apply {
            activities = arrayOf(ActivityInfo().apply { name = "org.part2.alpha.One"; exported = true; enabled = true },
                ActivityInfo().apply { name = "org.part2.alpha.Two"; exported = true; enabled = true })
        }
        return object : ContextWrapper(context) { override fun getPackageManager(): PackageManager = pm }
    }
    @Test fun `installed apps list reports truncation with returned and matched counts`() = runBlocking {
        val tool = appLauncherTools(nativeContext()).single { it.name == "list_installed_apps" }
        val result = invoke(tool, buildJsonObject { put("limit", 1); put("include_no_launcher", true) })
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(1, result.getValue("apps").jsonArray.size)
        assertEquals(JsonPrimitive(1), result["returned"])
        assertEquals(JsonPrimitive(2), result["matched"])
    }
    @Test fun `activity list reports truncation with returned and matched counts`() = runBlocking {
        val tool = appLauncherTools(nativeContext()).single { it.name == "list_app_activities" }
        val result = invoke(tool, buildJsonObject { put("package_name", "org.part2.alpha"); put("limit", 1) })
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertEquals(1, result.getValue("activities").jsonArray.size)
        assertEquals(JsonPrimitive(1), result["returned"])
        assertEquals(JsonPrimitive(2), result["matched"])
    }
    @Test fun `unsupported platform error stays Russian actionable and hides native details`() = runBlocking {
        val unsupported = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.CLIPBOARD_SERVICE) throw UnsupportedOperationException("native-private-canary")
                return super.getSystemService(name)
            }
        }
        val result = invoke(buildClipboardTool(unsupported), buildJsonObject { put("action", "write"); put("text", "Текст") })
        error(result)
        assertTrue(result.toString(), result.toString().contains("не поддерживается"))
        assertFalse(result.toString().contains("native-private-canary"))
    }
}
