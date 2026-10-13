package me.rerere.rikkahub.browser

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BrowserLaunchJournalTest {
    @Test fun stagesDistinguishActivityAndWebViewFromAnUnobservedLaunch() {
        var now = 100L
        val journal = BrowserLaunchJournal { now }
        val id = journal.begin(BrowserLaunchOrigin.TOOL)
        assertEquals("activity_not_observed", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        journal.record(id, BrowserLaunchEvent.ON_CREATE_ENTER)
        assertEquals("activity_created_without_bind", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        assertTrue(journal.reason(id).contains("onCreate"))
        now += 125
        journal.record(id, BrowserLaunchEvent.WEBVIEW_CREATE_ENTER)
        assertEquals("webview_not_ready", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        journal.record(id, BrowserLaunchEvent.WEBVIEW_READY)
        assertEquals("webview_ready_without_bind", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        journal.record(id, BrowserLaunchEvent.CONTROLLER_BOUND)
        journal.record(id, BrowserLaunchEvent.BIND_TIMEOUT)
        assertEquals("controller_bound", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        assertEquals("125", journal.snapshot(id)["events"]!!.jsonArray.last().jsonObject["elapsed_ms"]!!.jsonPrimitive.content)
        journal.record(id, BrowserLaunchEvent.ON_DESTROY_ENTER, finishing = true, changingConfigurations = false)
        assertEquals("activity_destroyed", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        assertTrue(journal.reason(id).contains("onDestroy"))
        journal.record(id, BrowserLaunchEvent.ON_CREATE_ENTER)
        assertEquals("activity_created_without_bind", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
    }

    @Test fun delayedCallbacksDoNotChangeAnotherLaunchAndDoNotConsumeExceptionMessages() {
        val journal = BrowserLaunchJournal { 0L }
        val first = journal.begin(BrowserLaunchOrigin.TOOL)
        val second = journal.begin(BrowserLaunchOrigin.MANUAL)
        journal.record(first, BrowserLaunchEvent.ON_CREATE_ENTER)
        val secret = "https://private.example/path password=private-cookie page-content"
        journal.record(first, BrowserLaunchEvent.WEBVIEW_CREATION_FAILED, error = IllegalStateException(secret))
        assertEquals("webview_creation_failed", journal.snapshot(first)["stage"]!!.jsonPrimitive.content)
        assertEquals("activity_not_observed", journal.snapshot(second)["stage"]!!.jsonPrimitive.content)
        assertEquals("MANUAL", journal.snapshot(second)["origin"]!!.jsonPrimitive.content)
        assertTrue(journal.snapshot(first).toString().contains("java.lang.IllegalStateException"))
        for (value in listOf(secret, "private.example", "private-cookie", "page-content")) {
            assertFalse(journal.report().contains(value))
            assertFalse(journal.snapshot(first).toString().contains(value))
        }
    }

    @Test fun launchFailuresAndCancellationKeepDifferentEvidence() {
        val journal = BrowserLaunchJournal { 0L }
        val id = journal.begin(BrowserLaunchOrigin.TOOL)
        journal.record(id, BrowserLaunchEvent.START_ACTIVITY_FAILED, error = SecurityException("private"))
        assertEquals("start_activity_failed", journal.snapshot(id)["stage"]!!.jsonPrimitive.content)
        assertTrue(journal.reason(id).contains("startActivity"))
        val cancelled = journal.begin(BrowserLaunchOrigin.TOOL)
        journal.record(cancelled, BrowserLaunchEvent.LAUNCH_CANCELLED)
        assertFalse(journal.snapshot(cancelled).toString().contains("START_ACTIVITY_FAILED"))
        assertFalse(journal.report().contains("private"))
    }

    @Test fun historyIsBoundedWithoutLosingTheKnownStageOrRevivingEvictedAttempts() {
        val journal = BrowserLaunchJournal { 0L }
        val first = journal.begin(BrowserLaunchOrigin.TOOL)
        journal.record(first, BrowserLaunchEvent.CONTROLLER_BOUND)
        repeat(BrowserLaunchJournal.MAX_EVENTS + 10) { journal.record(first, BrowserLaunchEvent.WINDOW_FOCUS_CHANGED, windowFocus = true) }
        val snapshot = journal.snapshot(first)
        assertEquals(BrowserLaunchJournal.MAX_EVENTS, snapshot["events"]!!.jsonArray.size)
        assertEquals("12", snapshot["dropped_events"]!!.jsonPrimitive.content)
        assertEquals("controller_bound", snapshot["stage"]!!.jsonPrimitive.content)
        repeat(BrowserLaunchJournal.MAX_ATTEMPTS) { journal.begin(BrowserLaunchOrigin.MANUAL) }
        journal.record(first, BrowserLaunchEvent.ON_CREATE_ENTER)
        assertEquals("history_expired", journal.snapshot(first)["stage"]!!.jsonPrimitive.content)
        assertFalse(journal.report().contains("\"attempt_id\":$first,"))
    }
}
