package me.rerere.rikkahub.ui.hooks

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test

class HapticsTest {
    @Test fun masterSwitchStopsAllCategoriesAndRestoresTheirIndependentChoices() {
        val selected = DisplaySetting(hapticsOnCardTap = false, hapticsOnDrawer = true,
            enableMessageGenerationHapticEffect = true)
        HapticAction.entries.forEach { action ->
            assertFalse(isHapticActionEnabled(selected.copy(hapticsEnabled = false), action))
        }
        assertFalse(isHapticActionEnabled(selected, HapticAction.CARD))
        assertTrue(isHapticActionEnabled(selected, HapticAction.DRAWER))
        assertTrue(isHapticActionEnabled(selected, HapticAction.GENERATION))
        assertFalse(isHapticActionEnabled(DisplaySetting(), HapticAction.GENERATION))
    }

    @Test fun gatedFeedbackDispatchesTheActualPlatformEventOnlyWhenEnabled() {
        val events = mutableListOf<HapticFeedbackType>()
        val platform = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
        }
        GatedHapticFeedback(platform, false).performHapticFeedback(HapticFeedbackType.LongPress)
        assertTrue(events.isEmpty())
        GatedHapticFeedback(platform, true).performHapticFeedback(HapticFeedbackType.ContextClick)
        assertEquals(listOf(HapticFeedbackType.ContextClick), events)
    }
}
