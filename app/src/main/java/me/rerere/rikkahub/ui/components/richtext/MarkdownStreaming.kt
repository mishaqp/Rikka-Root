package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf

/** Only text added by an active response receives the streaming fade. */
val LocalMarkdownStreaming = staticCompositionLocalOf { false }

internal fun appendedMarkdownRange(previous: String, current: String): IntRange? =
    if (current.length > previous.length && current.startsWith(previous)) previous.length until current.length else null
