package me.rerere.rikkahub.browser

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.browserCurrentUrlTool
import me.rerere.rikkahub.data.ai.tools.local.browserOpenTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, manifest = Config.NONE)
class BrowserLaunchFailureTest {
    @After fun clearTask() = BrowserController.clearTaskWindow()

    @Test fun `an accepted launch with no Activity binding explains the actual failure in Russian`() = runTest {
        val launched = mutableListOf<Intent>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { launched += intent }
        }
        assertFalse(BrowserController.isBound())
        val result = browserOpenTool(context).execute(buildJsonObject { put("url", "https://example.com") })
        val output = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject

        assertEquals(1, launched.size)
        assertEquals(BrowserActivity::class.java.name, launched.single().component?.className)
        assertEquals("https://example.com", launched.single().getStringExtra(BrowserActivity.EXTRA_INITIAL_URL))
        assertEquals("browser_launch_failed", output["error"]?.jsonPrimitive?.content)
        val recovery = output["recovery"]!!.jsonPrimitive.content
        assertTrue(recovery.contains("5 секунд"))
        assertTrue(recovery.contains("Браузер"))
        assertFalse(recovery.contains("overlay", ignoreCase = true))
        assertFalse(recovery.contains("разрешен", ignoreCase = true))
        assertEquals(5_000L, testScheduler.currentTime)
        assertFalse(BrowserController.isBound())
        assertEquals(null, BrowserController.currentTaskStartedAt)
    }

    @Test fun `a tool after a missing binding preserves its code and gives a Russian recovery`() = runTest {
        assertFalse(BrowserController.isBound())
        val result = browserCurrentUrlTool().execute(buildJsonObject {})
        val output = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject

        assertEquals("browser_not_open", output["error"]?.jsonPrimitive?.content)
        assertTrue(output["recovery"]!!.jsonPrimitive.content.contains("browser_open"))
        assertTrue(output["recovery"]!!.jsonPrimitive.content.contains("Браузер"))
        assertFalse(BrowserController.isBound())
    }

    @Test fun `launch exceptions preserve authored Russian reasons and do not expose Android exception text`() = runTest {
        val authored = "Открытие браузера недоступно на этом устройстве."
        val cases = listOf(
            IllegalStateException(authored) to authored,
            ActivityNotFoundException("private-token-and-file-path") to
                "Функция недоступна на устройстве или Android отклонил запрос.",
        )
        for ((failure, reason) in cases) {
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            val result = browserOpenTool(context).execute(buildJsonObject { put("url", "https://example.com") })
            val output = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("browser_launch_failed", output["error"]?.jsonPrimitive?.content)
            assertTrue(output["recovery"]!!.jsonPrimitive.content.startsWith(reason))
            assertFalse(output.toString().contains("private-token-and-file-path"))
            assertFalse(BrowserController.isBound())
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `launch cancellation still propagates without a misleading failure envelope`() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { throw CancellationException("cancelled") }
        }
        try {
            browserOpenTool(context).execute(buildJsonObject { put("url", "https://example.com") })
            throw AssertionError("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
        assertFalse(BrowserController.isBound())
    }
}
