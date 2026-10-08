package me.rerere.rikkahub.costguards

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Adapted from rikkahub-agent costguards/CostGuardTools.kt; never uses global/current/latest chat. */
fun checkTokenUsageTool(callerAssistant: Assistant, conversationId: Uuid?,
                        tokenBudget: TokenBudgetLedger? = null,
                        loadConversation: suspend (Uuid) -> Conversation?): Tool = Tool(
    name = "check_token_usage",
    description = "Расход токенов текущего диалога или общего бюджета запуска и его субагентов. " +
        "WARN: завершайте задачу; OVER_HARD: продолжение остановлено. Метрики провайдера могут поступить поздно.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("conversation_id", buildJsonObject {
            put("type", "string")
            put("description", "Необязательный UUID только текущего диалога; чужой диалог недоступен.")
        })
    }, emptyList()) },
    execute = execute@{ args ->
        fun error(code: String) = listOf(UIMessagePart.Text(buildJsonObject { put("error", code) }.toString()))
        val params = args as? JsonObject ?: return@execute error("invalid_arguments")
        val supplied = params["conversation_id"]
        val raw = (supplied as? JsonPrimitive)?.contentOrNull
        val requested = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (supplied != null && supplied != JsonNull && (requested == null || requested != conversationId)) {
            return@execute error("conversation_not_allowed")
        }
        val ledger = tokenBudget ?: currentCoroutineContext()[TokenBudgetContext]?.ledger
        val totals: TokenBudgetTracker.Totals
        if (ledger == null) {
            val id = conversationId ?: return@execute error("no_conversation")
            val conversation = loadConversation(id) ?: return@execute error("no_conversation")
            if (conversation.id != id || conversation.assistantId != callerAssistant.id) return@execute error("conversation_not_allowed")
            totals = TokenBudgetTracker.aggregate(conversation)
        } else {
            // A trusted run ledger belongs to this closure's caller, and includes side-contexts
            // that are intentionally not persisted as the parent's Room transcript.
            totals = TokenBudgetTracker.Totals(0, 0, ledger.snapshot().spentTokens, 0, 0)
        }
        val state = ledger?.snapshot()
        val enabled = ledger?.enabled ?: callerAssistant.localTools.contains(LocalToolOption.CostGuards)
        val soft = if (ledger != null) ledger.softCap else callerAssistant.tokenBudgetSoftCap
        val hard = if (ledger != null) ledger.hardCap else callerAssistant.tokenBudgetHardCap
        val status = state?.status ?: if (enabled) TokenBudgetTracker.classify(totals, soft, hard)
            else TokenBudgetTracker.BudgetStatus.NO_BUDGET
        val payload = buildJsonObject {
            conversationId?.let { put("conversation_id", it.toString()) }
            put("total_tokens", totals.totalTokens)
            put("budget_enabled", enabled)
            put("scope", if (ledger != null) "shared_run" else "selected_branch")
            if (ledger == null) {
                put("input_tokens", totals.inputTokens)
                put("output_tokens", totals.outputTokens)
                put("per_message_max", totals.perMessageMax)
                put("message_count", totals.messageCount)
            }
            soft?.let { put("soft_cap", it) }
            hard?.let { put("hard_cap", it) }
            state?.let {
                put("reserved_tokens", it.reservedTokens)
                it.remainingTokens?.let { remaining -> put("remaining_tokens", remaining) }
            }
            put("status", status.name)
            put("usage_is_exact_billing", false)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
