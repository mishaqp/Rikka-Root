package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollNavigationTest {
    @Test
    fun `controls stay visible throughout a long scroll and hide after idle`() = runTest {
        val activity = MutableStateFlow(ChatScrollNavigationActivity())
        var visible = false
        backgroundScope.launch { observeChatScrollNavigation(activity) { visible = it } }
        runCurrent()
        assertFalse(visible)

        activity.value = activity.value.copy(scrolling = true)
        runCurrent()
        assertTrue(visible)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(visible)

        activity.value = activity.value.copy(scrolling = false)
        runCurrent()
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(visible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(visible)
    }

    @Test
    fun `movement and taps renew the idle timeout`() = runTest {
        val activity = MutableStateFlow(ChatScrollNavigationActivity())
        var visible = false
        backgroundScope.launch { observeChatScrollNavigation(activity) { visible = it } }
        runCurrent()
        activity.value = activity.value.copy(firstVisibleOffset = 20)
        runCurrent()
        assertTrue(visible)
        advanceTimeBy(1_000)

        activity.value = activity.value.copy(firstVisibleOffset = 40)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(visible)

        activity.value = activity.value.copy(interactionRevision = 1)
        runCurrent()
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(visible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(visible)
    }

    @Test
    fun `a restored scroll position does not show idle controls`() = runTest {
        val activity = MutableStateFlow(ChatScrollNavigationActivity(firstVisibleIndex = 8, firstVisibleOffset = 30))
        var visible = false
        backgroundScope.launch { observeChatScrollNavigation(activity) { visible = it } }
        runCurrent()
        assertFalse(visible)
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(visible)
    }

}
