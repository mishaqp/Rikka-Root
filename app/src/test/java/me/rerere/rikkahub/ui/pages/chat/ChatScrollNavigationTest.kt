package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
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

    @Test fun `navigation modes retain scrolling behavior and honor explicit always and never`() {
        assertFalse(chatMessageNavigationVisible(MessageNavButtonsMode.SCROLL, false))
        assertTrue(chatMessageNavigationVisible(MessageNavButtonsMode.SCROLL, true))
        assertTrue(chatMessageNavigationVisible(MessageNavButtonsMode.ALWAYS, false))
        assertTrue(chatMessageNavigationVisible(MessageNavButtonsMode.ALWAYS, true))
        assertFalse(chatMessageNavigationVisible(MessageNavButtonsMode.NEVER, false))
        assertFalse(chatMessageNavigationVisible(MessageNavButtonsMode.NEVER, true))
    }

    @Test fun `idle delay pauses following and a new scroll cancels pending resume`() = runTest {
        val scrolling = MutableStateFlow(false)
        var ready = false
        backgroundScope.launch { observeChatAutoFollowReady(scrolling, 2) { ready = it } }
        runCurrent()
        assertTrue("Opening a chat must not delay following before any user scroll", ready)
        scrolling.value = true
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(4_000)
        runCurrent()
        assertFalse(ready)
        scrolling.value = false
        runCurrent()
        advanceTimeBy(1_500)
        scrolling.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(ready)
        scrolling.value = false
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(ready)
    }

    @Test fun `zero idle delay preserves immediate legacy following after scrolling`() = runTest {
        val scrolling = MutableStateFlow(false)
        var ready = false
        backgroundScope.launch { observeChatAutoFollowReady(scrolling, 0) { ready = it } }
        runCurrent()
        assertTrue(ready)
        scrolling.value = true
        runCurrent()
        assertFalse(ready)
        scrolling.value = false
        runCurrent()
        assertTrue(ready)
    }

    @Test fun `long idle choices wait for the exact selected duration beyond thirty seconds`() = runTest {
        listOf(32, 64).forEach { seconds ->
            val scrolling = MutableStateFlow(false)
            var ready = false
            val observer = backgroundScope.launch { observeChatAutoFollowReady(scrolling, seconds) { ready = it } }
            runCurrent()
            assertTrue(ready)
            scrolling.value = true
            runCurrent()
            assertFalse(ready)
            scrolling.value = false
            runCurrent()
            advanceTimeBy(31_000)
            runCurrent()
            assertFalse("A $seconds-second choice must still pause after 31 seconds", ready)
            advanceTimeBy(seconds * 1_000L - 31_001)
            runCurrent()
            assertFalse("Following stays paused until the final millisecond", ready)
            advanceTimeBy(1)
            runCurrent()
            assertTrue("Following resumes after exactly $seconds seconds", ready)
            observer.cancel()
        }
    }

}
