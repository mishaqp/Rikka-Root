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
            assertEquals("Вызов инструмента", summary.preview)
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
        assertEquals("Вызов инструмента", metadata(node(message)).preview)
    }

    @Test
    fun `attachment only messages have a readable preview`() {
        assertEquals("Изображение", metadata(node(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file://private"))))).preview)
        assertEquals("Документ: инструкция.pdf", metadata(node(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Document("file://private", "инструкция.pdf"))))).preview)
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
    fun `broken branch and empty conversation have a safe empty preview`() {
        assertEquals("Сообщений пока нет", metadata(MessageNode(messages = emptyList(), selectIndex = 4)).preview)
        assertEquals("Сообщений пока нет", metadata().preview)
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
