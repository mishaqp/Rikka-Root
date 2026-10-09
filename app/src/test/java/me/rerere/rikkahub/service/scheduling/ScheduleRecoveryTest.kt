package me.rerere.rikkahub.service.scheduling

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduleRecoveryTest {
    @Test fun supportedRecoveryBroadcastsRouteClockChangesWithoutRunningBootWorkflows() = runBlocking {
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            ScheduleRecoveryReason.EXACT_ACCESS_GRANTED_ACTION)
        for (action in actions) {
            val calls = mutableListOf<String>()
            val reason = requireNotNull(ScheduleRecoveryReason.fromAction(action))
            recoverSchedules(reason,
                { changed -> calls += "cron:$changed" },
                { changed -> calls += "workflows:$changed" },
                { calls += "boot" })
            val clockChanged = action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED
            assertEquals("cron:$clockChanged", calls[0])
            assertEquals("workflows:$clockChanged", calls[1])
            assertEquals(action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED,
                "boot" in calls)
        }
    }

    @Test fun unrelatedBroadcastsCannotRearmOrRunActions() {
        assertNull(ScheduleRecoveryReason.fromAction(Intent.ACTION_SCREEN_ON))
        assertNull(ScheduleRecoveryReason.fromAction(null))
    }

    @Test fun cronRecoveryFailureDoesNotPreventIndependentWorkflowRecovery() = runBlocking {
        var workflowRecovered = false
        recoverSchedules(ScheduleRecoveryReason.TIMEZONE_CHANGED,
            { throw IllegalStateException("cron unavailable") },
            { workflowRecovered = true },
            { fail("time change must not execute boot workflow") })
        assertTrue(workflowRecovered)
    }

    @Test fun cancellationStopsRecoveryWithoutExecutingLaterDomain() = runBlocking {
        var cancelled = false
        try {
            recoverSchedules(ScheduleRecoveryReason.BOOT,
                { throw CancellationException("receiver deadline") },
                { fail("recovery must remain cancellable") },
                { fail("cancelled recovery must not dispatch boot") })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
