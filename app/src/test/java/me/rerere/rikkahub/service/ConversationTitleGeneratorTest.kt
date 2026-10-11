package me.rerere.rikkahub.service

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTitleGeneratorTest {
    private val model = Model(modelId = "fast-title")
    private val configured = Settings(
        fastModelId = model.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
    )

    @Test fun `no fast model uses first user text without an AI request or an error`() = runBlocking {
        var requests = 0
        val result = generateConversationTitle(
            Settings(providers = emptyList()), conversation("Первое сообщение пользователя"),
        ) {
            requests++
            error("An unavailable model must not be called")
        }
        assertEquals("Первое сообщение пользователя", result)
        assertEquals(0, requests)
    }

    @Test fun `removed provider leaves no usable fast model and falls back silently`() = runBlocking {
        var requests = 0
        val settings = configured.copy(providers = emptyList())
        assertFalse(settings.hasFastModelTarget())
        assertEquals("Сохрани название", generateConversationTitle(settings, conversation("Сохрани название")) {
            requests++
            error("The removed provider must not be called")
        })
        assertEquals(0, requests)
    }

    @Test fun `disabled automatic AI titles never make a provider request`() = runBlocking {
        var requests = 0
        val settings = configured.copy(displaySetting = DisplaySetting(enableAutoChatTitles = false))
        assertEquals("Название без ИИ", generateConversationTitle(settings, conversation("Название без ИИ")) {
            requests++
            error("The toggle disables AI title requests")
        })
        assertEquals(0, requests)
    }

    @Test fun `configured fast model preserves AI title generation`() = runBlocking {
        var requests = 0
        assertEquals("Заголовок ИИ", generateConversationTitle(configured, conversation("Сообщение")) { target ->
            requests++
            assertEquals(model, target.model)
            assertEquals(configured.providers.single(), target.provider)
            "Заголовок ИИ"
        })
        assertEquals(1, requests)
        assertTrue(configured.hasFastModelTarget())
    }

    @Test fun `background title generation preserves an existing manual title`() = runBlocking {
        val original = conversation("Текст").copy(title = "Моё название")
        assertNull(generateConversationTitle(configured, original) { error("An existing title must be kept") })
    }

    @Test fun `manual regenerate without a fast model preserves the current title`() = runBlocking {
        val original = conversation("Текст").copy(title = "Моё название")
        assertNull(generateConversationTitle(Settings(providers = emptyList()), original, force = true) {
            error("A missing fast model must not be called")
        })
    }

    @Test fun `manual regenerate uses the configured model for an existing title`() = runBlocking {
        val original = conversation("Текст").copy(title = "Моё название")
        assertEquals("Новое название", generateConversationTitle(configured, original, force = true) {
            "Новое название"
        })
    }

    @Test fun `disabled AI title setting also prevents a forced AI request`() = runBlocking {
        val settings = configured.copy(displaySetting = DisplaySetting(enableAutoChatTitles = false))
        assertEquals("Первое сообщение", generateConversationTitle(settings, conversation("Первое сообщение"), force = true) {
            error("The setting forbids AI title requests")
        })
    }

    @Test fun `local title chooses the first user message and normalizes line breaks and spaces`() {
        val source = conversation("  Один\n\tдва\r\nтри\u00a0четыре ").copy(messageNodes = listOf(
            UIMessage.assistant("Этот ответ не является названием").toMessageNode(),
            UIMessage.user("  Один\n\tдва\r\nтри\u00a0четыре ").toMessageNode(),
            UIMessage.user("Следующее сообщение").toMessageNode(),
        ))
        assertEquals("Один два три четыре", localConversationTitle(source))
    }

    @Test fun `local title truncates at a whole word within forty characters`() {
        assertEquals("Это название из первого сообщения", localConversationTitle(conversation(
            "Это название из первого сообщения пользователя, которое слишком длинное",
        )))
    }

    @Test fun `local title does not split Unicode surrogate pairs in a long single word`() {
        val title = localConversationTitle(conversation("😀".repeat(45)))
        assertEquals("😀".repeat(40), title)
        assertEquals(40, title.codePointCount(0, title.length))
    }

    @Test fun `attachment only first message uses the conversation date`() {
        val source = conversation("Позднее сообщение").copy(messageNodes = listOf(
            UIMessage.user("").copy(parts = listOf(UIMessagePart.Image(url = "content://photo/1"))).toMessageNode(),
            UIMessage.user("Позднее сообщение").toMessageNode(),
        ))
        assertEquals("Чат от 11.10.2026", localConversationTitle(source, ZoneId.of("UTC")))
    }

    @Test fun `empty user message and no user messages use the conversation date`() {
        assertEquals("Чат от 11.10.2026", localConversationTitle(conversation(" \n "), ZoneId.of("UTC")))
        assertEquals("Чат от 11.10.2026", localConversationTitle(conversation("").copy(
            messageNodes = listOf(UIMessage.assistant("Ответ").toMessageNode()),
        ), ZoneId.of("UTC")))
    }

    @Test fun `old display settings and old backups default automatic AI titles to enabled`() {
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>("{}").enableAutoChatTitles)
        assertTrue(JsonInstant.decodeFromString<Settings>("{\"displaySetting\":{}}").displaySetting.enableAutoChatTitles)
    }

    @Test fun `disabled automatic titles survive settings serialization`() {
        val saved = configured.copy(displaySetting = DisplaySetting(enableAutoChatTitles = false))
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(saved))
        assertFalse(restored.displaySetting.enableAutoChatTitles)
    }

    @Test fun `first saved user message gets a local title even before any main model reply`() {
        val saved = applyLocalConversationTitle(Settings(providers = emptyList()), conversation("Первое сообщение"))
        assertEquals("Первое сообщение", saved.title)
        assertEquals("Первое сообщение", saved.currentMessages.single().toText())
    }

    @Test fun `disabled AI titles are applied when the user message is first saved`() {
        val settings = configured.copy(displaySetting = DisplaySetting(enableAutoChatTitles = false))
        assertEquals("Название без ответа ИИ", applyLocalConversationTitle(settings, conversation("Название без ответа ИИ")).title)
    }

    @Test fun `saved user message with configured automatic AI titles stays blank until generation`() {
        val source = conversation("Первое сообщение")
        assertTrue(applyLocalConversationTitle(configured, source) === source)
        assertEquals("", source.title)
    }

    @Test fun `local title persistence does not name empty drafts or conversations without a user message`() {
        val settings = Settings(providers = emptyList())
        val draft = conversation("").copy(messageNodes = emptyList())
        val preset = draft.copy(messageNodes = listOf(
            UIMessage.system("Инструкция").toMessageNode(),
            UIMessage.assistant("Приветствие").toMessageNode(),
        ))
        assertTrue(applyLocalConversationTitle(settings, draft) === draft)
        assertTrue(applyLocalConversationTitle(settings, preset) === preset)
    }

    @Test fun `local title persistence preserves an existing title and conversation fields`() {
        val source = conversation("Первое сообщение").copy(title = "Ручное название", isPinned = true)
        assertTrue(applyLocalConversationTitle(Settings(providers = emptyList()), source) === source)
        val untitled = source.copy(title = "")
        assertEquals(untitled.copy(title = "Первое сообщение"), applyLocalConversationTitle(Settings(providers = emptyList()), untitled))
    }

    private fun conversation(text: String) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(UIMessage.user(text).toMessageNode()),
        createAt = Instant.parse("2026-10-11T12:00:00Z"),
    )
}
