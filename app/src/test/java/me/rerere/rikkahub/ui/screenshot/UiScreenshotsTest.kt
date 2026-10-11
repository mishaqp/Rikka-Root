package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

internal enum class ScreenshotScreen(val fileName: String) {
    ChatDrawer("chat_drawer"), MainSettings("main_settings"), AssistantTools("assistant_tools"),
}

/** The required 3 screens × 4 palettes × 2 font scales at a 411 dp phone width. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(
    sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotsTest internal constructor(
    private val screen: ScreenshotScreen,
    private val theme: ScreenshotThemeMode,
    private val fontScale: Float,
) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capturesProductionScreen() {
        var drawerBackground = Color.Unspecified
        var selectedBackground = Color.Unspecified
        var foreground = Color.Unspecified
        var supportingForeground = Color.Unspecified
        var selectedForeground = Color.Unspecified
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val colors = MaterialTheme.colorScheme
                drawerBackground = if (theme.amoled) colors.background else colors.surfaceContainerLow
                selectedBackground = colors.secondaryContainer
                foreground = colors.onSurface
                supportingForeground = colors.onSurfaceVariant
                selectedForeground = colors.onSecondaryContainer
                Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("screenshot")) {
                    when (screen) {
                        ScreenshotScreen.ChatDrawer -> DrawerScreenshotFixture()
                        ScreenshotScreen.MainSettings -> MainSettingsScreenshotFixture()
                        ScreenshotScreen.AssistantTools -> AssistantToolsScreenshotFixture()
                    }
                }
            }
        }
        compose.waitForIdle()
        if (theme == ScreenshotThemeMode.Dynamic) {
            assertTrue("Dynamic scheme must read the frozen Android system roles",
                FixedSeedDynamicColors.observedResources.containsAll(listOf(
                    "system_primary_light", "system_surface_container_low_light", "system_primary_fixed",
                )))
        }
        val outputDirectory = File(System.getProperty("ui.screenshots.dir", "build/outputs/roborazzi"))
        outputDirectory.mkdirs()
        val output = File(outputDirectory, "${screen.fileName}_${theme.fileName}_font${fontScale}.png")
        compose.onNodeWithTag("screenshot").captureRoboImage(output.absolutePath)
        if (System.getProperty("roborazzi.test.record").toBoolean()) {
            assertTrue("Screenshot was not recorded: $output", output.isFile && output.length() > 0)
        }
        if (screen == ScreenshotScreen.ChatDrawer) {
            val drawer = compose.onNodeWithTag("chat_drawer_sheet").fetchSemanticsNode().boundsInRoot
            assertEquals("411dp phone drawer must be capped at 320dp", 320f, drawer.width, .5f)
            val headerBounds = compose.onNodeWithTag("chat_drawer_header").fetchSemanticsNode().boundsInRoot
            assertEquals("Header must start on the shared 16dp grid", drawer.left + 16f, headerBounds.left, .5f)
            val titleBounds = compose.onNodeWithText(drawerFixtureTitles.first(), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("Chat titles must start on the shared 16dp grid", drawer.left + 16f, titleBounds.left, .5f)
            val dateBounds = compose.onNodeWithText("Сегодня", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Date headings must share the chat-title grid", titleBounds.left, dateBounds.left, .5f)
            if (fontScale == 1f) {
                val header = compose.onNodeWithTag("chat_drawer_header").fetchSemanticsNode().boundsInRoot
                assertTrue("Default header exceeds 72dp: ${header.height}", header.height <= 72.5f)
                val footer = compose.onNodeWithTag("chat_drawer_bottom_bar").fetchSemanticsNode().boundsInRoot
                val rows = compose.onAllNodes(SemanticsMatcher("conversation row") { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("chat_drawer_conversation_")
                }).fetchSemanticsNodes()
                val fullyVisible = rows.count { node ->
                    val bounds = node.boundsInRoot
                    bounds.height >= 55.5f && bounds.top >= 0 && bounds.bottom <= footer.top
                }
                assertTrue("Only $fullyVisible full chats fit on the phone", fullyVisible >= 8)
            }
            listOf(foreground to drawerBackground, supportingForeground to drawerBackground,
                selectedForeground to selectedBackground).forEach { (text, container) ->
                assertTrue("Drawer text contrast is below 4.5:1 in $theme",
                    ColorUtils.calculateContrast(text.toArgb(), container.toArgb()) >= 4.5)
            }
            if (System.getProperty("roborazzi.test.record").toBoolean()) {
                val bitmap = ImageIO.read(output)
                val footer = compose.onNodeWithTag("chat_drawer_bottom_bar").fetchSemanticsNode().boundsInRoot
                assertEquals("Footer must retain the drawer background in every theme",
                    drawerBackground.toArgb(), bitmap.getRGB((footer.left + 2).toInt(), (footer.top + 2).toInt()))
                val first = compose.onNodeWithTag("chat_drawer_conversation_20000000-0000-0000-0000-000000000001")
                    .fetchSemanticsNode().boundsInRoot
                assertEquals("Unselected row must show the drawer background, with no delete underlay",
                    drawerBackground.toArgb(), bitmap.getRGB(first.center.x.toInt(), (first.top + 2).toInt()))
                val selected = compose.onNodeWithTag("chat_drawer_conversation_20000000-0000-0000-0000-000000000002")
                    .fetchSemanticsNode().boundsInRoot
                assertEquals("Selected row uses the theme's drawer pill",
                    selectedBackground.toArgb(), bitmap.getRGB(selected.center.x.toInt(), (selected.top + 2).toInt()))
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}_{1}_font{2}")
        fun cases(): List<Array<Any>> = ScreenshotScreen.entries.flatMap { screen ->
            ScreenshotThemeMode.entries.flatMap { theme ->
                listOf(1f, 1.3f).map { fontScale -> arrayOf(screen, theme, fontScale) }
            }
        }
    }
}
