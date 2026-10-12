package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun longNamesLeaveAllHeaderActionsReachable() {
        val clicks = IntArray(7)
        render(clicks)
        compose.onNodeWithTag("chat_header_title").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_header_model").assertIsDisplayed().performClick()
        listOf("Открыть список чатов", "Новый чат", "Файлы", "Терминал", "Браузер").forEachIndexed { index, label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label fits the $width dp viewport", bounds.left >= 0f && bounds.right <= width)
            node.performClick()
            assertEquals(1, clicks[index + 2])
        }
        assertEquals(1, clicks[0])
        assertEquals(1, clicks[1])
        compose.onNodeWithContentDescription("Меню чата").performClick()
        compose.onNodeWithText("Обзор и поиск сообщений").assertIsDisplayed()
    }

    @Test fun readOnlyRetainsNavigationAndDisablesMutations() {
        render(IntArray(7), readOnly = true)
        compose.onNodeWithTag("chat_header_title").assertIsNotEnabled()
        compose.onNodeWithTag("chat_header_model").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Новый чат").assertHasClickAction()
    }

    private fun render(clicks: IntArray, readOnly: Boolean = false) {
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Light, fontScale) {
                Surface(Modifier.requiredSize(width.dp, 180.dp)) {
                    ChatHeader(
                        title = "Очень длинное русское название текущего разговора",
                        modelName = "Очень длинная модель с расширенным рассуждением",
                        modelDescription = "Модель и провайдер полностью доступны для чтения",
                        bigScreen = false, previewMode = false,
                        canRename = !readOnly, canSelectModel = !readOnly, showWorkspace = true,
                        avatar = { Text("А") },
                        onClickTitle = { clicks[0]++ }, onSelectModel = { clicks[1]++ },
                        onOpenDrawer = { clicks[2]++ }, onNewChat = { clicks[3]++ },
                        onFiles = { clicks[4]++ }, onTerminal = { clicks[5]++ },
                        onBrowser = { clicks[6]++ }, onClickMenu = {},
                    )
                }
            }
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "width{0}_font{1}") fun cases(): List<Array<Any>> =
            listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { arrayOf(width, it) } }
    }
}
