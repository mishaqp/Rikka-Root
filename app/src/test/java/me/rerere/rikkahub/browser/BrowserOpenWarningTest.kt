package me.rerere.rikkahub.browser

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.browserOpenTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BrowserOpenWarningTest {
    private val destination = "https://destination.example/open"
    private val token = "sk-abcdefghijklmnopqrstuvwxyz"

    @Before fun setUp() {
        // The WebView dispatch is the platform boundary. Keep the actual tool and
        // SensitiveContentDetector so these tests cover warning production and gating.
        mockkObject(BrowserController, BrowserControllerHandle)
        every { BrowserController.isBound() } returns true
    }

    @After fun tearDown() {
        unmockkObject(BrowserController, BrowserControllerHandle)
        BrowserController.clearTaskWindow()
    }

    private fun controllerResult(success: Boolean = true) = buildJsonObject {
        put("success", success)
        put("current_url", destination)
        put("title", "Destination")
    }

    private fun open(url: String): JsonObject = runBlocking {
        val parts = browserOpenTool(RuntimeEnvironment.getApplication()).execute(buildJsonObject { put("url", url) })
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `successful sensitive query explains its cause and required action in Russian`() {
        coEvery { BrowserControllerHandle.withController(any()) } returns controllerResult()

        val result = open("https://source.example/open?key=$token")

        assertEquals("true", result["success"]!!.jsonPrimitive.content)
        assertEquals(destination, result["current_url"]!!.jsonPrimitive.content)
        assertEquals("Destination", result["title"]!!.jsonPrimitive.content)
        val warning = result["warning"]!!.jsonPrimitive.content
        assertTrue("Warning must explain the cause in Russian: $warning", warning.contains("конфиденциаль", ignoreCase = true))
        assertTrue("Warning must tell the user what to confirm: $warning", warning.contains("Уточните"))
        assertTrue("Warning must retain the instruction against echoing the value: $warning", warning.contains("не повторяйте", ignoreCase = true))
        assertTrue("Detector category must remain available", warning.contains("api_key"))
        assertTrue("The actual destination must remain in the warning", warning.contains(destination))
    }

    @Test fun `ordinary queries and sensitive looking path retain successful output without warning`() {
        coEvery { BrowserControllerHandle.withController(any()) } returns controllerResult()

        for (url in listOf("https://source.example/open?q=weather", "https://source.example/$token")) {
            val result = open(url)
            assertEquals(controllerResult(), result)
            assertFalse(result.containsKey("warning"))
        }
    }

    @Test fun `unsuccessful open with sensitive query retains original result without warning`() {
        val failed = buildJsonObject { put("error", "browser_launch_failed"); put("recovery", "Повторите browser_open.") }
        coEvery { BrowserControllerHandle.withController(any()) } returns failed

        assertEquals(failed, open("https://source.example/open?key=$token"))
    }
}
