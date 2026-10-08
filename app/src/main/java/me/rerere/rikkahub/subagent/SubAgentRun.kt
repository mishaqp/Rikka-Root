package me.rerere.rikkahub.subagent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting

enum class SubAgentStatus { PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED, TIMED_OUT, CANCELLED, INDETERMINATE;
    val terminal: Boolean get() = this != PENDING && this != RUNNING
}
data class SubAgentOwner(val assistantId: String, val conversationId: String)
data class SubAgentRun(
    val id: String, val owner: SubAgentOwner, val label: String, val modelId: String,
    val status: SubAgentStatus = SubAgentStatus.PENDING, val result: String? = null,
    val errorCode: String? = null, val noResult: Boolean = false,
    val startedAtMs: Long = System.currentTimeMillis(), val finishedAtMs: Long? = null,
    val tokensIn: Long = 0, val tokensOut: Long = 0, val tripCount: Int = 0,
)
data class SubAgentRequest(
    val task: String, val modelId: String? = null, val systemPrompt: String? = null,
    val tools: List<String>? = null, val runInBackground: Boolean = false,
    val noResult: Boolean = false, val timeoutSeconds: Int = 300,
    val maxTrips: Int = 12, val label: String? = null,
)
internal fun validateSubAgentRequest(request: SubAgentRequest): String? = when {
    request.task.isBlank() || request.task.length > 100_000 -> "invalid_task"
    request.timeoutSeconds !in 1..480 -> "invalid_timeout"
    request.maxTrips !in 1..32 -> "invalid_max_trips"
    request.modelId != null && request.modelId.isBlank() -> "invalid_model"
    request.label != null && request.label.length > 60 -> "invalid_label"
    request.systemPrompt != null && request.systemPrompt.length > 32_000 -> "invalid_system_prompt"
    request.tools != null && (request.tools.size > 256 || request.tools.any { it.isBlank() }) -> "invalid_tools"
    else -> null
}

/** Only an exact UUID or unique exact provider model ID/display name is selectable. */
internal fun resolveSubAgentModel(providers: List<ProviderSetting>, requested: String?, fallback: String?): Model? {
    val key = requested ?: fallback ?: return null
    if (key.isBlank()) return null
    val models = providers.filter { it.enabled }.flatMap { it.models }.filter { it.type == ModelType.CHAT }
    val ids = models.filter { it.id.toString() == key }
    if (ids.isNotEmpty()) return ids.singleOrNull()
    return models.filter { it.modelId == key || it.displayName.equals(key, ignoreCase = true) }.singleOrNull()
}
