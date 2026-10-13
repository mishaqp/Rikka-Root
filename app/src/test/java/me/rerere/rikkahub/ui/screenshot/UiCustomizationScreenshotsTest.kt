package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.dokar.sonner.rememberToasterState
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.GoogleFontEntry
import me.rerere.rikkahub.ui.pages.setting.GoogleFontsPickerContent
import me.rerere.rikkahub.ui.pages.setting.InterfaceSettingsContent
import me.rerere.rikkahub.ui.pages.setting.InterfaceSettingsPageId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

internal data class UiCustomizationScreenshotScene(
    val name: String,
    val page: InterfaceSettingsPageId? = null,
    val drawer: DrawerScreenshotMode? = null,
    val bottom: Boolean = false,
) {
    override fun toString() = name
}

/** Every scene mounts production content; only persisted data and external I/O are fixed. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class UiCustomizationScreenshotsTest internal constructor(
    private val scene: UiCustomizationScreenshotScene,
    private val theme: ScreenshotThemeMode,
    private val fontScale: Float,
) {
    @get:Rule val compose = createComposeRule()
    private val boundaries = UiCustomizationScreenshotBoundaries()
    private var chat: ChatBeforeFixture? = null
    private var previousFontScale = 1f
    private val snapshots get() = ChatStage1ScreenshotPaths(theme.fileName, fontScale)

    @Before fun installOfflineBoundaries() {
        previousFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        boundaries.install()
        compose.registerIdlingResource(boundaries.imagesIdle)
    }

    @After fun uninstallOfflineBoundaries() {
        try {
            compose.unregisterIdlingResource(boundaries.imagesIdle)
            chat?.close()
            boundaries.close()
        } finally {
            RuntimeEnvironment.setFontScale(previousFontScale)
        }
    }

    @Test fun capturesProductionInterface() {
        when {
            scene.drawer != null -> captureDrawer(scene.drawer)
            scene.page != null -> captureSettings(scene.page)
            else -> captureWallpaperChat()
        }
    }

    private fun captureDrawer(mode: DrawerScreenshotMode) {
        val state = DrawerScreenshotState(mode)
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("ui_customization")) {
                    DrawerScreenshotFixture(state)
                }
            }
        }
        compose.waitForIdle()
        val drawer = compose.onNodeWithTag("chat_drawer_sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Drawer keeps the 75% phone width", 411f * .75f, drawer.width, .5f)
        assertEquals("Drawer keeps the full viewport height", 891f, drawer.height, .5f)
        when (mode) {
            DrawerScreenshotMode.Normal -> {
                compose.onNodeWithTag("chat_drawer_search_row").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_header").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_bottom_bar").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(2)}").assertIsSelected()
                compose.onNodeWithText("Сегодня", useUnmergedTree = true).assertIsDisplayed()
            }
            DrawerScreenshotMode.Search -> {
                compose.onNodeWithTag("chat_drawer_topic_search").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(1)}").assertDoesNotExist()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(2)}").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(3)}").assertIsDisplayed()
            }
            DrawerScreenshotMode.MultiSelect -> {
                compose.onNodeWithTag("chat_drawer_selection_header").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_selection_actions").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_search_row").assertDoesNotExist()
                compose.onNodeWithTag("chat_drawer_bottom_bar").assertDoesNotExist()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(1)}").assertIsSelected()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(2)}").assertIsSelected()
            }
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(drawerFixtureTitles[1], useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals("Production drawer title uses the actual requested font scale", fontScale,
            titleLayouts.single().layoutInput.density.fontScale, .001f)
        assertEquals("Moru keeps a single title line per conversation", 1, titleLayouts.single().layoutInput.maxLines)
        capture(scene.name)
        // Interactions happen after the stable frame so ripple timing cannot alter its baseline.
        val context = RuntimeEnvironment.getApplication()
        when (mode) {
            DrawerScreenshotMode.Normal -> {
                compose.onNodeWithContentDescription("Новый чат").performClick()
                compose.runOnIdle { assertEquals("Production new-chat control invokes its callback", 1, state.newChatCalls) }
                compose.onNodeWithContentDescription(context.getString(R.string.moru_drawer_search_messages)).performClick()
                compose.runOnIdle { assertEquals(Screen.MessageSearch, state.lastDestination) }
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(1)}").performClick()
                compose.runOnIdle { assertEquals(drawerFixtureId(1), state.currentId) }
            }
            DrawerScreenshotMode.Search -> {
                compose.onNodeWithTag("chat_drawer_topic_search").performTextReplacement("несуществующее название")
                compose.runOnIdle { assertEquals("Topic search invokes its real query callback", "несуществующее название", state.query) }
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(2)}").assertDoesNotExist()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(3)}").assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.moru_drawer_no_search_results)).assertIsDisplayed()
                compose.onNodeWithContentDescription(context.getString(R.string.moru_drawer_clear_search)).performClick()
                compose.runOnIdle { assertEquals("", state.query) }
                compose.onNodeWithText(context.getString(R.string.moru_drawer_no_search_results)).assertDoesNotExist()
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(1)}").assertIsDisplayed()
            }
            DrawerScreenshotMode.MultiSelect -> {
                compose.onNode(hasText("Закрепить") and hasClickAction()).performClick()
                compose.onNode(hasText(context.getString(R.string.moru_drawer_move_selected)) and hasClickAction()).performClick()
                compose.onNode(hasText(context.getString(R.string.chat_page_delete)) and hasClickAction()).performClick()
                compose.runOnIdle {
                    assertEquals(1, state.pinCalls); assertEquals(1, state.moveCalls); assertEquals(1, state.deleteCalls)
                }
                compose.onNodeWithTag("chat_drawer_conversation_${drawerFixtureId(1)}").performClick()
                compose.runOnIdle { assertEquals(setOf(drawerFixtureId(2)), state.selectedIds) }
                compose.onNodeWithContentDescription(context.getString(R.string.moru_drawer_exit_selection)).performClick()
                compose.onNodeWithTag("chat_drawer_search_row").assertIsDisplayed()
                compose.onNodeWithTag("chat_drawer_selection_actions").assertDoesNotExist()
            }
        }
    }

    private fun captureSettings(page: InterfaceSettingsPageId) {
        val settings = mutableStateOf(settingsFor(page))
        val selectedGoogleFont = mutableStateOf<GoogleFontEntry?>(null)
        var updates = 0
        var refreshes = 0
        var destination: Screen? = null
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val toaster = rememberToasterState()
                CompositionLocalProvider(LocalSettings provides settings.value, LocalToaster provides toaster) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("ui_customization")) {
                        if (page == InterfaceSettingsPageId.GoogleFonts) {
                            GoogleFontsPickerContent(googleFontsCatalog, selected = selectedGoogleFont.value,
                                onRefresh = { refreshes++ }, onSelect = { selectedGoogleFont.value = it })
                        } else {
                            InterfaceSettingsContent(page, settings.value, onUpdateSettings = { transform ->
                                settings.value = transform(settings.value)
                                updates++
                            }, onNavigate = { destination = it })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("ui_settings_page").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Settings page keeps the phone width", 411f, viewport.width, .5f)
        assertEquals("Settings page keeps the viewport height", 891f, viewport.height, .5f)
        compose.onNodeWithTag("ui_settings_list").assertIsDisplayed()
        val title = RuntimeEnvironment.getApplication().getString(page.titleRes)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(title, useUnmergedTree = true).onFirst().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Actual production heading uses requested font scale", fontScale,
            layouts.single().layoutInput.density.fontScale, .001f)
        assertTrue("Settings heading must not lose lines at $fontScale", !layouts.single().didOverflowHeight)
        if (scene.bottom) {
            compose.onNodeWithTag("ui_settings_list").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue("Long settings page remains scrollable", scroll(0f, 100_000f))
            }
            compose.waitForIdle()
        }
        // Publish the actual frame before layout assertions so a failed geometry check
        // still leaves the image needed to diagnose it.
        capture(scene.name)
        if (page == InterfaceSettingsPageId.Themes && scene.bottom) {
            listOf("import", "create").forEach { action ->
                val button = compose.onNodeWithTag("ui_theme_$action").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Theme $action button stays within the viewport at $fontScale",
                    button.left >= viewport.left && button.right <= viewport.right && button.top >= viewport.top && button.bottom <= viewport.bottom)
                val label = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("ui_theme_${action}_label", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
                assertEquals("Theme $action label remains one complete line at $fontScale", 1, label.single().lineCount)
                val measured = label.single()
                val labelBounds = compose.onNodeWithTag("ui_theme_${action}_label", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val detail = "Theme $action label at $fontScale: " +
                    "widthOverflow=${measured.didOverflowWidth}, heightOverflow=${measured.didOverflowHeight}, " +
                    "size=${measured.size}, paragraph=${measured.multiParagraph.width}x${measured.multiParagraph.height}, " +
                    "constraints=${measured.layoutInput.constraints}, label=$labelBounds, button=$button, " +
                    "line=${measured.getLineLeft(0)}..${measured.getLineRight(0)}, " +
                    "ellipsis=${measured.isLineEllipsized(0)}, end=${measured.getLineEnd(0)}, " +
                    "characters=${measured.layoutInput.text.length}"
                // Intrinsic Text can keep a paragraph as wide as the parent's max constraint
                // while its measured label wraps tightly around the complete single line.
                // didOverflowWidth compares those two container widths, not drawn text.
                assertTrue("Theme $action label must not clip vertically: $detail", !measured.didOverflowHeight)
                assertTrue("Theme $action label must not elide characters: $detail", !measured.isLineEllipsized(0))
                assertEquals("Theme $action label starts at its first character: $detail", 0, measured.getLineStart(0))
                assertEquals("Theme $action label draws every character: $detail", measured.layoutInput.text.length, measured.getLineEnd(0))
                assertTrue("Theme $action label stays inside its button: $detail", labelBounds.left >= button.left &&
                    labelBounds.right <= button.right && labelBounds.top >= button.top && labelBounds.bottom <= button.bottom)
                assertTrue("Theme $action complete line fits the measured label: $detail",
                    measured.getLineLeft(0) >= -.5f && measured.getLineRight(0) <= labelBounds.width + .5f)
                for (offset in measured.layoutInput.text.indices) {
                    val character = measured.getBoundingBox(offset)
                    assertTrue("Theme $action character $offset fits its label and button: $character; $detail",
                        character.left >= -.5f && character.right <= labelBounds.width + .5f &&
                            character.top >= -.5f && character.bottom <= labelBounds.height + .5f &&
                            labelBounds.left + character.left >= button.left - .5f &&
                            labelBounds.left + character.right <= button.right + .5f &&
                            labelBounds.top + character.top >= button.top - .5f &&
                            labelBounds.top + character.bottom <= button.bottom + .5f)
                }
            }
        }
        if (scene.bottom) {
            compose.onNodeWithTag("ui_settings_list").performScrollToIndex(0)
            compose.waitForIdle()
        }
        val switchRow = SemanticsMatcher("production settings switch row") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("ui_") && node.config.contains(SemanticsActions.OnClick) &&
                node.config.contains(SemanticsProperties.Role) && node.config[SemanticsProperties.Role] == Role.Switch
        }
        val switches = compose.onAllNodes(switchRow).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }
        if (switches.isNotEmpty()) {
            val before = settings.value
            val tag = switches.first().config.getOrElse(SemanticsProperties.TestTag) { "" }
            compose.onNodeWithTag(tag).performClick()
            compose.runOnIdle {
                assertTrue("Visible production switch writes Settings", updates > 0)
                assertTrue("Visible production switch changes its actual value", before != settings.value)
            }
        } else if (page == InterfaceSettingsPageId.Hub) {
            compose.onNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.ui_display_settings_page_theme_settings_title)) and hasClickAction()).performClick()
            compose.runOnIdle { assertNotNull("Interface navigation invokes its real destination callback", destination) }
        } else if (page == InterfaceSettingsPageId.Language) {
            compose.onNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.ui_moru_language_russian)) and hasClickAction()).performClick()
            compose.runOnIdle {
                assertTrue("Language selection commits Settings", updates > 0)
                assertEquals("Language row persists its actual app locale", "ru", settings.value.displaySetting.appLanguageTag)
            }
        } else if (page == InterfaceSettingsPageId.GoogleFonts) {
            val context = RuntimeEnvironment.getApplication()
            compose.onNodeWithTag("ui_google_fonts_search").performTextReplacement("Noto")
            compose.onNodeWithText("Noto Sans").assertIsDisplayed()
            compose.onNodeWithText("Roboto").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.ui_google_fonts_refresh)).performClick()
            compose.runOnIdle { assertEquals("Catalog refresh invokes the real picker callback", 1, refreshes) }
            compose.onNode(hasText("Noto Sans") and hasClickAction()).performClick()
            compose.runOnIdle { assertEquals("Catalog selection invokes the real picker callback", googleFontsCatalog.last(), selectedGoogleFont.value) }
        } else if (page in setOf(InterfaceSettingsPageId.FontsApp, InterfaceSettingsPageId.FontsCode, InterfaceSettingsPageId.FontsChat)) {
            val context = RuntimeEnvironment.getApplication()
            compose.onNode(hasText(context.getString(R.string.ui_font_default)) and hasClickAction()).performClick()
            compose.onNodeWithText(context.getString(R.string.ui_font_serif)).performClick()
            compose.runOnIdle {
                assertTrue("Native font selector commits Settings", updates > 0)
                val selected = when (page) {
                    InterfaceSettingsPageId.FontsApp -> settings.value.displaySetting.appFontFamily
                    InterfaceSettingsPageId.FontsCode -> settings.value.displaySetting.codeFontFamily
                    else -> settings.value.displaySetting.chatFontFamily
                }
                assertEquals("Font choice reaches its actual target", ChatFontFamily.SERIF, selected)
            }
        } else {
            val slider = SemanticsMatcher("production settings slider") { it.config.contains(SemanticsActions.SetProgress) }
            val sliders = compose.onAllNodes(slider).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }
            if (sliders.isNotEmpty()) {
                val before = settings.value
                val info = sliders.first().config[SemanticsProperties.ProgressBarRangeInfo]
                val value = info.range.start + (info.range.endInclusive - info.range.start) * .75f
                compose.onAllNodes(slider).onFirst().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
                compose.runOnIdle {
                    assertTrue("Production slider commits its Settings callback", updates > 0)
                    assertTrue("Production slider changes its actual value", before != settings.value)
                }
            }
        }
    }

    private fun settingsFor(page: InterfaceSettingsPageId): Settings {
        val display = ScreenshotFixtures.settings.displaySetting
        return ScreenshotFixtures.settings.copy(displaySetting = when (page) {
            InterfaceSettingsPageId.Background -> display.copy(chatBackgroundEnabled = true,
                chatBackgroundImagePath = boundaries.wallpaper.toURI().toString())
            InterfaceSettingsPageId.MessageStyle -> display.copy(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED)
            InterfaceSettingsPageId.Glass -> display.copy(glassTheme = true, glassEconomy = true,
                chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED)
            else -> display
        })
    }

    private fun captureWallpaperChat() {
        val fixture = ChatBeforeFixture(boundaries.wallpaper).also { chat = it; it.install() }
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(
            chatBackgroundEnabled = true, chatBackgroundImagePath = boundaries.wallpaper.toURI().toString(),
            chatBackgroundMaskStrength = 1f, showMessageJumper = false,
        ))
        every { fixture.vm.settings } returns MutableStateFlow(settings)
        val template = fixture.conversation.messageNodes.first()
        fixture.conversationFlow.value = fixture.conversation.copy(title = "План поездки на выходные", messageNodes = (0..13).map { index ->
            val id = Uuid.parse("60000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}")
            template.copy(id = id, messages = listOf(template.currentMessage.copy(id = id,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                modelId = if (index % 2 == 0) null else ScreenshotFixtures.model.id,
                parts = listOf(UIMessagePart.Text(if (index % 2 == 0) "Предложи маршрут на выходные: парк, музей и кафе."
                    else "Начнём с прогулки по набережной. Затем зайдём в музей и оставим время на обед.\n\nВечером можно вернуться через парк и посмотреть закат.")),
            )), selectIndex = 0)
        })
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("ui_customization")) { fixture.Content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_list_timeline").performScrollToIndex(5)
        settle()
        val bar = compose.onNodeWithTag("chat_header_bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithTag("chat_header_title_line").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag("chat_list_timeline").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Fixed switcher keeps its 56dp row", 56f, bar.height, .5f)
        assertEquals("Conversation/model identity stays immediately below switcher", bar.bottom, identity.top, .5f)
        assertEquals("Identity keeps the compact separate 22dp row", 22f, identity.height, .5f)
        assertEquals("Messages scroll beneath header from viewport top", 0f, timeline.top, .5f)
        assertEquals("Messages scroll beneath composer to viewport bottom", 891f, timeline.bottom, .5f)
        compose.onNodeWithTag("chat_background_wallpaper").assertIsDisplayed()
        compose.onNodeWithTag("chat_background_mask").assertIsDisplayed()
        compose.onNodeWithTag("chat_header_model").assertIsDisplayed()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_header_browser)).assertIsDisplayed()
        capture(scene.name)
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_header_browser)).performClick()
        compose.runOnIdle {
            val intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertTrue("Fixed browser action opens the real browser Activity", intent?.component?.className?.endsWith("BrowserActivity") == true)
            assertEquals("Browser action carries the active conversation", fixture.conversation.id.toString(),
                intent?.getStringExtra(BrowserActivity.EXTRA_CONVERSATION_ID))
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        settle()
        val baseline = snapshots.baseline(name)
        requireNotNull(baseline.parentFile).mkdirs()
        val options = RoborazziOptions(
            taskType = if (snapshots.record) RoborazziTaskType.Record else RoborazziTaskType.Verify,
            compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = .007f)),
        )
        // New scenes still publish their real current frame before missing-baseline
        // verification fails. Both paths now consume one warmed native frame.
        captureRoboFrame(snapshots.artifact(name), baseline, options) { file, captureOptions ->
            compose.onNodeWithTag("ui_customization").captureRoboImage(file.absolutePath, roborazziOptions = captureOptions)
        }
        assertTrue("Missing committed stage-5 baseline: $baseline", baseline.isFile && baseline.length() > 0)
    }

    companion object {
        private val googleFontsCatalog = listOf(
            GoogleFontEntry("Roboto", "https://fonts.gstatic.com/s/roboto/fixture.ttf", listOf("latin", "cyrillic")),
            GoogleFontEntry("Open Sans", "https://fonts.gstatic.com/s/opensans/fixture.ttf", listOf("latin", "cyrillic")),
            GoogleFontEntry("Noto Sans", "https://fonts.gstatic.com/s/notosans/fixture.ttf", listOf("latin", "cyrillic", "greek")),
        )

        private val bottomPages = setOf(InterfaceSettingsPageId.Hub, InterfaceSettingsPageId.Messages,
            InterfaceSettingsPageId.Rendering, InterfaceSettingsPageId.Behavior,
            InterfaceSettingsPageId.Background, InterfaceSettingsPageId.MessageStyle, InterfaceSettingsPageId.Themes)

        @JvmStatic @Parameters(name = "{0}_{1}_font{2}") fun cases(): List<Array<Any>> {
            fun pageName(page: InterfaceSettingsPageId) =
                "settings_${page.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()}"
            val scenes = DrawerScreenshotMode.entries.map { mode ->
                UiCustomizationScreenshotScene("drawer_${mode.name.lowercase()}", drawer = mode)
            } + InterfaceSettingsPageId.entries.map { page ->
                UiCustomizationScreenshotScene(pageName(page), page = page)
            } + bottomPages.map { page ->
                UiCustomizationScreenshotScene("${pageName(page)}_bottom", page = page, bottom = true)
            } + UiCustomizationScreenshotScene("chat_wallpaper_header_scrolled")
            return scenes.flatMap { scene -> listOf(ScreenshotThemeMode.Light, ScreenshotThemeMode.Dark).flatMap { theme ->
                listOf(1f, 1.3f).map { fontScale -> arrayOf(scene, theme, fontScale) }
            } }
        }
    }
}
