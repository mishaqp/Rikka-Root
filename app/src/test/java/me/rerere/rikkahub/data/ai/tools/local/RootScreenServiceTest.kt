package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.app.Application
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
import org.robolectric.annotation.Config
import java.io.File

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

    companion object {
        private const val XML = """<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node package="org.test" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true"><node package="org.test" class="android.widget.EditText" focused="true" text="Search" resource-id="org.test:id/query" bounds="[10,20][790,100]" clickable="true" enabled="true"/><node package="org.test" class="android.widget.Button" text="Go" content-desc="Go button" bounds="[800,20][1000,100]" clickable="true" enabled="true"/></node></hierarchy>"""
    }
}
