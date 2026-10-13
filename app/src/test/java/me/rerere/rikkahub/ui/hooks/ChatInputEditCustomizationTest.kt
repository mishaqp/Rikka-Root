package me.rerere.rikkahub.ui.hooks

import android.app.Application
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatInputEditCustomizationTest {
    @Test fun explicitAssistantEditDropsOnlyThinkingCardsAndPreservesAttachmentsTextAndTheirOrder() {
        val text1 = UIMessagePart.Text("Первая часть")
        val reasoning = UIMessagePart.Reasoning("План")
        val image = UIMessagePart.Image("file:///original.png")
        val tool = UIMessagePart.Tool("tool", "root_exec", "{}")
        val file = UIMessagePart.Document("file:///original.txt", "original.txt", "text/plain")
        val server = UIMessagePart.ServerTool("server", "search", status = ServerToolStatus.COMPLETED)
        val text2 = UIMessagePart.Text("Старая последняя часть")
        val source = listOf(text1, reasoning, image, tool, file, server, text2)
        val input = ChatInputState()
        input.editingMessage = Uuid.random()
        input.setContents(source)
        input.setMessageText("Изменённая последняя часть")
        val normal = input.getContents()
        assertEquals(source.dropLast(1), normal.dropLast(1))
        assertEquals(listOf(text1, image, file, UIMessagePart.Text("Изменённая последняя часть")),
            input.getContents(keepThinkingAndToolCardsWhenEditing = false))
        assertEquals(normal, input.getContents())
        assertEquals(7, source.size)
        assertFalse(input.shouldDeleteFileOnRemove(image))
        assertFalse(input.shouldDeleteFileOnRemove(file))
    }

    @Test fun settingCannotRemovePartsFromAQueuedOrNewMessage() {
        val parts = listOf(UIMessagePart.Text("Текст"), UIMessagePart.Tool("tool", "root_exec", "{}"),
            UIMessagePart.Reasoning("План"))
        val input = ChatInputState()
        input.setContents(parts)
        assertEquals(parts, input.getContents(keepThinkingAndToolCardsWhenEditing = false))
    }
}
