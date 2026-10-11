package me.rerere.rikkahub.ui.components.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises user intent and returned Android results without Android or Compose mocks. */
class LocalToolPermissionQueueTest {
    @Test
    fun grantedResultMergesWithToolsEnabledWhilePermissionWasOpen() {
        val running = LocalToolPermissionQueue("assistant").replace(listOf("Calendar"), emptySet()).startNext()
        val result = running.finish(running.active!!, granted = true, currentEnabled = setOf("Root"))
        assertEquals(setOf("Root", "Calendar"), result.enabled)
        assertFalse(result.queue.busy)
    }

    @Test
    fun permissionRequestsRunOneAtATime() {
        val running = LocalToolPermissionQueue("assistant")
            .replace(listOf("Calendar", "Contacts"), emptySet()).startNext()
        assertEquals("Calendar", running.active?.option)
        assertEquals(running, running.startNext())
        val finished = running.finish(running.active!!, true, emptySet())
        assertEquals("Contacts", finished.queue.startNext().active?.option)
    }

    @Test
    fun replacingPresetIgnoresLateGrantForPreviouslyRequestedTool() {
        val running = LocalToolPermissionQueue("assistant").replace(listOf("Calendar"), emptySet()).startNext()
        val previous = running.active!!
        val replaced = running.replace(listOf("Contacts"), emptySet())
        val finished = replaced.finish(previous, true, emptySet())
        assertTrue(finished.enabled.isEmpty())
        assertEquals("Contacts", finished.queue.startNext().active?.option)
    }

    @Test
    fun cancelingCategoryWaitsForOpenAndroidDialogBeforeStartingAnotherRequest() {
        val running = LocalToolPermissionQueue("assistant").replace(listOf("Calendar"), emptySet()).startNext()
        val previous = running.active!!
        val replaced = running.replace(listOf("Contacts"), emptySet())
        assertEquals(previous, replaced.startNext().active)
        assertTrue(replaced.busy)
    }

    @Test
    fun deniedPermissionLeavesToolDisabledAndContinuesQueue() {
        val running = LocalToolPermissionQueue("assistant")
            .replace(listOf("Calendar", "Contacts"), emptySet()).startNext()
        val denied = running.finish(running.active!!, false, setOf("Root"))
        assertEquals(setOf("Root"), denied.enabled)
        assertFalse("Calendar" in denied.queue.desired)
        assertEquals("Contacts", denied.queue.startNext().active?.option)
    }

    @Test
    fun callbackFromDifferentAssistantCannotChangeTools() {
        val old = LocalToolPermissionQueue("first").replace(listOf("Calendar"), emptySet()).startNext()
        val current = LocalToolPermissionQueue("second").replace(listOf("Contacts"), emptySet()).startNext()
        val ignored = current.finish(old.active!!, true, setOf("Root"))
        assertEquals(setOf("Root"), ignored.enabled)
        assertEquals(current, ignored.queue)
    }

    @Test
    fun permissionOnlyRequestDoesNotEnableDisabledTool() {
        val running = LocalToolPermissionQueue("assistant").requestPermission("Calendar").startNext()
        assertNotNull(running.active)
        val granted = running.finish(running.active!!, true, setOf("Root"))
        assertEquals(setOf("Root"), granted.enabled)
        assertFalse(granted.queue.busy)
    }

    @Test
    fun replacingWithEmptyPresetCancelsAllPendingEnables() {
        val running = LocalToolPermissionQueue("assistant")
            .replace(listOf("Calendar", "Contacts"), emptySet()).startNext()
        val canceled = running.replace(emptyList(), emptySet())
            .finish(running.active!!, true, emptySet())
        assertTrue(canceled.enabled.isEmpty())
        assertFalse(canceled.queue.busy)
    }
}
