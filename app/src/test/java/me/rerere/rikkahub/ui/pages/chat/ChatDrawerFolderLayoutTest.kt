package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDrawerFolderLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creationUsesCompactPlusChipAndKeepsItsAction() {
        var creations = 0
        compose.setContent {
            ScreenshotTheme {
                Surface(Modifier.width(288.dp)) {
                    FolderBar(emptyList(), null, true, {}, {}, { creations++ }, {}, {})
                }
            }
        }
        compose.onNodeWithText("+").performClick()
        assertEquals(1, creations)
        compose.onNodeWithText("Без папки").assertDoesNotExist()
    }

    @Test
    fun longPressKeepsRenameAndDeleteActions() {
        val folder = Folder(id = Uuid.random(), assistantId = Uuid.random(), name = "Работа")
        var renamed: Folder? = null
        var deleted: Folder? = null
        var selections = 0
        compose.setContent {
            ScreenshotTheme {
                Surface(Modifier.width(288.dp)) {
                    FolderBar(listOf(folder), null, true, {}, { selections++ }, {}, { renamed = it }, { deleted = it })
                }
            }
        }
        compose.onNodeWithText(folder.name).performTouchInput { longClick() }
        compose.onNodeWithText("Переименовать").performClick()
        assertEquals(folder, renamed)
        compose.onNodeWithText(folder.name).performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").performClick()
        assertEquals(folder, deleted)
        assertEquals("Long presses must not select the folder", 0, selections)
    }
}
