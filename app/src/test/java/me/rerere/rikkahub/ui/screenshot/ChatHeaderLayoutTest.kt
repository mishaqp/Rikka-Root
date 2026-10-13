package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.chat.ChatHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHeaderLayoutTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var expectedTitleColor = Color.Unspecified

    @Test fun longTitleLeavesAllHeaderActionsReachable() {
        val clicks = IntArray(9)
        render(clicks)
        compose.onNodeWithTag("chat_header_title").assertIsDisplayed().performClick()
        listOf("Открыть список чатов", "Новый чат", "Файлы", "Терминал", "Браузер").forEachIndexed { index, label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label fits the $width dp viewport", bounds.left >= 0f && bounds.right <= width)
            node.performClick()
            assertEquals(1, clicks[index + 1])
        }
        assertEquals(1, clicks[0])
        compose.onNodeWithContentDescription("Меню чата").performClick()
        compose.onNodeWithText("Обзор и поиск сообщений").assertIsDisplayed().performClick()
        assertEquals(1, clicks[6])
        compose.onNodeWithText("Обзор и поиск сообщений").assertDoesNotExist()
    }

    @Test fun shortAvatarTitleAndModelGroupIsCenteredOnPermanentSecondLine() {
        render(IntArray(9), title = "Чат")
        assertCenteredIdentity()
        val identity = compose.onNodeWithTag("chat_header_identity").fetchSemanticsNode().boundsInRoot
        assertTrue("A short identity wraps its content within full-width line insets", identity.width < width - 32f)
        val avatar = compose.onNodeWithTag("chat_header_avatar").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithText(MODEL_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Center the visible avatar/title/model without spare width", expectedIdentityCenter(), (avatar.left + model.right) / 2f, 1f)
        assertAvatarAndHeaderSize()
    }

    @Test fun longTitleIsSingleLineEllipsizedAndUsesMutedOnSurface() {
        render(IntArray(9))
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(LONG_TITLE, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue("The long title must ellipsize rather than hide actions", layout.isLineEllipsized(0))
        assertEquals(expectedTitleColor, layout.layoutInput.style.color)
        compose.onNodeWithTag("chat_header_model").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun readOnlyDisablesRenameAndRetainsNavigationAndPreview() {
        val clicks = IntArray(9)
        render(clicks, readOnly = true, previewMode = true)
        compose.onNodeWithTag("chat_header_title").assertIsNotEnabled().performClick()
        assertEquals(0, clicks[0])
        compose.onNodeWithContentDescription("Открыть список чатов").performClick()
        compose.onNodeWithContentDescription("Новый чат").assertHasClickAction().performClick()
        assertEquals(1, clicks[1])
        assertEquals(1, clicks[2])
        compose.onNodeWithContentDescription("Меню чата").performClick()
        compose.onNodeWithText("Вернуться в чат").assertIsDisplayed().performClick()
        assertEquals(1, clicks[6])
    }

    @Test fun bigScreenKeepsPermanentIdentityAndBrowserAndHidesDrawer() {
        render(IntArray(9), title = "Чат", bigScreen = true, showWorkspace = false)
        compose.onNodeWithContentDescription("Открыть список чатов").assertDoesNotExist()
        compose.onNodeWithTag("chat_header_workspace").assertDoesNotExist()
        val leading = compose.onNodeWithTag("chat_header_leading").fetchSemanticsNode().boundsInRoot
        assertEquals("The leading inset must retain its measured horizontal padding", 4f, leading.left, 0.5f)
        assertEquals("Big screens retain the 12dp leading inset", 12f, leading.width, 0.5f)
        assertEquals("The leading slot must expose nonempty header bounds", 56f, leading.height, 0.5f)
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
    }

    @Test fun browserRemainsReachableWithoutWorkspaceAndModelCallbackIsIndependent() {
        val clicks = IntArray(9)
        render(clicks, title = "Чат", showWorkspace = false)
        compose.onNodeWithTag("chat_header_workspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("Файлы").assertDoesNotExist()
        compose.onNodeWithContentDescription("Терминал").assertDoesNotExist()
        compose.onNodeWithContentDescription("Браузер").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_header_model").assertIsDisplayed().performClick()
        assertEquals(1, clicks[5])
        assertEquals(1, clicks[7])
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
    }

    @Test fun capsuleMatchesMoruDimensionsWithoutStealingTitleWidth() {
        render(IntArray(9))
        val capsule = compose.onNodeWithTag("chat_header_switcher").fetchSemanticsNode().boundsInRoot
        assertEquals(40f, capsule.height, .5f)
        assertEquals(148f, capsule.width, .5f)
        assertEquals("The switcher stays at the screen centre despite asymmetric leading/actions slots", width / 2f, capsule.center.x, .5f)
        val actions = compose.onNodeWithTag("chat_header_actions").fetchSemanticsNode().boundsInRoot
        assertTrue("The centred capsule does not overlap the menu/new-chat targets", capsule.right <= actions.left)
        listOf("Файлы", "Терминал", "Браузер").forEach { description ->
            val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertEquals(48f, bounds.width, .5f)
            assertEquals(34f, bounds.height, .5f)
            assertTrue(bounds.left >= capsule.left && bounds.right <= capsule.right)
        }
    }

    @Test fun avatarPointerTapAndLongPressDispatchSeparateActionsWithoutGrowingTitleLine() {
        val clicks = IntArray(9)
        render(clicks, title = "Чат")
        val avatar = compose.onNodeWithTag("chat_header_avatar").assertIsDisplayed().assertHasClickAction()
        avatar.performTouchInput { click() }
        assertEquals("Avatar tap opens the drawer", 1, clicks[1])
        assertEquals(0, clicks[8])
        avatar.performTouchInput { longClick() }
        assertEquals("A long press must not also trigger the drawer tap", 1, clicks[1])
        assertEquals("Long press opens assistant settings", 1, clicks[8])
        assertAvatarAndHeaderSize()
        assertCenteredIdentity()
    }

    @Test fun avatarLongPressRespectsDisabledHapticsAndRetainsItsAction() {
        val clicks = IntArray(9)
        val haptics = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        render(clicks, avatarHaptics = false, hapticFeedback = recorder)
        compose.onNodeWithTag("chat_header_avatar").performTouchInput { longClick() }
        assertEquals(1, clicks[8])
        assertTrue("A disabled category must not produce automatic foundation long-press haptics", haptics.isEmpty())
    }

    @Test fun capsuleAndTitleLineExposeWallpaperInLightTheme() {
        assertHeaderExposesWallpaper(ScreenshotThemeMode.Light)
    }

    @Test fun capsuleAndTitleLineExposeWallpaperInDarkTheme() {
        assertHeaderExposesWallpaper(ScreenshotThemeMode.Dark)
    }

    private fun assertHeaderExposesWallpaper(theme: ScreenshotThemeMode) {
        render(IntArray(9), title = "Чат", theme = theme, backgroundColor = Color.Magenta)
        val pixels = compose.onNodeWithTag("header_pixels").captureToImage().toPixelMap()
        val root = compose.onNodeWithTag("header_pixels").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("chat_header_switcher").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("chat_header_title_line").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
        fun sample(x: Float, y: Float) = pixels[(x - root.left).toInt(), (y - root.top).toInt()]
        // The centre seam between two segments is clear of icons and the thin outer border.
        assertEquals("Capsule interior has no dark/light fill over wallpaper", Color.Magenta,
            sample(capsule.left + 50f, capsule.center.y))
        assertEquals("The full title/model line has no painted background strip", Color.Magenta,
            sample(root.left + 8f, line.center.y))
        assertEquals("The main header has no opaque bar beside the capsule", Color.Magenta,
            sample(root.left + 60f, bar.center.y))
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
        assertEquals(40f, capsule.height, .5f)
    }

    private fun assertCenteredIdentity() {
        val identity = compose.onNodeWithTag("chat_header_identity").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("chat_header_title_line").fetchSemanticsNode().boundsInRoot
        assertEquals("Center avatar/title/model on the separate full-width line", expectedIdentityCenter(), identity.center.x, 1f)
        assertTrue("The identity must sit below the switcher even without a workspace", identity.top >= bar.bottom - .5f)
        assertEquals(22f, line.height, .5f)
        assertTrue("Identity content respects 16dp side insets", identity.left >= 16f && identity.right <= width - 16f)
    }

    private fun expectedIdentityCenter(): Float = width / 2f

    private fun assertAvatarAndHeaderSize() {
        val avatar = compose.onNodeWithTag("chat_header_avatar").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, avatar.width, 0.5f)
        assertEquals(16f, avatar.height, 0.5f)
        val bar = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
        assertEquals(56f, bar.height, 0.5f)
    }

    private fun render(
        clicks: IntArray,
        title: String = LONG_TITLE,
        readOnly: Boolean = false,
        previewMode: Boolean = false,
        bigScreen: Boolean = false,
        showWorkspace: Boolean = true,
        avatarHaptics: Boolean = true,
        hapticFeedback: HapticFeedback? = null,
        theme: ScreenshotThemeMode = ScreenshotThemeMode.Light,
        backgroundColor: Color? = null,
    ) {
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                expectedTitleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f)
                CompositionLocalProvider(LocalHapticFeedback provides (hapticFeedback ?: LocalHapticFeedback.current)) {
                Surface(Modifier.requiredSize(width.dp, 180.dp).testTag("header_pixels"),
                    color = backgroundColor ?: MaterialTheme.colorScheme.background) {
                    ChatHeader(
                        title = title,
                        modelName = MODEL_NAME,
                        onSelectModel = { clicks[7]++ },
                        onLongClickAvatar = { clicks[8]++ },
                        avatarHapticFeedbackEnabled = avatarHaptics,
                        bigScreen = bigScreen, previewMode = previewMode,
                        canRename = !readOnly, showWorkspace = showWorkspace,
                        avatar = { Box(Modifier.size(16.dp)) },
                        onClickTitle = { clicks[0]++ },
                        onOpenDrawer = { clicks[1]++ }, onNewChat = { clicks[2]++ },
                        onFiles = { clicks[3]++ }, onTerminal = { clicks[4]++ },
                        onBrowser = { clicks[5]++ }, onClickMenu = { clicks[6]++ },
                    )
                }
                }
            }
        }
    }

    companion object {
        private const val MODEL_NAME = "GPT-4.1"
        private const val LONG_TITLE = "Очень длинное русское название текущего разговора"
        @JvmStatic @Parameters(name = "width{0}_font{1}") fun cases(): List<Array<Any>> =
            listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { arrayOf(width, it) } }
    }
}
