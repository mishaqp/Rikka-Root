package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.ScreenshotThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the same row used by the paged drawer, including its interaction semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationItemLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val conversation = Conversation(
        id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        title = "План на завтра",
        messageNodes = emptyList(),
        updateAt = Instant.parse("2026-01-02T12:00:00Z"),
        isPinned = true,
    )
    private val metadata = ChatDrawerRowMetadata(
        preview = "Подготовить список задач",
        hasTools = true,
        hasWorkflows = true,
    )
    private val rowTag = "chat_drawer_conversation_${conversation.id}"
    private var selectedForeground = Color.Unspecified

    @Test
    fun `compact row grows with font scale instead of clipping its content`() {
        val fontScale = mutableFloatStateOf(1f)
        showRow(fontScale = { fontScale.floatValue })

        val normalHeight = rowHeightDp()
        assertEquals("Default font scale keeps the compact row at 56dp", 56f, normalHeight, 0.5f)

        compose.runOnIdle { fontScale.floatValue = 1.3f }
        val enlargedHeight = rowHeightDp()
        assertTrue("The row must grow when its two text lines need more space", enlargedHeight > normalHeight)
        val rowBounds = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        listOf(conversation.title, metadata.preview, "02.01").forEach { text ->
            val textBounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$text extends above the row", textBounds.top >= rowBounds.top)
            assertTrue("$text extends below the row", textBounds.bottom <= rowBounds.bottom)
            assertTrue("$text extends past the trailing edge", textBounds.right <= rowBounds.right)
        }
    }

    @Test
    fun `selected title preview and time use the selected container foreground`() {
        val mode = mutableStateOf(ScreenshotThemeMode.Light)
        showRow(themeMode = { mode.value })

        ScreenshotThemeMode.entries.forEach { theme ->
            compose.runOnIdle { mode.value = theme }
            compose.waitForIdle()
            assertTextColor(conversation.title, selectedForeground)
            assertTextColor(metadata.preview, selectedForeground)
            assertTextColor("02.01", selectedForeground)
        }
    }

    @Test
    fun `large font row retains all six accessible actions and status information`() {
        val invoked = mutableListOf<String>()
        showRow(fontScale = { 1.3f }, invoked = invoked)
        val row = compose.onNodeWithTag(rowTag).fetchSemanticsNode()
        assertEquals(Role.Tab, row.config[SemanticsProperties.Role])
        assertTrue(row.config[SemanticsProperties.Selected])
        val actions = row.config[SemanticsActions.CustomActions]
        assertEquals(
            listOf("Открепить", "Переименовать", "Обновить название с ИИ", "В папку", "К ассистенту", "Удалить"),
            actions.map { it.label },
        )
        compose.runOnIdle {
            actions.forEach { assertTrue(it.action()) }
        }
        assertEquals(listOf("pin", "rename", "regenerate", "folder", "assistant", "delete"), invoked)

        val description = row.config[SemanticsProperties.ContentDescription].joinToString(". ")
        listOf(conversation.title, metadata.preview, "02.01", "Закреплён", "Работали сценарии", "Ответ формируется")
            .forEach { assertTrue("Missing accessible status: $it", description.contains(it)) }
    }

    @Test
    fun `long press keeps all six menu actions available with large fonts`() {
        showRow(fontScale = { 1.3f })
        compose.onNodeWithTag(rowTag).performTouchInput { longClick() }

        listOf("Открепить", "Переименовать", "Обновить название с ИИ", "В папку", "К ассистенту", "Удалить")
            .forEach { compose.onNodeWithText(it).assertExists() }
    }

    private fun showRow(
        fontScale: () -> Float = { 1f },
        invoked: MutableList<String> = mutableListOf(),
        themeMode: () -> ScreenshotThemeMode = { ScreenshotThemeMode.Light },
    ) {
        compose.setContent {
            ScreenshotTheme(mode = themeMode(), fontScale = fontScale()) {
                selectedForeground = MaterialTheme.colorScheme.onSecondaryContainer
                Box(Modifier.width(320.dp)) {
                    ConversationItem(
                        conversation = conversation,
                        metadata = metadata,
                        selected = true,
                        loading = true,
                        onDelete = { invoked += "delete" },
                        onRegenerateTitle = { invoked += "regenerate" },
                        onPin = { invoked += "pin" },
                        onMoveToAssistant = { invoked += "assistant" },
                        onMoveToFolder = { invoked += "folder" },
                        onRename = { invoked += "rename" },
                        onClick = {},
                    )
                }
            }
        }
    }

    private fun rowHeightDp(): Float =
        compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.height / compose.density.density

    private fun assertTextColor(text: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        assertEquals("Foreground for $text", expected, layouts.single().layoutInput.style.color)
    }
}
