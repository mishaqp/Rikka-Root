package me.rerere.rikkahub.ui.theme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.ChatBubbleStyleOverrides
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.QuickMessageSheetItem
import me.rerere.rikkahub.ui.components.ai.ToolsSheetSurface
import me.rerere.rikkahub.ui.components.ai.WorkspaceSelectRow
import me.rerere.rikkahub.ui.components.ui.LayeredSheetTile
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.setting.InterfacePageState
import me.rerere.rikkahub.ui.pages.setting.SettingInterfaceThemeAdvancedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayeredSheetTileRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val display = mutableStateOf(DisplaySetting())
    private var renderedScheme: ColorScheme? = null
    private var renderedSheetTile: Color? = null
    private val observedContentColors = mutableMapOf<String, Color>()

    @Test fun fourCombinationsUpdateOnlyTheirOwnProductionRoles() {
        compose.setContent {
            RikkahubThemeContent(PresetThemes.first().getColorScheme(false), darkTheme = false,
                displaySetting = display.value, content = {
                val scheme = MaterialTheme.colorScheme
                SideEffect { renderedScheme = scheme }
                Column(Modifier.requiredWidth(220.dp).background(Color.Magenta)) {
                    TopAppBar(
                        title = {},
                        colors = CustomColors.topBarColors,
                        modifier = Modifier.fillMaxWidth().testTag("ordinary_top_bar"),
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                        modifier = Modifier.requiredSize(40.dp).testTag("ordinary_card"),
                    ) {}
                    LayeredSheetTile(selected = false,
                        modifier = Modifier.requiredSize(40.dp).testTag("sheet_tile")) {
                        ContentColorProbe("sheet_tile")
                    }
                    LayeredSheetTile(selected = true,
                        modifier = Modifier.requiredSize(40.dp).testTag("selected_sheet_tile")) {
                        ContentColorProbe("selected_sheet_tile")
                    }
                    LayeredSheetTile(selected = false, onClick = {}, enabled = false,
                        modifier = Modifier.requiredSize(40.dp).testTag("disabled_sheet_tile")) {
                        ContentColorProbe("disabled_sheet_tile")
                    }
                    ToolsSheetSurface(onClick = {},
                        modifier = Modifier.requiredSize(40.dp).testTag("attachment_sheet_tile")) {}
                    QuickMessageSheetItem(title = "", message = "", onClick = {},
                        modifier = Modifier.requiredSize(40.dp).testTag("quick_message_sheet_tile"))
                    WorkspaceSelectRow(title = "", selected = false, onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag("workspace_sheet_tile"))
                    WorkspaceSelectRow(title = "", selected = true, onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag("selected_workspace_sheet_tile"))
                }
            })
        }

        val snapshots = mutableMapOf<Pair<Boolean, Boolean>, Snapshot>()
        for (layeredSurfaces in listOf(false, true)) {
            for (layeredSheetTiles in listOf(false, true)) {
                compose.runOnIdle {
                    display.value = DisplaySetting(useLayeredSurfaces = layeredSurfaces,
                        useLayeredSheetTiles = layeredSheetTiles)
                }
                compose.waitForIdle()
                val scheme = requireNotNull(renderedScheme)
                snapshots[layeredSurfaces to layeredSheetTiles] = Snapshot(
                    external = ExternalRoles(scheme.background, scheme.surface, scheme.surfaceContainerLow,
                        scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.primaryContainer,
                        scheme.onSurface, scheme.onPrimaryContainer),
                    contentColors = compose.runOnIdle { observedContentColors.toMap() },
                    tile = pixel("sheet_tile"),
                    selected = pixel("selected_sheet_tile"),
                    disabled = pixel("disabled_sheet_tile"),
                    ordinaryCard = pixel("ordinary_card"),
                    topBar = pixel("ordinary_top_bar"),
                    attachment = pixel("attachment_sheet_tile"),
                    quickMessage = pixel("quick_message_sheet_tile"),
                    workspace = pixel("workspace_sheet_tile"),
                    selectedWorkspace = pixel("selected_workspace_sheet_tile"),
                )
            }
        }

        for (layeredSurfaces in listOf(false, true)) {
            val off = requireNotNull(snapshots[layeredSurfaces to false])
            val on = requireNotNull(snapshots[layeredSurfaces to true])
            assertEquals("Sheet tiles must not mutate the external palette", off.external, on.external)
            assertEquals("Ordinary cards must keep their color", off.ordinaryCard, on.ordinaryCard)
            assertEquals("Top bars must keep their color", off.topBar, on.topBar)
            assertEquals("Selected tiles keep their existing role", off.selected, on.selected)
            assertEquals("Disabled tiles keep their existing state", off.disabled, on.disabled)
            assertEquals("Selected workspaces keep their existing role", off.selectedWorkspace, on.selectedWorkspace)
            assertEquals("Sheet layering keeps selected, unselected and disabled content colors",
                off.contentColors, on.contentColors)
            assertEquals(off.external.onSurface, off.contentColors.getValue("sheet_tile"))
            assertEquals(off.external.onPrimaryContainer, off.contentColors.getValue("selected_sheet_tile"))
            assertNotEquals("The real sheet tile must react", off.tile, on.tile)
            assertEquals(off.external.surface, off.tile)
            assertEquals(off.external.primaryContainer, off.selected)
            assertEquals("Attachment rows restore their own default", off.external.surfaceContainerLow, off.attachment)
            assertEquals("Quick messages restore their own default", off.external.surfaceContainer, off.quickMessage)
            assertEquals("Unselected workspaces restore transparency", Color.Magenta, off.workspace)
            assertEquals(off.external.surfaceContainerHigh, off.selectedWorkspace)
            assertEquals("Attachment rows use only the active sheet role", on.tile, on.attachment)
            assertEquals("Quick messages use only the active sheet role", on.tile, on.quickMessage)
            assertEquals("Unselected workspaces use only the active sheet role", on.tile, on.workspace)
        }
        assertNotEquals("Layered surfaces still own their independent effect",
            snapshots[false to false]?.external?.surfaceContainerLow,
            snapshots[true to false]?.external?.surfaceContainerLow)
        assertNotEquals(snapshots[false to false]?.external?.surfaceContainerHigh,
            snapshots[true to false]?.external?.surfaceContainerHigh)
    }

    @Test fun sheetCallbacksSurviveTogglingAndDisabledTilesRejectTouches() {
        val callbacks = mutableMapOf<String, Int>()
        fun record(key: String) { callbacks[key] = callbacks.getOrDefault(key, 0) + 1 }
        compose.setContent {
            RikkahubThemeContent(PresetThemes.first().getColorScheme(false), darkTheme = false,
                displaySetting = display.value, content = {
                Column(Modifier.requiredWidth(220.dp)) {
                    LayeredSheetTile(selected = false, onClick = { record("tile") },
                        modifier = Modifier.requiredSize(40.dp).testTag("tile")) {}
                    LayeredSheetTile(selected = true, onClick = { record("selected") },
                        modifier = Modifier.requiredSize(40.dp).testTag("selected")) {}
                    LayeredSheetTile(selected = false, enabled = false, onClick = { record("disabled") },
                        modifier = Modifier.requiredSize(40.dp).testTag("disabled")) {}
                    ToolsSheetSurface(onClick = { record("attachment") },
                        modifier = Modifier.requiredSize(40.dp).testTag("attachment")) {}
                    QuickMessageSheetItem(title = "", message = "", onClick = { record("quick") },
                        modifier = Modifier.requiredSize(40.dp).testTag("quick"))
                    WorkspaceSelectRow(title = "", selected = false, onClick = { record("workspace") },
                        modifier = Modifier.fillMaxWidth().testTag("workspace"))
                }
            })
        }
        for ((index, enabled) in listOf(false, true, false).withIndex()) {
            compose.runOnIdle { display.value = display.value.copy(useLayeredSheetTiles = enabled) }
            compose.waitForIdle()
            for (tag in listOf("tile", "selected", "attachment", "quick", "workspace")) {
                compose.onNodeWithTag(tag).assertIsEnabled().performTouchInput { click() }
                compose.runOnIdle { assertEquals(index + 1, callbacks[tag]) }
            }
            compose.onNodeWithTag("disabled").assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(0, callbacks.getOrDefault("disabled", 0)) }
        }
    }

    @Composable
    private fun ContentColorProbe(tag: String) {
        val color = LocalContentColor.current
        SideEffect { observedContentColors[tag] = color }
    }

    @Test fun realSwitchesImmediatelyUpdateThemeWithoutRewritingOtherSettings() {
        val initial = Settings(displaySetting = DisplaySetting(
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
            userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(backgroundArgbLight = 0xFF123456L),
            chatBackgroundImagePath = "file:///data/user/0/app/files/upload/wall.jpg",
        ))
        val settings = mutableStateOf(initial)
        val base = PresetThemes.first().getColorScheme(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides settings.value,
                LocalNavController provides Navigator(mutableListOf()),
            ) {
                RikkahubThemeContent(base, darkTheme = false, displaySetting = settings.value.displaySetting, content = {
                    val scheme = MaterialTheme.colorScheme
                    val sheetTile = scheme.sheetTileContainer
                    SideEffect {
                        renderedScheme = scheme
                        renderedSheetTile = sheetTile
                    }
                    SettingInterfaceThemeAdvancedPage(InterfacePageState(settings.value, { transform ->
                        settings.value = transform(settings.value)
                    }, navigate = {}))
                })
            }
        }
        val initialExternalContainer = requireNotNull(renderedScheme).surfaceContainer
        val initialSheetTile = requireNotNull(renderedSheetTile)

        compose.onNodeWithTag("ui_useLayeredSheetTiles").performScrollTo().performClick()
        compose.waitForIdle()
        val sheetEnabled = settings.value
        assertEquals(initial.displaySetting.copy(useLayeredSheetTiles = true), sheetEnabled.displaySetting)
        assertEquals(initial.copy(displaySetting = sheetEnabled.displaySetting), sheetEnabled)
        assertEquals(initialExternalContainer, requireNotNull(renderedScheme).surfaceContainer)
        assertNotEquals(initialSheetTile, renderedSheetTile)
        assertEquals(effectiveDisplayColors(base, false, false, sheetEnabled.displaySetting).sheetTileContainer,
            renderedSheetTile)

        compose.onNodeWithTag("ui_useLayeredSurfaces").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(sheetEnabled.displaySetting.copy(useLayeredSurfaces = true), settings.value.displaySetting)
        assertEquals(initialExternalContainer, requireNotNull(renderedScheme).surfaceContainer)
        assertEquals(effectiveDisplayColors(base, false, false, settings.value.displaySetting).sheetTileContainer,
            renderedSheetTile)
    }

    private fun pixel(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private data class ExternalRoles(
        val background: Color,
        val surface: Color,
        val surfaceContainerLow: Color,
        val surfaceContainer: Color,
        val surfaceContainerHigh: Color,
        val primaryContainer: Color,
        val onSurface: Color,
        val onPrimaryContainer: Color,
    )

    private data class Snapshot(
        val external: ExternalRoles,
        val contentColors: Map<String, Color>,
        val tile: Color,
        val selected: Color,
        val disabled: Color,
        val ordinaryCard: Color,
        val topBar: Color,
        val attachment: Color,
        val quickMessage: Color,
        val workspace: Color,
        val selectedWorkspace: Color,
    )
}
