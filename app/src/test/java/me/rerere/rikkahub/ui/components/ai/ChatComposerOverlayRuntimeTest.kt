package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.asr.ASRState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.screenshot.ChatBeforeFixture
import me.rerere.rikkahub.ui.screenshot.FixedSeedDynamicColors
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.ScreenshotThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Tests the production input, including its translucent user setting, over hostile content. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatComposerOverlayRuntimeTest internal constructor(
    private val theme: ScreenshotThemeMode,
    private val width: Int,
    private val font: Float,
) {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture

    @Before fun install() {
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
    }
    @After fun close() = fixture.close()

    @Test fun compactAndMultilineInputBlockContentAcrossTheirEntirePanel() {
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(
            chatInputBackgroundOpacityLight = .2f, chatInputBackgroundOpacityDark = .2f))
        val input = ChatInputState()
        val asr = mockk<CustomAsrState>(relaxed = true)
        every { asr.state } returns MutableStateFlow(ASRState())
        var expected = Color.Unspecified
        compose.setContent {
            ScreenshotTheme(theme, font) {
                expected = MaterialTheme.colorScheme.background
                CompositionLocalProvider(LocalASRState provides asr, LocalSettings provides settings,
                    LocalToaster provides rememberToasterState()) {
                    Box(Modifier.requiredSize(width.dp, 700.dp).background(Color.Magenta).testTag("opaque_input_root")) {
                        ChatInput(input, false, settings, ScreenshotFixtures.assistant, ScreenshotFixtures.model,
                            HazeState(), {}, modifier = Modifier.align(Alignment.BottomCenter),
                            onUpdateChatModel = {}, onUpdateAssistant = {}, onUpdateSearchService = {},
                            onMoreClick = {}, onCancelClick = {}, onSendClick = {}, onLongSendClick = {})
                    }
                }
            }
        }
        listOf("", "Первая строка\nВторая строка\nТретья строка\nЧетвёртая строка").forEach { draft ->
            compose.runOnIdle { input.setMessageText(draft) }
            compose.waitForIdle()
            val composer = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithTag("opaque_input_root").fetchSemanticsNode().boundsInRoot
            assertTrue("The complete composer remains inside a ${width}dp phone", composer.left >= root.left && composer.right <= root.right)
            val image = compose.onNodeWithTag("opaque_input_root").captureToImage().toPixelMap()
            // Outside the rounded inner bubble but inside the panel. Text behind it must not bleed through.
            val pixel = image[1, (composer.center.y - root.top).roundToInt()]
            assertEquals("The panel must cover the underlying magenta content", expected.red, pixel.red, .02f)
            assertEquals(expected.green, pixel.green, .02f)
            assertEquals(expected.blue, pixel.blue, .02f)
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}_width{1}_font{2}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme ->
                listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { font -> arrayOf(theme, width, font) } }
            }
    }
}
