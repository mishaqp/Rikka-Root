package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.*
import org.junit.Assert.*
import org.junit.Test

class KimiModelSelectionTest {
    @Test fun `catalog refresh preserves model identities custom settings and active chat`() {
        val current = Model(modelId = "kimi-k3", displayName = "My model",
            customHeaders = listOf(CustomHeader("X-Test", "kept")))
        val provider = ProviderSetting.KimiCode(id = DEFAULT_KIMI_PROVIDER_ID, enabled = true, models = listOf(current))
        val settings = Settings(providers = listOf(provider), chatModelId = current.id)
        val fresh = Model(modelId = "kimi-k3", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
        val updated = settings.withRefreshedKimiModels(provider.id, listOf(fresh, Model(modelId = "future")))
        val models = updated.providers.single().models
        assertEquals(current.id, models.first().id)
        assertEquals("My model", models.first().displayName)
        assertEquals(current.customHeaders, models.first().customHeaders)
        assertEquals(fresh.abilities, models.first().abilities)
        assertEquals(current.id, updated.chatModelId)
        assertEquals(2, models.size)
    }

    @Test fun `first usable login catalog enables Kimi and selects real account model`() {
        val provider = ProviderSetting.KimiCode(id = DEFAULT_KIMI_PROVIDER_ID)
        val settings = Settings(providers = listOf(provider))
        val catalog = listOf(Model(modelId = "kimi-for-coding"))
        val updated = settings.withRefreshedKimiModels(provider.id, catalog, afterLogin = true)
        assertTrue(updated.providers.single().enabled)
        assertEquals(catalog.single().id, updated.chatModelId)
        assertFalse(settings.withRefreshedKimiModels(provider.id, emptyList(), true).providers.single().enabled)
    }

    @Test fun `existing installations include one disabled built in Kimi provider without credentials`() {
        val provider = DEFAULT_PROVIDERS.single { it.id == DEFAULT_KIMI_PROVIDER_ID }
        assertTrue(provider is ProviderSetting.KimiCode)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
    }
}
