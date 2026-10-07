package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class CodexProviderConfigureTest {
    @Test
    fun `repeated refresh does not duplicate new models or change their ids`() {
        val first = Model(modelId = "codex-model")
        val refreshed = listOf(first, first.copy(id = Uuid.random()))
        val merged = mergeCodexModels(emptyList(), refreshed)
        assertEquals(listOf(first), merged)
        assertEquals(first.id, mergeCodexModels(merged, refreshed).single().id)
    }

    @Test
    fun `matching local aliases retain both UUIDs and display names`() {
        val first = Model(modelId = "codex-model", displayName = "First alias")
        val second = Model(modelId = "codex-model", displayName = "Second alias")
        val merged = mergeCodexModels(listOf(first, second), listOf(Model(modelId = "codex-model")))
        assertEquals(listOf(first.id, second.id), merged.map { it.id })
        assertEquals(listOf("First alias", "Second alias"), merged.map { it.displayName })
    }

    @Test
    fun `model refresh preserves local settings and missing models`() {
        val existingId = Uuid.random()
        val existing = Model(
            modelId = "gpt-5-codex",
            displayName = "My Codex",
            id = existingId,
            type = ModelType.EMBEDDING,
            customHeaders = listOf(CustomHeader("X-Test", "value")),
            customBodies = listOf(CustomBody("test", JsonPrimitive(true))),
            inputModalities = listOf(Modality.TEXT),
            abilities = emptyList(),
            tools = setOf(BuiltInTools.Search),
            providerOverwrite = ProviderSetting.OpenAI(name = "Local override"),
        )
        val missing = Model(modelId = "locally-kept", displayName = "Local model")
        val refreshed = Model(
            modelId = "gpt-5-codex",
            displayName = "Updated name",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh"),
        )
        val added = Model(modelId = "gpt-5.1-codex")

        val merged = mergeCodexModels(
            existing = listOf(existing, missing),
            refreshed = listOf(refreshed, added),
        )

        assertEquals(existingId, merged[0].id)
        assertEquals("My Codex", merged[0].displayName)
        assertEquals(ModelType.EMBEDDING, merged[0].type)
        assertEquals(existing.customHeaders, merged[0].customHeaders)
        assertEquals(existing.customBodies, merged[0].customBodies)
        assertEquals(existing.tools, merged[0].tools)
        assertEquals(existing.providerOverwrite, merged[0].providerOverwrite)
        assertEquals(refreshed.inputModalities, merged[0].inputModalities)
        assertEquals(refreshed.abilities, merged[0].abilities)
        assertEquals(refreshed.supportedReasoningEfforts, merged[0].supportedReasoningEfforts)
        assertEquals(missing, merged[1])
        assertEquals(added, merged[2])
    }
}
