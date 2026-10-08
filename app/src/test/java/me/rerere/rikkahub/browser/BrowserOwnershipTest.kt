package me.rerere.rikkahub.browser

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import me.rerere.rikkahub.data.ai.tools.local.browserOpenTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BrowserOwnershipTest {
    private var view: WebView? = null

    @After fun tearDown() {
        BrowserController.unbindHeadless("chat-a")
        view?.let { BrowserController.unbind(it); it.destroy() }
        BrowserController.clearTaskWindow()
    }

    @Test fun `another chat cannot read or clear current headless browser`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        view = WebView(context)
        assertTrue(BrowserController.bindHeadless("chat-a", requireNotNull(view)))
        BrowserController.startTaskWindow()
        val caller = ToolInvocationContext(callerConversationId = "chat-b", isHeadless = true)
        for (name in listOf(BrowserToolDefaults.GET_TEXT, BrowserToolDefaults.CURRENT_URL, BrowserToolDefaults.DONE)) {
            val tool = requireNotNull(createBrowserTool(name, context, caller))
            val parts = tool.execute(Json.parseToJsonElement("{\"summary\":\"done\"}"))
            assertTrue(name, (parts.single() as UIMessagePart.Text).text.contains("browser_busy"))
            assertTrue(BrowserController.canAccess("chat-a"))
            assertFalse(BrowserController.canAccess("chat-b"))
            assertTrue(BrowserController.currentTaskStartedAt != null)
        }
    }

    @Test fun `only browser_open can claim manually opened browser and owner remains stable`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        view = WebView(context)
        BrowserController.bindForeground(requireNotNull(view))
        assertFalse(BrowserController.canAccess("chat-a"))
        val read = requireNotNull(createBrowserTool(BrowserToolDefaults.GET_TEXT, context,
            ToolInvocationContext(callerConversationId = "chat-a")))
        assertTrue((read.execute(Json.parseToJsonElement("{}"))[0] as UIMessagePart.Text).text.contains("browser_busy"))
        assertTrue(BrowserController.claimForeground("chat-a"))
        assertTrue(BrowserController.canAccess("chat-a"))
        assertFalse(BrowserController.claimForeground("chat-b"))
        assertFalse(BrowserController.canAccess(null))
    }

    @Test fun `model cannot navigate directly to script data or app private schemes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tool = browserOpenTool(context)
        for (url in listOf("javascript:document.cookie", " JaVaScRiPt:localStorage.getItem('password') ",
            "data:text/html,<script>document.cookie</script>", "file:///data/user/0/private/Cookies",
            "content://private/token", "intent://read-private")) {
            val result = tool.execute(buildJsonObject { put("url", url) })
            assertTrue(url, (result.single() as UIMessagePart.Text).text.contains("scheme_not_allowed"))
        }
    }
}
