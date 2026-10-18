package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.Assistant

/** Apply only this screen's edits to fresh data; permission callbacks can arrive much later. */
internal fun mergeToolUiChanges(current: Assistant, before: Assistant, requested: Assistant): Assistant {
    val removed = before.localTools.toSet() - requested.localTools.toSet()
    val added = requested.localTools.toSet() - before.localTools.toSet()
    return current.copy(
        localTools = (current.localTools.filterNot { it in removed } + added).distinct(),
        tokenBudgetSoftCap = if (before.tokenBudgetSoftCap != requested.tokenBudgetSoftCap)
            requested.tokenBudgetSoftCap else current.tokenBudgetSoftCap,
        tokenBudgetHardCap = if (before.tokenBudgetHardCap != requested.tokenBudgetHardCap)
            requested.tokenBudgetHardCap else current.tokenBudgetHardCap,
        rootMode = if (before.rootMode != requested.rootMode) requested.rootMode else current.rootMode,
        subAgentConcurrencyLimit = if (before.subAgentConcurrencyLimit != requested.subAgentConcurrencyLimit)
            requested.subAgentConcurrencyLimit else current.subAgentConcurrencyLimit,
    )
}
