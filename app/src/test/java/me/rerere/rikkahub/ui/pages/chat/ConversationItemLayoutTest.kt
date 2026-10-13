package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    private var selectedBackground = Color.Unspecified

    @Test
    fun `compact row grows with font scale instead of clipping its content`() {
        val fontScale = mutableFloatStateOf(1f)
        showRow(fontScale = { fontScale.floatValue })

        val normalHeight = rowHeightDp()
        assertEquals("Moru title row retains a 48dp touch area", 48f, normalHeight, 0.5f)

        listOf(1.3f, 1.5f, 2f).forEach { scale ->
            compose.runOnIdle { fontScale.floatValue = scale }
            assertTrue("The title row grows for font scale $scale", rowHeightDp() > normalHeight)
            val row = compose.onNodeWithTag(rowTag).fetchSemanticsNode()
            val text = compose.onNodeWithText(conversation.title, useUnmergedTree = true).fetchSemanticsNode()
            val rowBounds = row.boundsInRoot
            val textBounds = text.boundsInRoot
            assertTrue("Title extends above the row at $scale", textBounds.top >= rowBounds.top)
            assertTrue("Title extends below the row at $scale", textBounds.bottom <= rowBounds.bottom)
            assertTrue("Title extends past the leading edge at $scale", textBounds.left >= rowBounds.left)
            assertTrue("Title extends past the trailing edge at $scale", textBounds.right <= rowBounds.right)
            val layout = textLayout(conversation.title)
            assertEquals("Moru has one title line", 1, layout.lineCount)
            assertTrue("Short title must not clip at $scale", !layout.hasVisualOverflow)
            assertAccessibleMetadata(row.config[SemanticsProperties.ContentDescription].joinToString(". "))
        }
        // Moru's title-only appearance retains this information in the accessible row description.
        compose.onNodeWithText(metadata.preview, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("02.01", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `selected title uses onSurface and preserves contrast across all theme modes`() {
        val mode = mutableStateOf(ScreenshotThemeMode.Light)
        showRow(themeMode = { mode.value })

        ScreenshotThemeMode.entries.forEach { theme ->
            compose.runOnIdle { mode.value = theme }
            compose.waitForIdle()
            assertTextColor(conversation.title, selectedForeground)
            val foregroundLuminance = selectedForeground.luminance()
            val backgroundLuminance = selectedBackground.luminance()
            val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + .05f) /
                (minOf(foregroundLuminance, backgroundLuminance) + .05f)
            assertTrue("Title contrast is too low in $theme: $contrast", contrast >= 4.5f)
            assertAccessibleMetadata(compose.onNodeWithTag(rowTag).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].joinToString(". "))
        }
    }

    @Test
    fun `large font row retains all six accessible actions plus selection and status information`() {
        val invoked = mutableListOf<String>()
        showRow(fontScale = { 1.3f }, invoked = invoked)
        val row = compose.onNodeWithTag(rowTag).fetchSemanticsNode()
        assertEquals(Role.Tab, row.config[SemanticsProperties.Role])
        assertTrue(row.config[SemanticsProperties.Selected])
        val actions = row.config[SemanticsActions.CustomActions]
        assertEquals(
            listOf("Открепить", "Переименовать", "Обновить название с ИИ", "В папку", "К ассистенту", "Удалить", "Выбрать"),
            actions.map { it.label },
        )
        compose.runOnIdle {
            actions.forEach { assertTrue(it.action()) }
        }
        assertEquals(listOf("pin", "rename", "regenerate", "folder", "assistant", "delete", "select"), invoked)

        val description = row.config[SemanticsProperties.ContentDescription].joinToString(". ")
        assertAccessibleMetadata(description)
    }

    @Test
    fun `long press keeps all six menu callbacks and selection available with large fonts`() {
        val invoked = mutableListOf<String>()
        showRow(fontScale = { 1.3f }, invoked = invoked)
        val actions = listOf("Открепить" to "pin", "Переименовать" to "rename", "Обновить название с ИИ" to "regenerate",
            "В папку" to "folder", "К ассистенту" to "assistant", "Удалить" to "delete", "Выбрать" to "select")
        actions.forEachIndexed { index, (label, _) ->
            compose.onNodeWithTag(rowTag).performTouchInput { longClick() }
            actions.forEach { (text, _) -> compose.onNodeWithText(text).assertExists() }
            compose.onNodeWithText(label).performClick()
            assertEquals(actions.take(index + 1).map { it.second }, invoked)
        }
    }

    private fun showRow(
        fontScale: () -> Float = { 1f },
        invoked: MutableList<String> = mutableListOf(),
        themeMode: () -> ScreenshotThemeMode = { ScreenshotThemeMode.Light },
    ) {
        compose.setContent {
            ScreenshotTheme(mode = themeMode(), fontScale = fontScale()) {
                val colors = MaterialTheme.colorScheme
                val background = if (themeMode().amoled) colors.background else colors.surface
                selectedForeground = colors.onSurface
                selectedBackground = colors.primary.copy(alpha = .12f).compositeOver(background)
                Surface(color = background) {
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
                            onStartSelection = { invoked += "select" },
                        )
                    }
                }
            }
        }
    }

    private fun rowHeightDp(): Float =
        compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.height / compose.density.density

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        return layouts.single()
    }

    private fun assertTextColor(text: String, expected: Color) {
        assertEquals("Foreground for $text", expected, textLayout(text).layoutInput.style.color)
    }

    private fun assertAccessibleMetadata(description: String) {
        listOf(conversation.title, metadata.preview, "02.01", "Закреплён", "Работали сценарии", "Ответ формируется")
            .forEach { assertTrue("Missing accessible status: $it", description.contains(it)) }
    }
}
