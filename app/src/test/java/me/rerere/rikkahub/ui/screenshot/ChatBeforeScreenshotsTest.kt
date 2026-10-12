package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.espresso.Espresso.pressBack
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File
import me.rerere.rikkahub.R
import ru.noties.jlatexmath.JLatexMathAndroid

/** Captures the production ChatPage, not a recreation of its layout. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ChatBeforeScreenshotsTest internal constructor(private val theme: ScreenshotThemeMode, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var imageLoader: ImageLoader

    @OptIn(DelicateCoilApi::class)
    @Before fun installBoundaries() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric's plain Application omits this library's startup ContentProvider.
        JLatexMathAndroid.init(context)
        val image = File("/tmp/rikka-chat-audit/audit-image.png")
        requireNotNull(image.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(58, 97, 149))
            drawCircle(60f, 36f, 24f, Paint().apply { color = Color.rgb(255, 207, 76) })
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Keep the real Coil decoder/render path; only remote favicon transport is fixed.
        val offlineClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Screenshot fixture: offline").body(ByteArray(0).toResponseBody()).build()
        }.build()
        imageLoader = ImageLoader.Builder(context).components {
            // ImageDecoder is not implemented for this Robolectric SDK; decode the real PNG.
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { offlineClient }))
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        val decoded = runBlocking { imageLoader.execute(ImageRequest.Builder(context).data(image.toURI().toString()).build()) }
        assertTrue("The fixture attachment must decode through real Coil: ${(decoded as? ErrorResult)?.throwable}", decoded is SuccessResult)
        fixture = ChatBeforeFixture(image)
        fixture.install()
    }
    @OptIn(DelicateCoilApi::class)
    @After fun uninstallBoundaries() {
        if (::fixture.isInitialized) fixture.close()
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        SingletonImageLoader.reset()
    }

    @Test fun productionChatBeforeRedesign() {
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("chat_before")) { fixture.Content() }
            }
        }
        compose.waitForIdle()
        val list = compose.onNode(SemanticsMatcher("visible main chat LazyColumn") {
            it.config.contains(SemanticsActions.ScrollToIndex) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                it.boundsInRoot.left >= 0 && it.boundsInRoot.top > 0
        })
        fixture.viewports.forEach { (name, index) ->
            list.performScrollToIndex(index)
            compose.waitForIdle()
            if (name in listOf("terminal", "automation")) {
                val count = if (name == "terminal") 1 else 2
                val label = RuntimeEnvironment.getApplication().getString(R.string.chain_of_thought_show_more_steps, count)
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
            }
            if (name == "reasoning") {
                val label = RuntimeEnvironment.getApplication().getString(R.string.deep_thinking_seconds, 2f)
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
            }
            capture(name)
            if (name == "terminal") {
                captureDetails("root_exec", "tool_success_details")
                captureDetails("termux_run_command", "tool_error_details")
            }
            if (name == "generic") captureDetails("unregistered_fixture_tool", "tool_plaintext_details")
        }
        fixture.showError()
        list.performScrollToIndex(fixture.conversation.messageNodes.size)
        compose.waitForIdle()
        capture("provider_error")
    }

    private fun captureDetails(tool: String, name: String) {
        val label = RuntimeEnvironment.getApplication().getString(R.string.chat_message_tool_call_generic, tool)
        // A later Pending root call can also be precomposed below this viewport.
        compose.onAllNodesWithText(label).onFirst().performClick()
        compose.waitForIdle()
        // Material's bottom sheet has its own window and is outside the ChatPage Surface.
        capture(name, includeWindows = true)
        pressBack()
        compose.waitForIdle()
    }

    private fun capture(name: String, includeWindows: Boolean = false) {
        val record = System.getProperty("chat.audit.record").toBoolean()
        // Allow explicit review captures outside the committed baselines. Verification
        // always uses the normal paths, including Daily Build's global record mode.
        val baselineDirectory = if (record) {
            System.getProperty("chat.audit.record.dir", "src/test/screenshots/chat-before")
        } else {
            "src/test/screenshots/chat-before"
        }
        val baseline = File(baselineDirectory, "${theme.fileName}_font${fontScale}/$name.png")
        val options = RoborazziOptions(taskType = if (record) RoborazziTaskType.Record else RoborazziTaskType.Verify)
        requireNotNull(baseline.parentFile).mkdirs()
        if (includeWindows) {
            captureScreenRoboImage(baseline.absolutePath, roborazziOptions = options)
        } else {
            compose.onNodeWithTag("chat_before").captureRoboImage(baseline.absolutePath, roborazziOptions = options)
        }
        assertTrue("Missing committed baseline: $baseline", baseline.isFile && baseline.length() > 0)
        val artifacts = File(System.getProperty("ui.screenshots.dir", "build/outputs/roborazzi"), "chat-before/${theme.fileName}_font${fontScale}")
        artifacts.mkdirs()
        baseline.copyTo(File(artifacts, "$name.png"), overwrite = true)
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}_font{1}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme -> listOf(1f, 1.3f).map { arrayOf(theme, it) } }
    }
}
