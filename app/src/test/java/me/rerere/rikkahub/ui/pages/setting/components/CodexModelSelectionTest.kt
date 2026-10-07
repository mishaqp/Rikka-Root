package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexModelSelectionTest {
    @Test
    fun `preferred display name uses actual account model id and UUID`() {
        val fallback = Model(modelId = "account-fallback")
        val preferred = Model(modelId = "account-owned-preview", displayName = "GPT 6.1 Sol")
        assertSame(preferred, preferredCodexModel(listOf(fallback, preferred)))
        val byId = preferred.copy(modelId = "gpt-6.1-sol", displayName = "")
        assertSame(byId, preferredCodexModel(listOf(byId)))
    }

    @Test
    fun `missing requested model falls back to first actual usable chat catalog entry`() {
        val embedding = Model(modelId = "embedding", type = ModelType.EMBEDDING)
        val first = Model(modelId = "actual-first")
        val later = Model(modelId = "actual-later")
        assertSame(first, preferredCodexModel(listOf(Model(), embedding, first, later)))
        assertNull(preferredCodexModel(emptyList()))
        assertNull(preferredCodexModel(listOf(embedding)))
    }

    @Test
    fun `first successful login selects fetched preferred model and enables provider`() {
        val provider = ProviderSetting.Codex()
        val preferred = Model(modelId = "returned-slug", displayName = "GPT 6.1 Sol")
        val conflictingOverride = Uuid.random()
        val current = Assistant(chatModelId = conflictingOverride)
        val other = Assistant(chatModelId = Uuid.random())
        val settings = Settings(
            providers = listOf(provider), assistants = listOf(current, other), assistantId = current.id,
        )
        val updated = settings.withRefreshedCodexModels(provider.id, listOf(preferred), afterLogin = true)
        assertEquals(preferred.id, updated.chatModelId)
        assertNull(updated.assistants.first().chatModelId)
        assertEquals(other, updated.assistants[1])
        assertTrue(updated.providers.single().enabled)
        assertEquals(preferred.modelId, updated.providers.single().models.single().modelId)
    }

    @Test
    fun `first manual catalog refresh sets actual default without enabling provider`() {
        val provider = ProviderSetting.Codex()
        val returned = Model(modelId = "actual-first")
        val settings = Settings(providers = listOf(provider))
        val updated = settings.withRefreshedCodexModels(provider.id, listOf(returned))
        assertEquals(returned.id, updated.chatModelId)
        assertFalse(updated.providers.single().enabled)
    }

    @Test
    fun `empty catalog changes neither defaults nor enable choice`() {
        val provider = ProviderSetting.Codex()
        val settings = Settings(providers = listOf(provider))
        val updated = settings.withRefreshedCodexModels(provider.id, emptyList(), afterLogin = true)
        assertEquals(settings, updated)
    }

    @Test
    fun `later refresh preserves global assistant and provider enabled choices`() {
        val selected = Model(modelId = "selected-before")
        val provider = ProviderSetting.Codex(models = listOf(selected), enabled = false)
        val current = Assistant(chatModelId = selected.id)
        val settings = Settings(
            chatModelId = selected.id, providers = listOf(provider),
            assistants = listOf(current), assistantId = current.id,
        )
        val newPreferred = Model(modelId = "actual-new", displayName = "GPT-6.1 Sol")
        val refreshedSelected = selected.copy(id = Uuid.random(), supportedReasoningEfforts = listOf("high"))
        val updated = settings.withRefreshedCodexModels(
            provider.id, listOf(newPreferred, refreshedSelected), afterLogin = true,
        )
        assertEquals(settings.chatModelId, updated.chatModelId)
        assertEquals(current, updated.assistants.single())
        assertFalse(updated.providers.single().enabled)
        val retained = updated.providers.single().models.first { it.modelId == selected.modelId }
        assertEquals(selected.id, retained.id)
        assertEquals(listOf("high"), retained.supportedReasoningEfforts)
    }

    @Test
    fun `later refresh preserves selection of a different provider`() {
        val existing = Model(modelId = "existing-codex")
        val codex = ProviderSetting.Codex(models = listOf(existing))
        val external = Model(modelId = "user-choice")
        val externalProvider = ProviderSetting.OpenAI(models = listOf(external))
        val settings = Settings(chatModelId = external.id, providers = listOf(codex, externalProvider))
        val updated = settings.withRefreshedCodexModels(
            codex.id, listOf(Model(modelId = "gpt-6.1-sol")),
        )
        assertEquals(external.id, updated.chatModelId)
        assertEquals(externalProvider, updated.providers[1])
    }
}
