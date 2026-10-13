package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        val clicks = IntArray(7)
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

    @Test fun shortAvatarAndTitleGroupIsCenteredBetweenNavigationAndActions() {
        render(IntArray(7), title = "Чат")
        assertCenteredIdentity()
        val identity = compose.onNodeWithTag("chat_header_identity").fetchSemanticsNode().boundsInRoot
        val leading = compose.onNodeWithTag("chat_header_leading").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("chat_header_actions").fetchSemanticsNode().boundsInRoot
        assertTrue("A short identity wraps its content instead of filling the middle", identity.width < actions.left - leading.right)
        val avatar = compose.onNodeWithTag("chat_header_avatar").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Чат", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Center the visible avatar and text without spare title width", expectedIdentityCenter(), (avatar.left + title.right) / 2f, 1f)
        assertAvatarAndHeaderSize()
    }

    @Test fun longTitleIsSingleLineEllipsizedAndUsesOnSurface() {
        render(IntArray(7))
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(LONG_TITLE, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue("The long title must ellipsize rather than hide actions", layout.isLineEllipsized(0))
        assertEquals(expectedTitleColor, layout.layoutInput.style.color)
        compose.onNodeWithTag("chat_header_model").assertDoesNotExist()
    }

    @Test fun readOnlyDisablesRenameAndRetainsNavigationAndPreview() {
        val clicks = IntArray(7)
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

    @Test fun bigScreenCentersIdentityAfterLeadingInsetAndHidesDrawer() {
        render(IntArray(7), title = "Чат", bigScreen = true, showWorkspace = false)
        compose.onNodeWithContentDescription("Открыть список чатов").assertDoesNotExist()
        compose.onNodeWithTag("chat_header_workspace").assertDoesNotExist()
        val leading = compose.onNodeWithTag("chat_header_leading").fetchSemanticsNode().boundsInRoot
        assertEquals("The leading inset must retain its measured horizontal padding", 4f, leading.left, 0.5f)
        assertEquals("Big screens retain the 12dp leading inset", 12f, leading.width, 0.5f)
        assertEquals("The leading slot must expose nonempty header bounds", 56f, leading.height, 0.5f)
        assertCenteredIdentity()
        assertAvatarAndHeaderSize()
    }

    private fun assertCenteredIdentity() {
        val identity = compose.onNodeWithTag("chat_header_identity").fetchSemanticsNode().boundsInRoot
        val leading = compose.onNodeWithTag("chat_header_leading").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("chat_header_actions").fetchSemanticsNode().boundsInRoot
        assertEquals("Center the whole avatar/title group in the available space", expectedIdentityCenter(), identity.center.x, 1f)
        if (identityOnSecondLine()) {
            // Moru: with a workspace the capsule owns the bar and the name is a quiet line below it.
            val bar = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
            assertTrue("The identity sits below the bar", identity.top >= bar.bottom - 0.5f)
            assertTrue("The identity stays inside the screen", identity.left >= 0f && identity.right <= width)
        } else {
            assertTrue("The identity must stay clear of navigation", identity.left >= leading.right)
            assertTrue("The identity must stay clear of menu and new chat", identity.right <= actions.left)
        }
    }

    private fun identityOnSecondLine(): Boolean {
        val identity = compose.onNodeWithTag("chat_header_identity").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
        return identity.top >= bar.bottom - 0.5f
    }

    private fun expectedIdentityCenter(): Float {
        if (identityOnSecondLine()) return width / 2f
        val leading = compose.onNodeWithTag("chat_header_leading").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("chat_header_actions").fetchSemanticsNode().boundsInRoot
        return (leading.right + actions.left) / 2f
    }

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
    ) {
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Light, fontScale) {
                // Under the workspace capsule the name is Moru's quiet secondary line.
                expectedTitleColor = if (showWorkspace) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                Surface(Modifier.requiredSize(width.dp, 180.dp)) {
                    ChatHeader(
                        title = title,
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

    companion object {
        private const val LONG_TITLE = "Очень длинное русское название текущего разговора"
        @JvmStatic @Parameters(name = "width{0}_font{1}") fun cases(): List<Array<Any>> =
            listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { arrayOf(width, it) } }
    }
}
