package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.deviceToolParts
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.service.ChatService.ApprovalScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileNotFoundException
import kotlin.time.Instant

/** Exercises the production grouping, visibility projection and actual tool/reasoning renderers. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class Stage4ToolPresentationRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulClientStepShowsHeadingAndOutputWithoutAResultReceivedLabel() {
        val tool = UIMessagePart.Tool("done", "root_exec", """{"command":"echo итог"}""",
            output = listOf(UIMessagePart.Text("""{"success":true,"stdout":"готово\nитог\n","stderr":"","exit_code":0}""")))
        render(listOf(tool))
        compose.onNodeWithText("Root: echo итог").assertExists()
        val layout = layoutFor("chat_timeline_tool_preview:done")
        assertEquals("готово\nитог", layout.layoutInput.text.text)
        assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        assertNoResultReceivedLabel()
    }

    @Test fun nonzeroCommandShowsMonospaceStreamsAndFullRussianRecovery() {
        val explanation = "Команда завершилась с ошибкой (код 7). Проверьте вывод и результат перед новым запуском."
        render(listOf(UIMessagePart.Tool("nonzero", "root_exec", """{"command":"run command"}""",
            output = listOf(UIMessagePart.Text("""{"success":false,"exit_code":7,"stdout":"checkpoint saved","stderr":"command failed"}""")))))
        val outputLayout = layoutFor("chat_timeline_tool_preview:nonzero")
        assertEquals("checkpoint saved", outputLayout.layoutInput.text.text)
        assertEquals(FontFamily.Monospace, outputLayout.layoutInput.style.fontFamily)
        compose.onNodeWithText("command failed").assertExists()
        compose.onNodeWithText(explanation).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(explanation).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Int.MAX_VALUE, layouts.single().layoutInput.maxLines)
    }

    @Test fun completedServerStepsWithAndWithoutOutputStayQuiet() {
        render(listOf(
            UIMessagePart.ServerTool("empty", "server_empty", status = ServerToolStatus.COMPLETED),
            UIMessagePart.ServerTool("result", "server_result", output = JsonPrimitive("ответ сервера"),
                status = ServerToolStatus.COMPLETED),
        ))
        compose.onNodeWithTag("chat_timeline_server_details:empty").assertExists()
        compose.onNodeWithTag("chat_timeline_server_details:result").assertExists()
        compose.onNodeWithText("ответ сервера").assertExists()
        assertNoResultReceivedLabel()
    }

    @Test fun emptyFinishedReasoningIsHiddenWhileItsStreamingStateStillShowsTheThinkingCard() {
        val mark = Instant.parse("2026-10-13T01:00:00Z")
        val reasoning = UIMessagePart.Reasoning(" \n", createdAt = mark, finishedAt = mark)
        val tool = UIMessagePart.Tool("done", "root_exec", "{}",
            output = listOf(UIMessagePart.Text("{\"success\":true,\"stdout\":\"результат\"}")))
        val state = render(listOf(reasoning, tool))
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("результат", layoutFor("chat_timeline_tool_preview:done").layoutInput.text.text)
        compose.runOnIdle { state.value = listOf(reasoning.copy(finishedAt = null), tool) }
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun everyTermuxSessionConsumerShowsTheNewestFourTerminalLines() {
        val names = listOf("termux_session_start", "termux_session_send", "termux_session_read")
        val output = """{"success":true,"screen":"old\nсессия\n~ $ ls\na  b\n~ $ \n\n"}"""
        render(names.map { name -> UIMessagePart.Tool(name, name, "{}", output = listOf(UIMessagePart.Text(output))) })
        names.forEach { name ->
            val layout = layoutFor("chat_timeline_tool_preview:$name")
            assertEquals("сессия\n~ $ ls\na  b\n~ $", layout.layoutInput.text.text)
            assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        }
        assertNoResultReceivedLabel()
    }

    @Test fun deviceErrorReasonsRemainRussianAndVisibleWithToolCardsDisabled() {
        val authored = runBlocking { deviceToolParts {
            throw IllegalArgumentException("Нет записи в выбранной папке. Сначала вызовите grant_directory_access.")
        } }
        val platform = runBlocking { deviceToolParts {
            throw FileNotFoundException("/data/user/0/private.txt: EACCES (Permission denied)")
        } }
        render(listOf(UIMessagePart.Tool("authored", "write_text_file", "{}", output = authored),
            UIMessagePart.Tool("platform", "write_text_file", "{}", output = platform)),
            DisplaySetting(showToolCards = false, collapseThinkingSteps = false))
        assertEquals("Нет записи в выбранной папке. Сначала вызовите grant_directory_access.", layoutFor("chat_timeline_tool_preview:authored").layoutInput.text.text)
        assertEquals("Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку.", layoutFor("chat_timeline_tool_preview:platform").layoutInput.text.text)
        assertFalse((platform.single() as UIMessagePart.Text).text.contains("private.txt"))
        assertEquals(2, compose.onAllNodesWithText("Ошибка").fetchSemanticsNodes().size)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ordinaryPreviewsStayCompactWhileWarningsAndPartialNotesRemainComplete() {
        fun assertFullTextFits(label: String, layout: TextLayoutResult) {
            assertEquals("$label must not limit its lines", Int.MAX_VALUE, layout.layoutInput.maxLines)
            assertFalse("$label must fit vertically", layout.didOverflowHeight)
            // Paragraph width can retain the original maximum constraint after Text shrinks
            // to its longest line. Actual line bounds detect clipping without that sentinel.
            for (line in 0 until layout.lineCount) {
                assertFalse("$label line $line must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("$label line $line must fit horizontally",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("$label line $line must fit vertically",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertEquals("$label must render its last character", layout.layoutInput.text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        val warning = "Проверьте копию.\nСохраните оригинал.\nНе повторяйте действие.\nОткройте результат.\nПроверьте его содержимое.\nДождитесь решения пользователя."
        val partialNote = "Экран сессии недоступен.\nКоманда могла выполниться.\nНе вводите её повторно.\nПрочитайте сессию.\nПроверьте сохранённый вывод.\nРешите, что делать дальше."
        val ordinary = "Первая строка\nВторая строка\nТретья строка\nЧетвёртая строка\nПятая строка\nШестая строка"
        val warningOutput = kotlinx.serialization.json.buildJsonObject {
            put("success", JsonPrimitive(true)); put("warning", JsonPrimitive(warning))
        }.toString()
        val partialOutput = kotlinx.serialization.json.buildJsonObject {
            put("success", JsonPrimitive(true)); put("screen", JsonPrimitive("")); put("note", JsonPrimitive(partialNote))
            put("partial_stdout", JsonPrimitive("checkpoint saved")); put("partial_stderr", JsonPrimitive("partial warning"))
        }.toString()
        // Each mode gets the full viewport; a fixed Column of all three long cards would
        // constrain later paragraphs by the height consumed by their preceding siblings.
        val state = render(listOf(
            UIMessagePart.Tool("ordinary", "unregistered_fixture_tool", "{}", output = listOf(UIMessagePart.Text(ordinary))),
        ))
        val ordinaryLayout = layoutFor("chat_timeline_tool_preview:ordinary")
        assertTrue(ordinaryLayout.lineCount <= 4)
        assertEquals(4, ordinaryLayout.layoutInput.maxLines)
        compose.runOnIdle {
            state.value = listOf(UIMessagePart.Tool("warning", "wake_screen", "{}", output = listOf(UIMessagePart.Text(warningOutput))))
        }
        val warningLayout = layoutFor("chat_timeline_tool_preview:warning")
        assertTrue(warningLayout.layoutInput.text.text.contains(warning))
        assertTrue(warningLayout.lineCount > 4)
        assertFullTextFits("Warning", warningLayout)
        compose.runOnIdle {
            state.value = listOf(UIMessagePart.Tool("partial", "termux_session_start", "{}", output = listOf(UIMessagePart.Text(partialOutput))))
        }
        val partialLayout = layoutFor("chat_timeline_tool_preview:partial")
        listOf(partialNote, "checkpoint saved", "partial warning").forEach { assertTrue(partialLayout.layoutInput.text.text.contains(it)) }
        assertTrue(partialLayout.lineCount > 4)
        assertFullTextFits("Partial note", partialLayout)
    }

    @Test fun hiddenToolCardsRetainMandatoryApprovalCallbacksAndRunningOrFailedServerLabels() {
        val approved = mutableListOf<ApprovalCall>()
        val done = UIMessagePart.Tool("hidden", "root_exec", "{}",
            output = listOf(UIMessagePart.Text("{\"success\":true}")))
        val pending = UIMessagePart.Tool("pending", "root_exec", """{"command":"echo approved"}""",
            approvalState = ToolApprovalState.Pending)
        val denied = UIMessagePart.Tool("denied", "root_exec", "{}", approvalState = ToolApprovalState.Denied("Отказ пользователя"))
        render(listOf(done, pending, denied,
            UIMessagePart.ServerTool("running", "server_running", status = ServerToolStatus.IN_PROGRESS),
            UIMessagePart.ServerTool("failed", "server_failed", status = ServerToolStatus.FAILED)),
            DisplaySetting(showToolCards = false, showThinkingCards = false, collapseThinkingSteps = false)) { id, allowed, reason, scope, name ->
            approved += ApprovalCall(id, allowed, reason, scope, name)
            Job().apply { complete() }
        }
        compose.onNodeWithTag("chat_timeline_tool_details:hidden").assertDoesNotExist()
        compose.onNodeWithText("Требуется разрешение").assertExists()
        compose.onNodeWithText("Выполняется").assertExists()
        compose.onNodeWithText("Ошибка").assertExists()
        compose.onNodeWithText("Отклонено").assertExists()
        compose.onNodeWithTag("chat_timeline_approval_once:pending").performClick()
        compose.waitUntil(5_000) { approved.isNotEmpty() }
        assertEquals(listOf(ApprovalCall("pending", true, "", ApprovalScope.Once, "root_exec")), approved)
    }

    private data class ApprovalCall(val id: String, val approved: Boolean, val reason: String, val scope: ApprovalScope, val name: String)

    private fun render(
        parts: List<UIMessagePart>,
        display: DisplaySetting = DisplaySetting(collapseThinkingSteps = false),
        approval: ((String, Boolean, String, ApprovalScope, String) -> Job)? = null,
    ): MutableState<List<UIMessagePart>> {
        val state = mutableStateOf(parts)
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides ScreenshotFixtures.settings.copy(displaySetting = display)) {
                    Column(Modifier.width(320.dp)) {
                        val message = UIMessage(role = MessageRole.ASSISTANT, parts = state.value)
                        val options = presentMessageDisplay(message, display).tools
                        state.value.groupMessageParts().filterIsInstance<MessagePartBlock.ThinkingBlock>()
                            .forEachIndexed { ordinal, block ->
                                ChatToolTimeline(block, "message", ordinal, options, 1f, false,
                                    null, null, emptyList(), onToolApproval = approval, onToolAnswer = null)
                            }
                    }
                }
            }
        }
        return state
    }

    private fun layoutFor(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertNoResultReceivedLabel() {
        compose.onNodeWithText("Результат получен").assertDoesNotExist()
    }
}
