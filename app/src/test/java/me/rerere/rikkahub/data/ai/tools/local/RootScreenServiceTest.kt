package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.app.Application
import android.graphics.Bitmap
import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.clipboard.RootClipboardInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import java.io.File
import java.util.regex.PatternSyntaxException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RootScreenServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<Pair<String, Int>>()
    private val pastedTexts = mutableListOf<String>()
    private fun service(allowed: suspend () -> Boolean = { true },
        runner: suspend (String, Int) -> RootProcessResult = { command, _ ->
            when {
                command == "wm size" -> RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
                command.startsWith("dumpsys window") -> RootProcessResult(exitCode = 0, stdout = "mCurrentFocus=Window{abc123 u0 org.test/.MainActivity}\nmInputMethodWindow=Window{ime u0 InputMethod}")
                command.startsWith("dumpsys input") -> RootProcessResult(exitCode = 0, stdout = "SurfaceOrientation: 0")
                command.startsWith("screencap -p") -> {
                    val path = command.substringAfter("screencap -p ").removeSurrounding("\'")
                    val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
                    File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    RootProcessResult(exitCode = 0)
                }
                command.contains("uiautomator dump") -> {
                    val path = command.substringAfter("uiautomator dump ").removeSurrounding("'")
                    File(path).writeText(XML)
                    RootProcessResult(exitCode = 0)
                }
                else -> RootProcessResult(exitCode = 0)
            }
        }): RootScreenService = RootScreenService(context, allowed, RootClipboardInput { text, authorize, launch ->
        pastedTexts.add(text)
        var error: String? = null
        for (command in listOf("input keycombination 113 29", "input keyevent 67", "input keyevent 279")) {
            if (!authorize(command)) { error = "screen_permission_revoked"; break }
            val result = launch(command, 10_000)
            if (result.error != null || result.exitCode != 0) { error = result.error ?: "root_input_failed"; break }
        }
        error
    }) { command, timeout, _, freshPermission ->
        calls.add(command to timeout)
        if (freshPermission()) runner(command, timeout) else RootProcessResult(error = "screen_permission_revoked")
    }
    private fun runTool(tool: me.rerere.ai.core.Tool, input: String = "{}"): String = runBlocking {
        tool.execute(Json.parseToJsonElement(input)).joinToString { it.toString() }
    }

    @Test fun `all Agent screen tools have explicit approval`() {
        val tools = createScreenAutomationTools(context, RootShellManager(suExecutable = "/nonexistent-su"))
        assertEquals(setOf("tap", "long_press", "swipe", "read_window_tree", "find_node", "click_node", "set_text", "scroll", "global_action", "take_screenshot", "wake_screen"), tools.map { it.name }.toSet())
        tools.forEach { assertTrue(it.name, it.needsApproval(Json.parseToJsonElement("{}"))) }
    }
    @Test fun `factory permission callback gets the exact current tool and arguments`() {
        val permissions = mutableListOf<Pair<String, String>>()
        val tools = createScreenAutomationTools(context, RootShellManager(suExecutable = "/must-not-launch-su")) { name, args ->
            permissions.add(name to args.toString())
            false
        }
        val result = runTool(tools.first { it.name == "read_window_tree" }, "{\"package_name\":\"org.test\"}")
        assertTrue(result.contains("screen_permission_revoked"))
        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.all { it == ("read_window_tree" to "{\"package_name\":\"org.test\"}") })
        permissions.clear()
        val next = runTool(tools.first { it.name == "global_action" }, "{\"action\":\"home\"}")
        assertTrue(next.contains("screen_permission_revoked"))
        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.all { it == ("global_action" to "{\"action\":\"home\"}") })
    }
    @Test fun `private tree XML retains bounds selectors and ancestor`() {
        val nodes = parseRootWindowXml(XML)
        assertEquals(3, nodes.size)
        assertEquals("Search", nodes[1].text)
        assertTrue(nodes[1].isEditable)
        assertEquals("org.test:id/query", nodes[1].viewIdResourceName)
        assertEquals(10, nodes[1].bounds.left)
        assertSame(nodes[0], nodes[1].parent)
        assertEquals(2, nodes[0].childCount)
    }
    @Test fun `tree XML rejects entity and doctype expansion`() {
        try { parseRootWindowXml("<!DOCTYPE hierarchy [<!ENTITY x SYSTEM 'file:///private'>]><hierarchy/>"); fail("DTD must fail") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun `root viewport respects display override`() {
        assertEquals(720 to 1600, parseRootDisplaySize("Physical size: 1080x2400\nOverride size: 720x1600"))
        assertNull(parseRootDisplaySize("Physical size: 0x2400"))
    }
    @Test fun `text shell quoting preserves literals and never runs substitutions`() {
        val text = "literal ' \$(id) `uname` ; | &"
        val command = "printf %s ${rootScreenQuote(text)}"
        val process = ProcessBuilder("sh", "-c", command).start()
        assertEquals(text, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
    @Test fun `ASCII conversion escapes spaces and leaves clipboard text to alternate transport`() {
        assertEquals("hello%sworld", rootInputText("hello world"))
        assertEquals("", rootInputText(""))
        assertNull(rootInputText("Привет"))
        assertNull(rootInputText("literal%s"))
        assertNull(rootInputText("line\nnext"))
    }
    @Test fun `Unicode text reaches verified field instead of being rejected as ASCII`() = runBlocking {
        val svc = service()
        assertTrue(svc.setText(parseRootWindowXml(XML)[1], "Привет 👩🏽‍💻"))
        assertNull(svc.lastFailure)
        assertEquals(listOf("Привет 👩🏽‍💻"), pastedTexts)
        assertEquals(listOf("input tap 400 60", "input keycombination 113 29", "input keyevent 67", "input keyevent 279"),
            calls.map { it.first }.filter { it.startsWith("input ") })
        assertTrue(calls.none { it.first.startsWith("input text") })
    }
    @Test fun `Unicode cannot bypass HARDLINE literal preflight`() = runBlocking {
        val svc = service()
        assertFalse(svc.setText(parseRootWindowXml(XML)[1], "Привет $(reboot)"))
        assertEquals("root_command_blocked", svc.lastFailure)
        assertTrue(calls.isEmpty())
        assertTrue(pastedTexts.isEmpty())
    }
    @Test fun `Unicode fresh permission stops between internal input steps`() = runBlocking {
        var allowed = true
        val svc = service(allowed = { allowed }, runner = { command, _ ->
            when {
                command == "wm size" -> RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
                command.startsWith("uiautomator dump") -> {
                    File(command.substringAfter("uiautomator dump ").removeSurrounding("'"))
                        .writeText(XML)
                    RootProcessResult(exitCode = 0)
                }
                else -> {
                    if (command == "input keycombination 113 29") allowed = false
                    RootProcessResult(exitCode = 0)
                }
            }
        })
        assertFalse(svc.setText(parseRootWindowXml(XML)[1], "Привет"))
        assertEquals("screen_permission_revoked", svc.lastFailure)
        assertTrue(calls.any { it.first == "input keycombination 113 29" })
        assertTrue(calls.none { it.first == "input keyevent 67" || it.first == "input keyevent 279" })
    }
    @Test fun `text rejected by HARDLINE cannot clear an existing field`() = runBlocking {
        val svc = service()
        assertFalse(svc.setText(parseRootWindowXml(XML)[1], "$(reboot)"))
        assertEquals("root_command_blocked", svc.lastFailure)
        assertTrue(calls.isEmpty())
    }
    @Test fun `text replacement verifies focus before clearing`() = runBlocking {
        val svc = service()
        assertTrue(svc.setText(parseRootWindowXml(XML)[1], "hello world"))
        assertEquals(listOf("input tap 400 60", "input keycombination 113 29", "input keyevent 67", "input text 'hello%sworld'"),
            calls.map { it.first }.filter { it.startsWith("input ") })
        assertTrue(pastedTexts.isEmpty())
    }
    @Test fun `unverified focus never selects or deletes another field`() = runBlocking {
        val svc = service(runner = { command, _ ->
            if (command.startsWith("uiautomator dump")) {
                File(command.substringAfter("uiautomator dump ").removeSurrounding("'"))
                    .writeText(XML.replace("focused=\"true\"", "focused=\"false\""))
            }
            RootProcessResult(exitCode = 0, stdout = if (command == "wm size") "Physical size: 1080x2400" else "")
        })
        assertFalse(svc.setText(parseRootWindowXml(XML)[1], "hello"))
        assertEquals("root_input_focus_unverified", svc.lastFailure)
        assertTrue(calls.none { it.first == "input keycombination 113 29" || it.first == "input keyevent 67" })
    }
    @Test fun `revoked feature cannot read tree or launch root`() {
        val result = runTool(readWindowTreeTool(service(allowed = { false })))
        assertTrue(result, result.contains("screen_permission_revoked"))
        assertTrue(calls.isEmpty())
    }
    @Test fun `live approval is checked before every individual input step`() = runBlocking {
        var allowed = true
        val svc = service(allowed = { allowed }, runner = { command, _ ->
            if (command.startsWith("input tap")) allowed = false
            RootProcessResult(exitCode = 0)
        })
        val node = parseRootWindowXml(XML)[1]
        assertFalse(svc.setText(node, "hello"))
        assertEquals(listOf("input tap 400 60"), calls.map { it.first })
        assertEquals("screen_permission_revoked", svc.lastFailure)
    }
    @Test fun `forbidden root commands never reach runner`() = runBlocking {
        val svc = service()
        val result = svc.exec("rm -rf /system")
        assertEquals("root_command_blocked", result.error)
        assertTrue(calls.isEmpty())
    }
    @Test fun `timed out gesture is bounded and never replayed`() = runBlocking {
        val svc = service(runner = { _, _ -> RootProcessResult(error = "command_timeout") })
        assertFalse(svc.tap(11.0, 12.0, 600))
        assertEquals(listOf("input swipe 11 12 11 12 600" to 10_600), calls)
        assertEquals("command_timeout", svc.lastFailure)
    }
    @Test fun `out of viewport tap never executes input`() {
        val result = runTool(tapTool(service()), "{\"x\":1080,\"y\":10}")
        assertTrue(result, result.contains("out_of_bounds"))
        assertTrue(calls.none { it.first.startsWith("input tap") })
    }
    @Test fun `tree and find share the source traversal node IDs`() {
        val svc = service()
        val tree = runTool(readWindowTreeTool(svc))
        val find = runTool(findNodeTool(svc), "{\"by\":\"text\",\"value\":\"Search\"}")
        assertTrue(tree, tree.contains("org.test:id/query"))
        assertTrue(find, find.contains("org.test:id/query"))
        val firstNodeId = Regex("node_id[^:]*:[^0-9-]*(-?\\d+:2)").find(tree)?.groupValues?.get(1)
        assertNotNull(tree, firstNodeId)
        assertTrue(find, find.contains(firstNodeId!!))
    }
    @Test fun `secure root keyguard is not bypassed with unlock commands`() {
        assertEquals("input keyevent 223", rootGlobalActionCommand(RootScreenService.GLOBAL_ACTION_LOCK_SCREEN))
        assertNull(rootGlobalActionCommand(999))
    }

    private fun assertToolWorks(tool: me.rerere.ai.core.Tool, input: String = "{}", commandPrefix: String? = null): String {
        val result = runTool(tool, input)
        assertFalse(result, result.contains("screen_backend_failed"))
        assertFalse(result, result.contains("\"error\":"))
        if (commandPrefix != null) assertTrue(calls.toString(), calls.any { it.first.startsWith(commandPrefix) })
        return result
    }
    @Test fun `root executor reaches tap`() {
        assertToolWorks(tapTool(service()), "{\"x\":10,\"y\":20}", "input tap 10 20")
    }
    @Test fun `root executor reaches long press`() {
        assertToolWorks(longPressTool(service()), "{\"x\":10,\"y\":20}", "input swipe 10 20 10 20")
    }
    @Test fun `root executor reaches swipe`() {
        assertToolWorks(swipeTool(service()), "{\"start_x\":10,\"start_y\":20,\"end_x\":30,\"end_y\":40}", "input swipe 10 20 30 40")
    }
    @Test fun `root executor reaches window tree and parses focus diagnostics`() {
        val result = assertToolWorks(readWindowTreeTool(service()), commandPrefix = "uiautomator dump")
        assertTrue(result, result.contains("org.test/.MainActivity"))
        assertTrue(result, result.contains("org.test:id/query"))
    }
    @Test fun `root executor reaches node search`() {
        val result = assertToolWorks(findNodeTool(service()), "{\"by\":\"text\",\"value\":\"Search\"}", "uiautomator dump")
        assertTrue(result, result.contains("org.test:id/query"))
    }
    @Test fun `root executor reaches node click`() {
        assertToolWorks(clickNodeTool(service()), "{\"by\":\"text\",\"value\":\"Go\"}", "input tap 900 60")
    }
    @Test fun `root executor reaches text replacement`() {
        assertToolWorks(setTextTool(service()), "{\"by\":\"text\",\"value\":\"Search\",\"text\":\"hello\"}", "input text 'hello'")
    }
    @Test fun `root executor reaches scrolling`() {
        assertToolWorks(scrollTool(service()), "{\"direction\":\"down\"}", "input swipe")
    }
    @Test fun `root executor reaches global action`() {
        assertToolWorks(globalActionTool(service()), "{\"action\":\"home\"}", "input keyevent 3")
    }
    @Test fun `root executor reaches screenshot and returns image attachment`() {
        val result = assertToolWorks(takeScreenshotTool(context, service()), commandPrefix = "screencap -p")
        assertTrue(result, result.contains("file://"))
        assertTrue(result, result.contains("gallery_path"))
    }
    @Test fun `root executor reaches screen wake`() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val result = assertToolWorks(wakeScreenTool(context, service()), commandPrefix = "input keyevent 224")
        assertTrue(result, result.contains("\"woke\":true"))
    }
    @Test fun `snapshot exception returns type and stage and logs only redacted diagnostic`() {
        ShadowLog.clear()
        val secret = "sk-test123456789012345678901234567890"
        val svc = service(runner = { _, _ -> throw IllegalStateException("broken root session password=secret123 $secret Bearer token-123") })
        val result = runTool(wakeScreenTool(context, svc))
        assertTrue(result, result.contains("screen_backend_failed"))
        assertTrue(result, result.contains("\"detail\":\"java.lang.IllegalStateException\""))
        assertTrue(result, result.contains("\"stage\":\"snapshot_wm_size\""))
        assertFalse(result, result.contains("password"))
        val logs = ShadowLog.getLogsForTag("RootScreenService").joinToString("\n") { it.msg }
        assertTrue(logs, logs.contains("IllegalStateException"))
        assertTrue(logs, logs.contains("broken root session"))
        assertFalse(logs, logs.contains("secret123"))
        assertFalse(logs, logs.contains(secret))
        assertFalse(logs, logs.contains("token-123"))
    }
    @Test fun `Android ICU pattern failure is visible instead of generic backend error`() {
        val svc = service(runner = { command, _ ->
            if (command.startsWith("dumpsys window")) throw PatternSyntaxException("Syntax error", "mCurrentFocus=Window\\{[^}]*}", 30)
            RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
        })
        val result = runTool(wakeScreenTool(context, svc))
        assertTrue(result, result.contains("\"detail\":\"java.util.regex.PatternSyntaxException\""))
        assertTrue(result, result.contains("\"stage\":\"snapshot_window_state\""))
    }
    @Test fun `invalid tree XML returns exception detail`() {
        val svc = service(runner = { command, _ ->
            if (command.startsWith("uiautomator dump")) {
                File(command.substringAfter("uiautomator dump ").removeSurrounding("'")).writeText("<hierarchy><node></hierarchy>")
            }
            RootProcessResult(exitCode = 0, stdout = if (command == "wm size") "Physical size: 1080x2400" else "")
        })
        val result = runTool(readWindowTreeTool(svc))
        assertTrue(result, result.contains("ui_tree_decode_failed"))
        assertTrue(result, result.contains("detail"))
        assertTrue(result, result.contains("snapshot_tree_decode"))
    }
    @Test fun `snapshot cancellation propagates without becoming a backend error`() = runBlocking {
        val svc = service(runner = { _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") })
        try { svc.withService(treeRequired = false) { buildJsonObject { put("success", true) } }; fail("cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    companion object {
        private const val XML = """<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node package="org.test" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true"><node package="org.test" class="android.widget.EditText" focused="true" text="Search" resource-id="org.test:id/query" bounds="[10,20][790,100]" clickable="true" enabled="true"/><node package="org.test" class="android.widget.Button" text="Go" content-desc="Go button" bounds="[800,20][1000,100]" clickable="true" enabled="true"/></node></hierarchy>"""
    }
}
