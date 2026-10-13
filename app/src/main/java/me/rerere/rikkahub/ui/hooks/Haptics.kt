package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings

enum class HapticAction { GENERAL, SWITCH, DRAWER, LIST_ITEM, CARD, GENERATION }

fun isHapticActionEnabled(settings: DisplaySetting, action: HapticAction): Boolean =
    settings.hapticsEnabled && when (action) {
        HapticAction.GENERAL -> true
        HapticAction.SWITCH -> settings.hapticsOnSwitch
        HapticAction.DRAWER -> settings.hapticsOnDrawer
        HapticAction.LIST_ITEM -> settings.hapticsOnListItemTap
        HapticAction.CARD -> settings.hapticsOnCardTap
        HapticAction.GENERATION -> settings.enableMessageGenerationHapticEffect
    }

internal class GatedHapticFeedback(
    private val delegate: HapticFeedback,
    private val enabled: Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled) delegate.performHapticFeedback(hapticFeedbackType)
    }
}

/** Also gates existing Compose controls that read LocalHapticFeedback directly. */
@Composable
fun ProvideAppHaptics(settings: DisplaySetting, content: @Composable () -> Unit) {
    val platform = LocalHapticFeedback.current
    val gated = remember(platform, settings.hapticsEnabled) {
        GatedHapticFeedback(platform, settings.hapticsEnabled)
    }
    CompositionLocalProvider(LocalHapticFeedback provides gated, content = content)
}

@Composable
fun rememberAppHapticFeedback(action: HapticAction): HapticFeedback {
    val settings = LocalSettings.current.displaySetting
    val platform = LocalHapticFeedback.current
    val enabled = isHapticActionEnabled(settings, action)
    return remember(platform, enabled) { GatedHapticFeedback(platform, enabled) }
}
