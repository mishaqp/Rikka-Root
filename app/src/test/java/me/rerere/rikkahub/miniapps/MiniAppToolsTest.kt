package me.rerere.rikkahub.miniapps

import kotlinx.coroutines.runBlocking
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MiniAppToolsTest {
    @Test fun optInOptionHasStableBackupName() {
        val encoded = Json.encodeToString<LocalToolOption>(LocalToolOption.MiniApps)
        assertEquals("mini_apps", Json.parseToJsonElement(encoded).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(LocalToolOption.MiniApps, Json.decodeFromString<LocalToolOption>(encoded))
    }

    @Test fun publishUsesWorkspaceApprovalOverrides() {
        assertEquals(false, WorkspaceToolDefaultApprovals["publish_mini_app"])
        assertFalse(resolveWorkspaceToolApproval("publish_mini_app", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("publish_mini_app", mapOf("publish_mini_app" to true)))
        assertTrue(ToolPermissionPolicy.registry.containsKey("mini_apps"))
        assertTrue(ToolPermissionPolicy.registry.containsKey("publish_mini_app"))
    }

    @Test fun missingAppReturnsCompatibleError() = runBlocking {
        val root = Files.createTempDirectory("mini-tools").toFile()
        try {
            val result = MiniAppDataTool(MiniAppStore(root)).execute(buildJsonObject {
                put("action", "read"); put("app_id", "missing")
            })
            assertEquals(JsonPrimitive(false), result["ok"])
            assertEquals(JsonPrimitive("not_found"), result["error"])
        } finally { root.deleteRecursively() }
    }

    @Test fun writesReadsAndBoundsAllDataWithoutLosingValues() = runBlocking {
        val root = Files.createTempDirectory("mini-tools").toFile()
        val source = Files.createTempDirectory("mini-source").toFile()
        try {
            source.resolve("moru-app.json").writeText("""{"id":"tracker","name":"Tracker","data":"Keep counter as a number"}""")
            source.resolve("index.html").writeText("<html>Tracker</html>")
            val store = MiniAppStore(root)
            val app = store.install(source).app
            val tool = MiniAppDataTool(store)
            suspend fun call(action: String, key: String? = null, value: JsonElement? = null) = tool.execute(buildJsonObject {
                put("action", action); put("app_id", app.id)
                key?.let { put("key", it) }; value?.let { put("value", it) }
            })
            assertEquals(JsonPrimitive("counter"), call("write", "counter", JsonPrimitive(3))["written"])
            assertEquals(JsonPrimitive(3), call("read", "counter")["value"])
            val values = listOf(JsonPrimitive("a b"), JsonPrimitive(-0.25), JsonPrimitive(true), JsonNull,
                Json.parseToJsonElement("""[1,null,{"nested":[false,"text"]}]"""),
                Json.parseToJsonElement("""{"empty":[],"object":{},"value":null}"""))
            values.forEachIndexed { index, value ->
                val key = "json-$index"
                assertEquals(JsonPrimitive(key), call("write", key, value)["written"])
                assertEquals(value, call("read", key)["value"])
            }
            call("write", "large", JsonPrimitive("a".repeat(20001)))
            assertTrue(call("read").containsKey("keys"))
            assertFalse(call("read").containsKey("data"))
            assertEquals(JsonPrimitive("a".repeat(20001)), call("read", "large")["value"])
            assertEquals(JsonPrimitive("counter"), call("remove", "counter")["removed"])
            assertEquals(JsonNull, call("read", "counter")["value"])
            assertEquals(JsonPrimitive("missing_value"), call("write", "counter")["error"])
        } finally { root.deleteRecursively(); source.deleteRecursively() }
    }

    @Test fun publishingResolvesSelectedMountAndUpdatesWithoutLosingData() = runBlocking {
        val root = Files.createTempDirectory("mini-tools").toFile()
        val source = Files.createTempDirectory("mini-mounted-source").toFile()
        try {
            source.resolve("moru-app.json").writeText("""{"id":"mounted","name":"Mounted"}""")
            source.resolve("index.html").writeText("<html>First</html>")
            val repository = mockk<WorkspaceRepository>()
            coEvery { repository.getById("selected") } returns WorkspaceEntity("selected", "Selected", "/private/root", "READY", 0, 0)
            coEvery { repository.acquireRootfsFileLease("selected") } returns mockk(relaxed = true)
            coEvery { repository.resolveRootfsFile("selected", "/mnt/apps/tracker") } returns source
            val store = MiniAppStore(root)
            var checks = 0
            val tool = publishMiniAppTool("selected", repository, "/mnt/apps", store = { store },
                check = { checks++; buildJsonObject { put("loaded", true) } }, needsApproval = { false })
            suspend fun publish() = Json.parseToJsonElement((tool.execute(buildJsonObject { put("path", "tracker") }).single() as UIMessagePart.Text).text).jsonObject
            val first = publish()
            assertEquals(JsonPrimitive(false), first["updated"])
            assertEquals(JsonPrimitive(true), first["check"]!!.jsonObject["loaded"])
            store.storageSet("mounted", "saved", JsonPrimitive(42))
            source.resolve("index.html").writeText("<html>Updated</html>")
            assertEquals(JsonPrimitive(true), publish()["updated"])
            assertEquals(JsonPrimitive(42), store.storageGet("mounted", "saved"))
            assertEquals(2, checks)
            val brokenCheck = publishMiniAppTool("selected", repository, "/mnt/apps", store = { store },
                check = { error("No WebView") }, needsApproval = { false })
            val skipped = Json.parseToJsonElement((brokenCheck.execute(buildJsonObject { put("path", "tracker") }).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(JsonPrimitive(true), skipped["ok"])
            assertTrue(skipped["check"]!!.jsonObject.containsKey("skipped"))
            coVerify(exactly = 3) { repository.resolveRootfsFile("selected", "/mnt/apps/tracker") }
        } finally { root.deleteRecursively(); source.deleteRecursively() }
    }

    @Test fun workspacePathsNormalizeConfinedParentsAndRejectRootEscapes() {
        assertEquals("/workspace/tracker", miniAppWorkspacePath("tracker", "/workspace"))
        assertEquals("/workspace/chat/tracker", miniAppWorkspacePath("tracker", "chat"))
        assertEquals("/workspace/chat", miniAppWorkspacePath(null, "/workspace/chat"))
        assertEquals("/mnt/apps/tracker", miniAppWorkspacePath("tracker", "/mnt/apps"))
        assertEquals("/workspace/tracker", miniAppWorkspacePath("../tracker", "/workspace/chat"))
        assertEquals("/mnt/apps/project/app", miniAppWorkspacePath("../app", "/mnt/apps/project/build"))
        assertEquals("/outside", miniAppWorkspacePath("../outside", null))
        assertEquals("/workspace/tracker", miniAppWorkspacePath("./../tracker/.", "/workspace/chat"))
        listOf("../../outside", "/workspace/../../outside", "/../outside", "tracker\u0000").forEach {
            assertThrows(IllegalArgumentException::class.java) { miniAppWorkspacePath(it, null) }
        }
    }
}
