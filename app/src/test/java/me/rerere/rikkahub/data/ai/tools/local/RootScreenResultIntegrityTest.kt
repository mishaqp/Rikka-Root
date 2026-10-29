package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import android.os.PowerManager
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.HeadlessToolFailure
import me.rerere.rikkahub.service.classifyHeadlessToolFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RootScreenResultIntegrityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val commands = mutableListOf<String>()

    @Test fun `incomplete root output on tap retains exit and streams without replay`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val service = service { command ->
            if (command.startsWith("input tap")) RootProcessResult(exitCode = 0, stdout = "partial", stderr = "warning", error = "root_output_incomplete") else null
        }
        val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
        assertEquals("root_output_incomplete", result["error"]?.jsonPrimitive?.content)
        assertTrue(result.toString(), result["reason"]!!.jsonPrimitive.content.contains("вывод", ignoreCase = true))
        val diagnostic = result["root_result"]!!.jsonObject
        assertEquals("0", diagnostic["exit_code"]?.jsonPrimitive?.content)
        assertEquals("partial", diagnostic["stdout"]?.jsonPrimitive?.content)
        assertEquals("warning", diagnostic["stderr"]?.jsonPrimitive?.content)
        assertEquals(1, commands.count { it.startsWith("input tap") })
    }

    @Test fun `incomplete root output during wake stops before tap without replay`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val service = service { command ->
            if (command == "input keyevent 224") RootProcessResult(exitCode = 0, stdout = "wake partial", error = "root_output_incomplete") else null
        }
        val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
        assertEquals("root_output_incomplete", result["error"]?.jsonPrimitive?.content)
        assertEquals("wake partial", result["root_result"]!!.jsonObject["stdout"]?.jsonPrimitive?.content)
        assertEquals(1, commands.count { it == "input keyevent 224" })
        assertFalse(commands.any { it.startsWith("input tap") })
    }

    @Test fun `incomplete root output during pre-action tree stops before tap`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val service = service { command ->
            if (command.startsWith("uiautomator dump")) RootProcessResult(exitCode = 0, stdout = "tree partial", error = "root_output_incomplete") else null
        }
        val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
        assertEquals("root_output_incomplete", result["error"]?.jsonPrimitive?.content)
        assertEquals("tree partial", result["root_result"]!!.jsonObject["stdout"]?.jsonPrimitive?.content)
        assertEquals(1, commands.count { it.startsWith("uiautomator dump") })
        assertFalse(commands.any { it.startsWith("input tap") })
    }

    @Test fun `uncertain root wake or tree never proceeds to tap`() = runBlocking {
        for (code in listOf("command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate")) {
            for (wake in listOf(true, false)) {
                commands.clear()
                shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(!wake)
                val service = service { command ->
                    if (wake && command == "input keyevent 224" || !wake && command.startsWith("uiautomator dump"))
                        RootProcessResult(error = code, stdout = "partial") else null
                }
                val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
                assertEquals("wake=$wake code=$code result=$result", code, result["error"]?.jsonPrimitive?.content)
                assertEquals("partial", result["root_result"]!!.jsonObject["stdout"]?.jsonPrimitive?.content)
                assertFalse("wake=$wake code=$code: $commands", commands.any { it.startsWith("input tap") })
            }
        }
    }

    @Test fun `every failed root screen action keeps both streams and actual exit status`() = runBlocking {
        for (name in listOf("tap", "long_press", "swipe", "click_node", "set_text", "scroll", "global_action", "wake_screen", "take_screenshot")) {
            commands.clear()
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(name != "wake_screen")
            val failed = RootProcessResult(exitCode = 37, stdout = "partial out\n", stderr = "partial err\n")
            val service = service { command ->
                if (command.startsWith("input ") || command.startsWith("screencap ")) failed else null
            }
            val (tool, input) = action(name, service)
            val result = execute(tool, input)

            assertTrue("$name: $result", result.containsKey("error"))
            assertNotEquals("$name must remain unsuccessful", true, result["success"]?.jsonPrimitive?.booleanOrNull)
            val diagnostic = result["root_result"] as? JsonObject
            assertNotNull("$name discarded its root diagnostics: $result", diagnostic)
            assertEquals("37", diagnostic?.get("exit_code")?.jsonPrimitive?.content)
            assertEquals("partial out\n", diagnostic?.get("stdout")?.jsonPrimitive?.content)
            assertEquals("partial err\n", diagnostic?.get("stderr")?.jsonPrimitive?.content)
            assertEquals("$name must not replay a failed input", 1, commands.count { it.startsWith("input ") || it.startsWith("screencap ") })
        }
    }

    @Test fun `failed tree readers retain native timeout with partial output`() = runBlocking {
        for (find in listOf(false, true)) {
            val service = service { command ->
                if (command.startsWith("uiautomator dump")) RootProcessResult(error = "command_timeout", stdout = "dump started\n", stderr = "Killed\n") else null
            }
            val result = execute(if (find) findNodeTool(service) else readWindowTreeTool(service),
                if (find) buildJsonObject { put("by", "text"); put("value", "Go") } else buildJsonObject {})
            assertEquals("command_timeout", result["error"]?.jsonPrimitive?.content)
            val diagnostic = result["root_result"] as? JsonObject
            assertNotNull("Tree timeout must keep command output: $result", diagnostic)
            assertEquals("command_timeout", diagnostic?.get("error")?.jsonPrimitive?.content)
            assertEquals("dump started\n", diagnostic?.get("stdout")?.jsonPrimitive?.content)
            assertEquals("Killed\n", diagnostic?.get("stderr")?.jsonPrimitive?.content)
            assertFalse("A timeout has no confirmed exit code", diagnostic!!.containsKey("exit_code"))
        }
    }

    @Test fun `screen failures expose actual truncation and backend reason`() = runBlocking {
        val service = service { command ->
            if (command.startsWith("input ")) RootProcessResult(error = "command_timeout", reason = "bounded cleanup diagnostic",
                stdout = "partial", stderr = " ", stdoutTruncated = true, stderrTruncated = false) else null
        }
        val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
        val diagnostic = result["root_result"] as? JsonObject
        assertNotNull(result.toString(), diagnostic)
        assertEquals("bounded cleanup diagnostic", diagnostic?.get("reason")?.jsonPrimitive?.content)
        assertEquals(" ", diagnostic?.get("stderr")?.jsonPrimitive?.content)
        assertEquals(true, diagnostic?.get("stdout_truncated")?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, diagnostic?.get("stderr_truncated")?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun `successful action remains successful when its after observation fails`() = runBlocking {
        var accepted = false
        val service = service { command ->
            when {
                command.startsWith("input tap") -> { accepted = true; RootProcessResult(exitCode = 0) }
                accepted && command.startsWith("uiautomator dump") -> RootProcessResult(error = "command_timeout", stderr = "observation only")
                else -> null
            }
        }
        val result = execute(tapTool(service), buildJsonObject { put("x", 10); put("y", 20) })
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(result.containsKey("error"))
        assertFalse("Observation failure must not be attributed to the action", result.containsKey("root_result"))
        assertEquals("command_timeout", result["after"]?.jsonObject?.get("tree_error")?.jsonPrimitive?.content)
        assertTrue(result.containsKey("warning"))
    }

    @Test fun `locally blocked text does not borrow a successful snapshot as its command result`() = runBlocking {
        val service = service { null }
        val result = execute(setTextTool(service), buildJsonObject {
            put("by", "text"); put("value", "Search"); put("text", "reboot")
        })
        assertEquals("root_command_blocked", result["error"]?.jsonPrimitive?.content)
        assertFalse("A successful snapshot is unrelated to the rejected text: $result", result.containsKey("root_result"))
        assertFalse(commands.any { it.startsWith("input ") })
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, "root_command_blocked"),
            classifyHeadlessToolFailure("set_text", listOf(UIMessagePart.Text(result.toString()))))
    }

    private fun service(failure: (String) -> RootProcessResult?): RootScreenService = RootScreenService(context, { true }) { command, _, _, permission ->
        check(permission())
        commands += command
        failure(command) ?: when {
            command == "wm size" -> RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
            command.startsWith("dumpsys window") -> RootProcessResult(exitCode = 0, stdout = "mCurrentFocus=Window{abc u0 org.test/.MainActivity}")
            command.startsWith("uiautomator dump") -> {
                File(command.substringAfter("uiautomator dump ").removeSurrounding("'")).writeText(XML)
                RootProcessResult(exitCode = 0)
            }
            else -> RootProcessResult(exitCode = 0)
        }
    }

    private fun action(name: String, service: RootScreenService): Pair<Tool, JsonObject> = when (name) {
        "tap" -> tapTool(service) to buildJsonObject { put("x", 10); put("y", 20) }
        "long_press" -> longPressTool(service) to buildJsonObject { put("x", 10); put("y", 20) }
        "swipe" -> swipeTool(service) to buildJsonObject { put("start_x", 10); put("start_y", 20); put("end_x", 30); put("end_y", 40) }
        "click_node" -> clickNodeTool(service) to buildJsonObject { put("by", "text"); put("value", "Go") }
        "set_text" -> setTextTool(service) to buildJsonObject { put("by", "text"); put("value", "Search"); put("text", "hello") }
        "scroll" -> scrollTool(service) to buildJsonObject { put("direction", "down") }
        "global_action" -> globalActionTool(service) to buildJsonObject { put("action", "home") }
        "wake_screen" -> wakeScreenTool(context, service) to buildJsonObject {}
        else -> takeScreenshotTool(context, service) to buildJsonObject {}
    }

    private suspend fun execute(tool: Tool, input: JsonObject): JsonObject =
        Json.parseToJsonElement(tool.execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    companion object {
        private const val XML = """<hierarchy><node package="org.test" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true"><node package="org.test" class="android.widget.EditText" focused="true" text="Search" resource-id="org.test:id/query" bounds="[10,20][790,100]" clickable="true" enabled="true"/><node package="org.test" class="android.widget.Button" text="Go" bounds="[800,20][1000,100]" clickable="true" enabled="true"/></node></hierarchy>"""
    }
}
