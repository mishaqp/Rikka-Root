package me.rerere.rikkahub.browser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserScreenshotIoTest {
    private lateinit var context: CacheIoContext
    private lateinit var view: WebView

    @Before fun install() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = CacheIoContext(RuntimeEnvironment.getApplication())
        view = object : WebView(context) {
            override fun draw(canvas: Canvas) {
                check(Looper.myLooper() == Looper.getMainLooper()) { "WebView must draw on Main" }
                super.draw(canvas)
            }
        }
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(160, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(240, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 160, 240)
        // Robolectric's stub WebViewProvider.setFrame does not call View.setFrame.
        // Seed the viewport geometry normally supplied by Chromium; PNG generation is real.
        org.robolectric.util.ReflectionHelpers.setField(view, "mRight", 160)
        org.robolectric.util.ReflectionHelpers.setField(view, "mBottom", 240)
        assertEquals("Fixture viewport width", 160, view.width)
        assertEquals("Fixture viewport height", 240, view.height)
        context.forbidMainCacheAccess = true
    }

    @After fun close() {
        context.forbidMainCacheAccess = false
        BrowserController.unbindHeadless("png-test")
        BrowserController.unbind(view)
        BrowserController.clearTaskWindow()
        view.destroy()
        Dispatchers.resetMain()
    }

    @Test fun `browser screenshot writes a readable PNG without opening the cache on the UI thread`() = runBlocking {
        BrowserController.bindForeground(view, "png-test")
        val tool = requireNotNull(createBrowserTool(BrowserToolDefaults.SCREENSHOT, context,
            ToolInvocationContext(callerConversationId = "png-test")))
        val parts = tool.execute(buildJsonObject {})
        val output = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        assertFalse(output.toString(), output.containsKey("error"))
        assertEquals("true", output["success"]?.jsonPrimitive?.content)
        val path = requireNotNull(output["file_path"]?.jsonPrimitive?.content)
        assertReadablePng(File(path))
        assertTrue(context.cacheOpenedOffMain)
    }

    @Test fun `headless screenshot stream writes a readable PNG without opening the cache on the UI thread`() = runBlocking {
        assertTrue(BrowserController.bindHeadless("png-test", view))
        BrowserController.streamScreenshotIfHeadless("Проверка")
        val file = context.actualCacheDir.resolve("browser-stream").listFiles()?.singleOrNull()
        assertNotNull("Headless PNG was silently lost after attempting disk access on Main", file)
        assertReadablePng(requireNotNull(file))
        assertTrue(context.cacheOpenedOffMain)
    }

    private fun assertReadablePng(file: File) {
        assertTrue(file.isFile)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("Screenshot must contain actual PNG bytes", bitmap)
        requireNotNull(bitmap).let {
            assertEquals(160, it.width)
            assertEquals(240, it.height)
            it.recycle()
        }
    }

    /** Android getCacheDir may create the directory: treat this disk API as an IO boundary. */
    private class CacheIoContext(base: Context) : ContextWrapper(base) {
        val actualCacheDir: File = super.getCacheDir()
        var forbidMainCacheAccess = false
        @Volatile var cacheOpenedOffMain = false
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File {
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            if (forbidMainCacheAccess && onMain) error("Нельзя открывать кэш на главном потоке")
            if (!onMain) cacheOpenedOffMain = true
            return actualCacheDir
        }
    }
}
