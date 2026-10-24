package me.rerere.rikkahub.data.kimi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import java.util.UUID

@Serializable
enum class KimiRegion(val oauthHost: String, val apiBase: String, val label: String) {
    CN("https://auth.kimi.com", "https://api.kimi.com/coding/v1", "Kimi.com (Китай)"),
    GLOBAL("https://auth.kimi.ai", "https://api.kimi.ai/coding/v1", "Kimi.ai (международный)"),
}

@Serializable
data class KimiTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String = "",
) {
    override fun toString() = "KimiTokens([redacted], expiresAt=$expiresAt)"
}

@Serializable
data class KimiAccount(
    val region: KimiRegion,
    val tokens: KimiTokens,
    val sessionId: String = UUID.randomUUID().toString(),
    val name: String = "Kimi",
    val userId: String = "",
    val membership: String = "",
    val needsLogin: Boolean = false,
    val models: List<KimiModelInfo> = emptyList(),
    val quotas: List<KimiQuota> = emptyList(),
    val checkedAt: Long? = null,
)

@Serializable
data class KimiQuota(val name: String, val usedRatio: Double, val resetAt: String? = null)

@Serializable
data class KimiModelInfo(
    val id: String,
    val displayName: String = id,
    val protocol: String = "chat_completions",
    val tools: Boolean = true,
    val reasoning: Boolean = false,
    val images: Boolean = false,
    val thinkingType: String = "none",
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
) {
    // The documented Pro contract remains mandatory even with old/incomplete catalog metadata.
    internal val requiresThinking get() = thinkingType == "only" || id in KIMI_PRO_MODELS
    internal val effectiveEfforts get() = efforts.ifEmpty { if (id in KIMI_PRO_MODELS) listOf("low", "high", "max") else emptyList() }
    internal val effectiveDefaultEffort get() = defaultEffort ?: if (id in KIMI_PRO_MODELS) {
        "max".takeIf { it in effectiveEfforts } ?: effectiveEfforts.firstOrNull()
    } else null

    fun toModel() = Model(modelId = id, displayName = displayName,
        abilities = buildList {
            if (tools) add(ModelAbility.TOOL)
            if (reasoning || requiresThinking) add(ModelAbility.REASONING)
        },
        inputModalities = if (images) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT),
        supportedReasoningEfforts = effectiveEfforts)
}

private val KIMI_PRO_MODELS = setOf("k3.1-pro", "k3.1-pro-highspeed")

internal fun parseKimiModels(payload: JsonObject): List<KimiModelInfo> {
    val data = payload["data"] as? JsonArray ?: error("Kimi вернул некорректный каталог моделей.")
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.text("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val reasoning = item["reasoning"] as? JsonObject
        val thinkingType = reasoning?.text("type") ?: if (item.flag("supports_reasoning")) "both" else "none"
        val effort = reasoning?.get("effort") as? JsonObject
        val legacyEffort = item["think_efforts"] as? JsonObject
        val efforts = (effort?.get("valid") ?: legacyEffort?.get("valid_efforts") ?: item["support_efforts"]).strings()
        val modalities = item["modalities"] as? JsonObject
        KimiModelInfo(
            id = id, displayName = item.text("display_name")?.takeIf(String::isNotBlank) ?: id,
            protocol = item.text("protocol")?.takeIf(String::isNotBlank) ?: "chat_completions",
            tools = (item["supports_tool_use"] as? JsonPrimitive)?.booleanOrNull ?: true,
            reasoning = thinkingType in setOf("only", "both"),
            images = modalities?.get("input").strings().contains("image") || item.flag("supports_image_in"),
            thinkingType = thinkingType, efforts = efforts,
            defaultEffort = (effort?.text("default") ?: legacyEffort?.text("default_effort") ?: item.text("default_effort"))
                ?.takeIf { efforts.isEmpty() || it in efforts },
        )
    }.distinctBy(KimiModelInfo::id)
}

internal fun parseKimiQuotas(payload: JsonObject): List<KimiQuota> {
    val usages = payload["usages"] as? JsonObject ?: return emptyList()
    return listOf("limit_5h" to "5 часов", "limit_7d" to "7 дней",
        "limit_month_total" to "Месяц", "limit_month_code" to "Программирование за месяц").mapNotNull { (key, name) ->
        val item = usages[key] as? JsonObject ?: return@mapNotNull null
        val ratio = item.text("used_ratio")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        KimiQuota(name, ratio, item.text("reset_time"))
    }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}.orEmpty()
