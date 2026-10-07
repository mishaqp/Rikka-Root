package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `codex default is a single disabled built-in account provider`() {
        val codex = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Codex>().single()
        assertEquals(DEFAULT_CODEX_PROVIDER_ID, codex.id)
        assertFalse(codex.enabled)
        assertTrue(codex.builtIn)
        assertTrue(codex.models.isEmpty())
        assertEquals(DEFAULT_PROVIDERS.size, DEFAULT_PROVIDERS.map { it.id }.distinct().size)
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
