package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDrawerChromeTest {
    @Test fun `unchecked root never displays a chip or starts a check`() {
        assertNull(chatDrawerRootStatus(RootStatus.UNCHECKED))
        assertNull(chatDrawerRootStatus(RootStatus.CHECKING))
    }

    @Test fun `verified root displays its cached result with meaningful colors`() {
        val ready = chatDrawerRootStatus(RootStatus.READY)!!
        assertEquals("Активен", ready.text)
        assertEquals(SettingsDashboardStatusTone.Success, ready.tone)
        for (status in listOf(RootStatus.UNAVAILABLE, RootStatus.DENIED_OR_FAILED, RootStatus.NOT_ROOT)) {
            assertEquals(status.name, SettingsDashboardStatusTone.Error, chatDrawerRootStatus(status)?.tone)
        }
        assertEquals(SettingsDashboardStatusTone.Warning, chatDrawerRootStatus(RootStatus.TIMED_OUT)?.tone)
    }

    @Test fun `model chip shows conversation snapshot instead of the current default`() {
        val default = Model(modelId = "default", displayName = "Новая модель")
        val pinned = Model(modelId = "pinned", displayName = "Модель этого чата")
        val assistant = Assistant(chatModelId = default.id)
        val settings = Settings(
            assistantId = assistant.id, assistants = listOf(assistant), chatModelId = default.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(default, pinned))),
        )
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = pinned.id))
        assertEquals("Модель этого чата", chatDrawerModelName(settings, conversation))
    }

    @Test fun `empty models hide the chip and unnamed models use their identifier`() {
        val assistant = Assistant()
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant), providers = emptyList())
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList())
        assertNull(chatDrawerModelName(settings, conversation))
        val model = Model(modelId = "gpt-model")
        assertEquals("gpt-model", chatDrawerModelName(settings.copy(chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))), conversation))
    }
}
