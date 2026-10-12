package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.uuid.Uuid

class ChatToolDisplayNamesTest {
    /** Permission registrations plus real definitions of read-only/special tools, not a second title table. */
    @Test fun everyFixedNativeToolHasARussianResource() {
        val names = ToolPermissionPolicy.registry.keys.toMutableSet()
        val registrationFiles = listOf(
            "SearchTools", "SkillsTools", "ConversationTools", "WorkspaceTools",
            "local/TimeInfoTool", "local/TelephonyInfoTool", "local/ScreenTimeTool",
            "local/StorageTool", "local/BatteryTool", "local/ChartDisplayTool",
            "local/AskUserTool", "local/WifiInfoTool", "local/SensorTool",
            "local/CalendarTool", "local/AudioInfoTool", "local/JavascriptTool",
        )
        val literalRegistration = Regex("\\bname\\s*=\\s*\"([a-z][a-z0-9_]+)\"")
        registrationFiles.forEach { path ->
            literalRegistration.findAll(ChatProductionSource.read("data/ai/tools/$path.kt"))
                .forEach { names.add(it.groupValues[1]) }
        }
        assertEquals("The audited native catalog changed; review display coverage", 182, names.size)
        val resources = russianResources()
        names.forEach { name ->
            assertNotNull("Missing display name for $name", timelineToolNameResource(name))
            val resource = resources["chat_timeline_tool_$name"]
            assertNotNull("Missing Russian resource for $name", resource)
            assertTrue("Non-Russian title for $name", resource!!.contains(Regex("[А-Яа-яЁё]")))
        }
    }

    @Test fun knownProviderToolsHaveRussianActionNames() {
        assertEquals(R.string.chat_timeline_server_tool_web_search, timelineToolNameResource("web_search"))
        assertEquals(R.string.chat_timeline_server_tool_file_search, timelineToolNameResource("file_search"))
        assertEquals(R.string.chat_timeline_server_tool_code_interpreter, timelineToolNameResource("code_interpreter"))
    }

    @Test fun remoteMcpUsesTheActualGeneratedNamespace() {
        val remote = buildMcpToolName(Uuid.parse("12345678-1234-1234-1234-123456789abc"), "Мой сервер", "read")
        assertEquals(R.string.chat_timeline_tool_remote_mcp, timelineToolNameResource(remote))
        assertEquals(R.string.chat_timeline_tool_mcp_list, timelineToolNameResource("mcp_list"))
        listOf("mcp_future", "mcp__server__read", "mcp__12345678___", "mcp__12345678_server__").forEach {
            assertNull(it, timelineToolNameResource(it))
        }
    }

    @Test fun unknownToolsKeepTheirRawNameInLocalizedFallback() {
        assertNull(timelineToolNameResource("future_tool"))
        assertNull(timelineToolNameResource(""))
        assertEquals("Инструмент: %1\$s", russianResources()["chat_timeline_tool_unknown"])
    }

    private fun russianResources(): Map<String, String> {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/res/values/chat_tool_display_names.xml").isFile }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/values/chat_tool_display_names.xml"))
            .getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val node = strings.item(index)
            assertEquals("false", node.attributes.getNamedItem("translatable")?.nodeValue)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
