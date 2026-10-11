package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerRowPresentationTest {
    @Test
    fun `completed and failed server operations keep their tool indicator`() {
        for (status in listOf(ServerToolStatus.COMPLETED, ServerToolStatus.FAILED)) {
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.ServerTool("server-test", "workflow_dispatch", status = status),
            ))
            val summary = metadata(node(message))
            assertEquals("Работа с инструментами", summary.preview)
            assertTrue(summary.hasTools)
            assertTrue(summary.hasWorkflows)
        }
    }

    @Test
    fun `preview uses the last selected branch and collapses whitespace`() {
        val first = node(UIMessage.user("Первое сообщение"))
        val last = MessageNode(
            messages = listOf(UIMessage.assistant("Старая ветка"), UIMessage.assistant("  Последнее\n\n сообщение\tчата  ")),
            selectIndex = 1,
        )
        assertEquals("Последнее сообщение чата", metadata(first, last).preview)
    }

    @Test
    fun `preview never includes reasoning or tool arguments`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "Скрытые рассуждения"), tool(input = "секрет")),
        )
        assertEquals("Работа с инструментами", metadata(node(message)).preview)
    }

    @Test
    fun `attachment only messages use the no text fallback`() {
        assertEquals("Работа с инструментами", metadata(node(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file://private"))))).preview)
        assertEquals("Работа с инструментами", metadata(node(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Document("file://private", "инструкция.pdf"))))).preview)
    }

    @Test
    fun `tool only last message uses earlier user text and keeps workflow indicators`() {
        val first = node(UIMessage.user("  Проверь\n\n заряд\tтелефона  "))
        val last = node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            tool(name = "workflow_run", input = "private arguments", output = listOf(UIMessagePart.Text("private result"))),
        )))
        val summary = metadata(first, last)
        assertEquals("Проверь заряд телефона", summary.preview)
        assertTrue(summary.hasTools)
        assertTrue(summary.hasWorkflows)
    }

    @Test
    fun `reasoning only last message keeps earlier text`() {
        val first = node(UIMessage.assistant("Предыдущий ответ"))
        val last = node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning(reasoning = "Скрытые рассуждения"),
        )))
        assertEquals("Предыдущий ответ", metadata(first, last).preview)
    }

    @Test
    fun `unicode whitespace only text keeps earlier readable text`() {
        val first = node(UIMessage.user("Последний текст чата"))
        val last = node(UIMessage.assistant("\u00a0\u2003\u202f"))
        assertEquals("Последний текст чата", metadata(first, last).preview)
    }

    @Test
    fun `mixed message uses its last nonblank text part without tool output`() {
        val last = node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("Первый фрагмент"),
            tool(output = listOf(UIMessagePart.Text("private result"))),
            UIMessagePart.Reasoning(reasoning = "Скрытые рассуждения"),
            UIMessagePart.Text("  Последний\n фрагмент  "),
            UIMessagePart.Text("\n\t  "),
        )))
        val summary = metadata(node(UIMessage.user("Старое сообщение")), last)
        assertEquals("Последний фрагмент", summary.preview)
        assertTrue(summary.hasTools)
    }

    @Test
    fun `system and raw tool text do not replace the latest chat text`() {
        val first = node(UIMessage.user("Последний текст чата"))
        val system = node(UIMessage.system("private system prompt"))
        val result = node(UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("private raw result"))))
        assertEquals("Последний текст чата", metadata(first, system, result).preview)
    }

    @Test
    fun `reasoning and blank text only use the no text fallback`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning(reasoning = "Скрытые рассуждения"),
            UIMessagePart.Text("\n\t  "),
        ))
        val summary = metadata(node(message))
        assertEquals("Работа с инструментами", summary.preview)
        assertFalse(summary.hasTools)
    }

    @Test
    fun `unselected tool branch does not show activity`() {
        val message = UIMessage.assistant("Обычный ответ")
        val other = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(output = listOf(UIMessagePart.Text("готово")))))
        val metadata = metadata(MessageNode(messages = listOf(message, other), selectIndex = 0))
        assertFalse(metadata.hasTools)
        assertFalse(metadata.hasWorkflows)
    }

    @Test
    fun `executed tools and workflows are marked across selected history`() {
        val old = node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(output = listOf(UIMessagePart.Text("готово"))))))
        val workflow = node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(name = "workflow_run", output = listOf(UIMessagePart.Text("готово"))))))
        val last = node(UIMessage.assistant("Завершено"))
        assertTrue(metadata(old, workflow, last).hasTools)
        assertTrue(metadata(old, workflow, last).hasWorkflows)
    }

    @Test
    fun `pending tools do not claim they worked`() {
        assertFalse(metadata(node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool())))).hasTools)
    }

    @Test
    fun `broken branch and empty conversation use the no text fallback`() {
        assertEquals("Работа с инструментами", metadata(MessageNode(messages = emptyList(), selectIndex = 4)).preview)
        assertEquals("Работа с инструментами", metadata().preview)
    }

    @Test
    fun `preview limit does not split emoji surrogate pair`() {
        val text = "а".repeat(159) + "😀" + "конец"
        val preview = metadata(node(UIMessage.assistant(text))).preview
        assertEquals("а".repeat(159) + "😀…", preview)
        assertEquals(161, preview.codePointCount(0, preview.length))
    }

    @Test
    fun `time uses local zone and compact Russian date`() {
        val zone = ZoneId.of("Europe/Moscow")
        val timestamp = Instant.parse("2026-10-10T22:20:00Z")
        assertEquals("01:20", chatDrawerRowTime(timestamp, LocalDate.of(2026, 10, 11), zone))
        assertEquals("11.10", chatDrawerRowTime(timestamp, LocalDate.of(2026, 10, 12), zone))
    }

    @Test
    fun `empty states distinguish all chats and folder and offer creating a chat`() {
        assertEquals("Пока нет чатов", chatDrawerEmptyState(false).title)
        assertEquals("В этой папке пусто", chatDrawerEmptyState(true).title)
        assertEquals("Начните новый чат", chatDrawerEmptyState(true).description)
        assertEquals("Новый чат", chatDrawerEmptyState(false).actionLabel)
    }

    private fun metadata(vararg nodes: MessageNode) = ChatDrawerRowMetadata.fromConversation(
        Conversation(assistantId = Uuid.random(), messageNodes = nodes.toList()),
    )

    private fun node(message: UIMessage) = MessageNode(messages = listOf(message))

    private fun tool(name: String = "battery_info", input: String = "{}", output: List<UIMessagePart> = emptyList()) =
        UIMessagePart.Tool(toolCallId = "test", toolName = name, input = input, output = output)
}
