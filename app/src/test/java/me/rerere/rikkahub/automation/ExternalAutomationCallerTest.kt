package me.rerere.rikkahub.automation

import me.rerere.rikkahub.service.HeadlessTaskStatus
import org.junit.Assert.*
import org.junit.Test

class ExternalAutomationCallerTest {
    private val tasker = "net.dinglisch.android.taskerm"

    @Test fun activityCallerUsesOnlyIdentityAvailableFromAndroid() {
        assertEquals(tasker, ExternalAutomationDispatcher.verifiedActivityCaller(tasker, "other.package", 33))
        assertNull(ExternalAutomationDispatcher.verifiedActivityCaller(null, tasker, 33))
        assertEquals(tasker, ExternalAutomationDispatcher.verifiedActivityCaller(null, tasker, 34))
        assertNull(ExternalAutomationDispatcher.verifiedActivityCaller(null, null, 34))
        assertNull(ExternalAutomationDispatcher.verifiedActivityCaller(" ", " ", 34))
    }

    @Test fun broadcastRequiresPackageAndUidSharedByAndroid14OrLater() {
        assertNull(ExternalAutomationDispatcher.verifiedBroadcastCaller(tasker, 10_000, 33))
        assertNull(ExternalAutomationDispatcher.verifiedBroadcastCaller(tasker, -1, 34))
        assertNull(ExternalAutomationDispatcher.verifiedBroadcastCaller(null, 10_000, 34))
        assertNull(ExternalAutomationDispatcher.verifiedBroadcastCaller(" ", 10_000, 34))
        assertEquals(tasker, ExternalAutomationDispatcher.verifiedBroadcastCaller(tasker, 10_000, 34))
    }

    @Test fun callerTrustDefaultsToDenyAndMasterOffAlwaysWins() {
        val trusted = setOf(tasker, "")
        assertEquals(ExternalAutomationDispatcher.TrustResult.Disabled,
            ExternalAutomationDispatcher.classifyCallerSettings(false, trusted, tasker))
        for (caller in listOf(null, "", " ", "malicious.package", "<adb>")) {
            assertEquals(ExternalAutomationDispatcher.TrustResult.PendingUserApproval,
                ExternalAutomationDispatcher.classifyCallerSettings(true, trusted, caller))
        }
        assertEquals(ExternalAutomationDispatcher.TrustResult.Trusted,
            ExternalAutomationDispatcher.classifyCallerSettings(true, trusted, tasker))
    }

    @Test fun removingCallerRevokesTrustImmediately() {
        assertEquals(ExternalAutomationDispatcher.TrustResult.Trusted,
            ExternalAutomationDispatcher.classifyCallerSettings(true, setOf(tasker), tasker))
        assertEquals(ExternalAutomationDispatcher.TrustResult.PendingUserApproval,
            ExternalAutomationDispatcher.classifyCallerSettings(true, emptySet(), tasker))
    }

    @Test fun callbackCannotBeRedirectedToAnotherPackageOrUnverifiedCaller() {
        assertTrue(ExternalAutomationDispatcher.isCallbackForCaller(tasker, tasker))
        assertTrue(ExternalAutomationDispatcher.isCallbackForCaller(tasker, null))
        assertFalse(ExternalAutomationDispatcher.isCallbackForCaller(tasker, "other.package"))
        assertFalse(ExternalAutomationDispatcher.isCallbackForCaller(null, tasker))
        assertFalse(ExternalAutomationDispatcher.isCallbackForCaller("", tasker))
    }

    @Test fun blockedTimedOutAndIndeterminateRunsNeverReportCompleted() {
        assertEquals("completed", ExternalAutomationDispatcher.callbackStatus(HeadlessTaskStatus.SUCCEEDED))
        assertEquals("blocked", ExternalAutomationDispatcher.callbackStatus(HeadlessTaskStatus.BLOCKED))
        assertEquals("cancelled", ExternalAutomationDispatcher.callbackStatus(HeadlessTaskStatus.CANCELLED))
        for (status in listOf(HeadlessTaskStatus.FAILED, HeadlessTaskStatus.TIMED_OUT, HeadlessTaskStatus.INDETERMINATE)) {
            assertEquals("failed", ExternalAutomationDispatcher.callbackStatus(status))
        }
    }
}
