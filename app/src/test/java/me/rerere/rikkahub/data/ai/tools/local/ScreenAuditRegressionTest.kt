package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootProcessResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ScreenAuditRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private var xml = XML
    private var dumpRotation = 0

    private fun service() = RootScreenService(context, { true }) { command, _, _, authorize ->
        if (!authorize()) return@RootScreenService RootProcessResult(error = "screen_permission_revoked")
        calls.add(command)
        when {
            command == "wm size" -> RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
            command.startsWith("dumpsys window") -> RootProcessResult(exitCode = 0,
                stdout = "mCurrentFocus=Window{abc123 u0 org.test/.MainActivity}")
            command.startsWith("dumpsys input") -> RootProcessResult(exitCode = 0,
                stdout = "SurfaceOrientation: $dumpRotation")
            command.startsWith("uiautomator dump ") -> {
                File(command.substringAfter("uiautomator dump ").removeSurrounding("'")).writeText(xml)
                RootProcessResult(exitCode = 0)
            }
            else -> RootProcessResult(exitCode = 0)
        }
    }

    private suspend fun execute(tool: Tool, input: String = "{}"): JsonObject =
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text).jsonObject

    private suspend fun nodeId(service: RootScreenService): String =
        execute(findNodeTool(service), """{"by":"text","value":"Search"}""")
            .getValue("matches").jsonArray.single().jsonObject.getValue("node_id").jsonPrimitive.content

    @Test fun `node handles change when the element is replaced`() = runBlocking {
        val svc = service()
        val original = nodeId(svc)
        listOf(
            XML.replace("android.widget.EditText", "org.test.CustomEditText"),
            XML.replace("org.test:id/search", "org.test:id/delete"),
        ).forEach { changed ->
            xml = changed
            val nodes = execute(readWindowTreeTool(svc), """{"verbose":true}""")
                .getValue("nodes").jsonArray
            val replacement = nodes[2].jsonObject.getValue("node_id").jsonPrimitive.content
            assertNotEquals("Node handle must identify the observed node, not only its position", original, replacement)
        }
    }

    @Test fun `review handle survives keyboard shift and the field's own edits`() = runBlocking {
        val svc = service()
        val original = nodeId(svc)
        for (changed in listOf(XML.replace("[20,100][220,300]", "[20,40][220,240]"), XML.replace("text=\"Search\"", "text=\"Searc\""))) {
            xml = changed
            val nodes = execute(readWindowTreeTool(svc), """{"verbose":true}""").getValue("nodes").jsonArray
            assertEquals(original, nodes[2].jsonObject.getValue("node_id").jsonPrimitive.content)
        }
    }

    @Test fun `review label text change is a new element but a ticking number is not`() = runBlocking {
        xml = ancestorXml(editable = false).replace("text=\"Search\"", "text=\"Отправить код (59с)\"")
        val svc = service()
        fun labelId() = runBlocking { execute(readWindowTreeTool(svc), """{"verbose":true}""")
            .getValue("nodes").jsonArray[2].jsonObject.getValue("node_id").jsonPrimitive.content }
        val original = labelId()
        xml = xml.replace("(59с)", "(58с)")
        assertEquals(original, labelId())
        xml = xml.replace("Отправить код (58с)", "Удалить всё")
        assertNotEquals(original, labelId())
    }

    @Test fun `remaining 50 changed order number rejects stale click before input`() = runBlocking {
        xml = ancestorXml(editable = false).replace("text=\"Search\"", "text=\"Открыть заказ 123\"")
        val svc = service()
        val original = execute(findNodeTool(svc), """{"by":"text","value":"Открыть заказ 123"}""")
            .getValue("matches").jsonArray.single().jsonObject.getValue("node_id").jsonPrimitive.content
        xml = xml.replace("Открыть заказ 123", "Открыть заказ 456")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", original) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertFalse("A stale order must never tap the replacement", calls.any { it.startsWith("input tap ") })
    }

    @Test fun `remaining 50 changed accessible amount rejects stale click before input`() = runBlocking {
        xml = ancestorXml(editable = false).replace("text=\"Search\"", "text=\"Оплатить\" content-desc=\"Оплатить 100 рублей\"")
        val svc = service()
        val original = execute(findNodeTool(svc), """{"by":"content_description","value":"Оплатить 100 рублей"}""")
            .getValue("matches").jsonArray.single().jsonObject.getValue("node_id").jsonPrimitive.content
        xml = xml.replace("Оплатить 100 рублей", "Оплатить 900 рублей")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", original) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertFalse("A stale amount must never tap the replacement", calls.any { it.startsWith("input tap ") })
    }

    @Test fun `replaced element refuses stale click without tapping replacement`() = runBlocking {
        val svc = service()
        val id = nodeId(svc)
        xml = XML.replace("text=\"Search\"", "text=\"Delete all\"").replace("org.test:id/search", "org.test:id/delete")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(result.toString(), result.toString().contains("Экран изменился, прочитайте дерево заново"))
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `replaced field refuses stale text edit before focus or deletion`() = runBlocking {
        val svc = service()
        val id = nodeId(svc)
        xml = XML.replace("org.test:id/search", "org.test:id/password")
        calls.clear()
        val result = execute(setTextTool(svc), buildJsonObject { put("node_id", id); put("text", "replacement") }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `unchanged handle remains usable across fresh snapshots`() = runBlocking {
        val svc = service()
        val id = nodeId(svc)
        val treeId = execute(readWindowTreeTool(svc), """{"verbose":true}""")
            .getValue("nodes").jsonArray[2].jsonObject.getValue("node_id").jsonPrimitive.content
        assertEquals(id, treeId)
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "true", result["success"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.contains("input tap 120 200"))
    }

    @Test fun `legacy positional handle cannot act without a fingerprint`() = runBlocking {
        val svc = service()
        val id = nodeId(svc).split(':').take(2).joinToString(":")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `changed clickable ancestor refuses child handle before input`() = runBlocking {
        xml = ancestorXml(editable = false)
        val svc = service()
        val id = nodeId(svc)
        xml = xml.replace("class=\"org.test.ClickableParent\"", "class=\"org.test.ReplacementParent\"")
            .replace("text=\"Parent\"", "text=\"Delete all\"")
            .replace("[0,0][300,600]", "[0,0][320,640]")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `changed semantic text of click target invalidates its unchanged child handle`() = runBlocking {
        xml = ancestorXml(editable = false).replace("text=\"Parent\"", "text=\"Открыть заказ 123\"")
        val svc = service()
        val id = nodeId(svc)
        xml = xml.replace("Открыть заказ 123", "Открыть заказ 456")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `a ticking countdown on the click target preserves its unchanged child handle`() = runBlocking {
        xml = ancestorXml(editable = false).replace("text=\"Parent\"", "text=\"Отправить код (59с)\"")
        val svc = service()
        val id = nodeId(svc)
        xml = xml.replace("(59с)", "(58с)")
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "true", result["success"]?.jsonPrimitive?.content)
        assertEquals(listOf("input tap 150 300"), calls.filter { it.startsWith("input ") })
    }

    @Test fun `changed editable ancestor refuses child handle before focus or deletion`() = runBlocking {
        xml = ancestorXml(editable = true)
        val svc = service()
        val id = nodeId(svc)
        xml = xml.replace("class=\"org.test.ParentEditText\"", "class=\"org.test.ReplacementEditText\"")
            .replace("text=\"Parent\"", "text=\"Other field\"")
            .replace("[0,0][300,600]", "[0,0][320,640]")
        calls.clear()
        val result = execute(setTextTool(svc), buildJsonObject {
            put("node_id", id); put("text", "replacement")
        }.toString())
        assertEquals(result.toString(), "stale_node_id", result["error"]?.jsonPrimitive?.content)
        assertTrue(calls.toString(), calls.none { it.startsWith("input ") })
    }

    @Test fun `unchanged clickable ancestor keeps child handle usable`() = runBlocking {
        xml = ancestorXml(editable = false)
        val svc = service()
        val id = nodeId(svc)
        calls.clear()
        val result = execute(clickNodeTool(svc), buildJsonObject { put("node_id", id) }.toString())
        assertEquals(result.toString(), "true", result["success"]?.jsonPrimitive?.content)
        assertEquals("true", result.getValue("clicked").jsonObject.getValue("resolved_via_ancestor").jsonPrimitive.content)
        assertEquals(listOf("input tap 150 300"), calls.filter { it.startsWith("input ") })
    }

    @Test fun `unchanged editable ancestor keeps child handle usable`() = runBlocking {
        xml = ancestorXml(editable = true)
        val svc = service()
        val id = nodeId(svc)
        calls.clear()
        val result = execute(setTextTool(svc), buildJsonObject {
            put("node_id", id); put("text", "replacement")
        }.toString())
        assertEquals(result.toString(), "true", result["success"]?.jsonPrimitive?.content)
        assertEquals(listOf("input tap 150 300", "input keycombination 113 29", "input keyevent 67",
            "input text 'replacement'"), calls.filter { it.startsWith("input ") })
    }

    private fun ancestorXml(editable: Boolean): String = """<hierarchy rotation="0">
        <node package="org.test" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
          <node package="org.test" class="${if (editable) "org.test.ParentEditText" else "org.test.ClickableParent"}" text="Parent" clickable="true" editable="$editable" focused="$editable" bounds="[0,0][300,600]" enabled="true">
            <node package="org.test" class="android.widget.TextView" text="Search" resource-id="org.test:id/label" clickable="false" editable="false" bounds="[20,100][220,300]" enabled="true"/>
          </node>
        </node>
    </hierarchy>""".trimIndent()

    @Test fun `fallback swipe stays inside selected scrollable container`() = runBlocking {
        val result = execute(scrollTool(service()), """{"direction":"down","x":100,"y":250}""")
        assertEquals(result.toString(), "true", result["success"]?.jsonPrimitive?.content)
        val swipe = calls.single { it.startsWith("input swipe ") }.split(' ')
        val sx = swipe[2].toInt(); val sy = swipe[3].toInt()
        val ex = swipe[4].toInt(); val ey = swipe[5].toInt()
        assertTrue(calls.toString(), sx in 0 until 300 && ex in 0 until 300 && sy in 0 until 600 && ey in 0 until 600)
        assertTrue("down scroll must move the finger upwards", sy > ey)
    }

    @Test fun `system display rotation wins over contradictory dumpsys orientation`() = runBlocking {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        shadowOf(display).setRotation(Surface.ROTATION_90)
        dumpRotation = 0
        val rotated = execute(readWindowTreeTool(service())).getValue("screen_state").jsonObject
            .getValue("display").jsonObject
        assertEquals("2400", rotated.getValue("w").jsonPrimitive.content)
        assertEquals("1080", rotated.getValue("h").jsonPrimitive.content)
    }

    @Test fun `stale dumpsys orientation cannot rotate portrait viewport`() = runBlocking {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        shadowOf(display).setRotation(Surface.ROTATION_0)
        dumpRotation = 1
        val viewport = execute(readWindowTreeTool(service())).getValue("screen_state").jsonObject
            .getValue("display").jsonObject
        assertEquals("1080", viewport.getValue("w").jsonPrimitive.content)
        assertEquals("2400", viewport.getValue("h").jsonPrimitive.content)
    }

    @Test fun `invalid arguments never wake a sleeping screen`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val svc = service()
        val invalid = listOf(
            tapTool(svc) to "{}",
            longPressTool(svc) to """{"x":1,"y":1,"duration_ms":99}""",
            swipeTool(svc) to """{"start_x":1,"start_y":2,"end_x":3,"end_y":4,"duration_ms":49}""",
            findNodeTool(svc) to "{}",
            clickNodeTool(svc) to """{"node_id":"broken"}""",
            setTextTool(svc) to "{}",
            scrollTool(svc) to """{"direction":"sideways"}""",
            globalActionTool(svc) to """{"action":"invalid"}""",
        )
        invalid.forEach { (tool, input) ->
            calls.clear()
            assertTrue(tool.name, execute(tool, input).containsKey("error"))
            assertFalse("${tool.name}: $calls", calls.contains("input keyevent 224"))
        }
    }

    @Test fun `wake waits for the same mutex as the action`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val svc = service()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            svc.withService(treeRequired = false) {
                entered.complete(Unit)
                release.await()
                buildJsonObject { put("success", true) }
            }
        }
        entered.await()
        calls.clear()
        val action = launch(start = CoroutineStart.UNDISPATCHED) {
            execute(tapTool(svc), """{"x":10,"y":20}""")
        }
        val wokeOutsideLock = calls.contains("input keyevent 224")
        release.complete(Unit)
        holder.join(); action.join()
        assertFalse("Wake ran while another screen operation owned the mutex", wokeOutsideLock)
        assertTrue(calls.toString(), calls.indexOf("input keyevent 224") < calls.indexOf("input tap 10 20"))
    }

    @Test fun `out of viewport coordinates do not wake sleeping screen`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val result = execute(tapTool(service()), """{"x":1080,"y":20}""")
        assertEquals(result.toString(), "out_of_bounds", result["error"]?.jsonPrimitive?.content)
        assertFalse(calls.toString(), calls.contains("input keyevent 224"))
    }

    companion object {
        private val XML = """<hierarchy rotation="0">
            <node package="org.test" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
              <node package="org.test" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][300,600]" enabled="true">
                <node package="org.test" class="android.widget.EditText" text="Search" resource-id="org.test:id/search" clickable="true" editable="true" focused="true" bounds="[20,100][220,300]" enabled="true"/>
              </node>
            </node>
        </hierarchy>""".trimIndent()
    }
}
