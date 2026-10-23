package me.rerere.rikkahub.data.kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import org.junit.Assert.*
import org.junit.Test

class KimiModelsTest {
    @Test fun `account catalog carries model protocols tools images and supported reasoning`() {
        val payload = Json.parseToJsonElement("""{"data":[
          {"id":"kimi-k3","display_name":"Kimi K3","protocol":"anthropic","limit":{"context":1000000},
           "reasoning":{"type":"only","effort":{"valid":["low","high","max"],"default":"max"}},
           "supports_tool_use":true,"modalities":{"input":["text","image"]}},
          {"id":"response-model","protocol":"response","reasoning":{"type":"none"},"supports_tool_use":false},
          {"id":"kimi-for-coding","supports_reasoning":true,"supports_image_in":true}
        ]}""").jsonObject
        val catalog = parseKimiModels(payload)
        assertEquals(listOf("kimi-k3", "response-model", "kimi-for-coding"), catalog.map { it.id })
        assertEquals("anthropic", catalog[0].protocol)
        assertEquals("only", catalog[0].thinkingType)
        assertEquals(listOf("low", "high", "max"), catalog[0].toModel().supportedReasoningEfforts)
        assertTrue(catalog[0].toModel().abilities.containsAll(listOf(ModelAbility.TOOL, ModelAbility.REASONING)))
        assertTrue(catalog[0].toModel().inputModalities.contains(Modality.IMAGE))
        assertFalse(catalog[1].toModel().abilities.contains(ModelAbility.REASONING))
        assertFalse(catalog[1].toModel().abilities.contains(ModelAbility.TOOL))
        assertTrue(catalog[2].toModel().abilities.contains(ModelAbility.REASONING))
    }

    @Test fun `malformed catalog does not silently produce a successful empty discovery`() {
        assertTrue(runCatching { parseKimiModels(Json.parseToJsonElement("{}").jsonObject) }.isFailure)
        assertTrue(parseKimiModels(Json.parseToJsonElement("""{"data":[]}""").jsonObject).isEmpty())
        val models = parseKimiModels(Json.parseToJsonElement("""{"data":[{"id":""},{"id":"ok"},{"id":"ok"},null]}""").jsonObject)
        assertEquals(listOf("ok"), models.map { it.id })
    }

    @Test fun `quota parsing preserves exhausted windows and actual reset time`() {
        val quotas = parseKimiQuotas(Json.parseToJsonElement("""{"usages":{
          "limit_5h":{"used_ratio":1,"reset_time":"2026-10-24T00:00:00Z"},
          "limit_7d":{"used_ratio":"0.25"},"limit_month_code":{"used_ratio":0.5}
        }}""").jsonObject)
        assertEquals(3, quotas.size)
        assertEquals(1.0, quotas.first().usedRatio, 0.0)
        assertEquals("2026-10-24T00:00:00Z", quotas.first().resetAt)
        assertEquals(0.25, quotas[1].usedRatio, 0.0)
    }
}
