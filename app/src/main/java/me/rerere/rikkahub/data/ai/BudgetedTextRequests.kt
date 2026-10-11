package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.boundedCustomBodies

private fun TextGenerationParams.bounded(budget: TokenBudgetLedger?, reservation: TokenBudgetLedger.Reservation?) =
    if (budget == null || reservation == null) this else copy(
        maxTokens = reservation.maxTokens,
        customBody = if (budget.enabled && budget.hardCap != null)
            boundedCustomBodies(customBody, reservation.maxTokens) else customBody,
    )

/** Auxiliary requests reserve from the caller's ledger; an absent context preserves standalone use. */
internal suspend fun <T : ProviderSetting> generateTextWithBudget(
    provider: Provider<T>,
    providerSetting: T,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    budget: TokenBudgetLedger?,
): TextGenerationResult {
    val reservation = budget?.reserve(AutoContextCompression.estimateTokens(messages), params.maxTokens)
    var failure: Throwable? = null
    try {
        currentCoroutineContext().ensureActive()
        budget?.ensureCanContinue()
        reservation?.markSubmitted()
        return provider.generateText(providerSetting, messages, params.bounded(budget, reservation)).also {
            reservation?.observe(it.usage)
            reservation?.markCompleted()
        }
    } catch (error: Throwable) {
        val primary = try {
            currentCoroutineContext().ensureActive()
            error
        } catch (cancelled: CancellationException) { cancelled }
        failure = primary
        throw primary
    } finally {
        reservation?.closePreserving(failure)
    }
}

/** Collect in the existing Flow context so translation callbacks and emissions remain valid. */
internal suspend fun <T : ProviderSetting> collectTextWithBudget(
    provider: Provider<T>,
    providerSetting: T,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    budget: TokenBudgetLedger?,
    onChunk: suspend (StreamChunk) -> Unit,
) {
    val reservation = budget?.reserve(AutoContextCompression.estimateTokens(messages), params.maxTokens)
    var failure: Throwable? = null
    try {
        currentCoroutineContext().ensureActive()
        budget?.ensureCanContinue()
        reservation?.markSubmitted()
        provider.streamText(providerSetting, messages, params.bounded(budget, reservation)).collect { chunk ->
            if (chunk is StreamChunk.Usage) reservation?.observe(chunk.usage)
            onChunk(chunk)
            budget?.ensureCanContinue()
        }
        reservation?.markCompleted()
    } catch (error: Throwable) {
        val primary = try {
            currentCoroutineContext().ensureActive()
            error
        } catch (cancelled: CancellationException) { cancelled }
        failure = primary
        throw primary
    } finally {
        reservation?.closePreserving(failure)
    }
}
