package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CronRunPolicyTest {
    private val job = ScheduledJobEntity("j", "a", "c", "encrypted", mode = "direct", scheduleType = "cron", cronExpression = "* * * * *", timezone = "UTC", createdAtMs = 0)
    private val run = ScheduledJobRunEntity("r", "j", 1, "scheduled", 100)
    @Test fun immutableOccurrenceIsSameForCatchupAndRegular() {
        val id = CronRunPolicy.occurrenceId("j", 1, 100)
        UUID.fromString(id)
        assertEquals(id, CronRunPolicy.occurrenceId("j", 1, 100))
        assertNotEquals(id, CronRunPolicy.occurrenceId("j", 2, 100))
        assertNotEquals(id, CronRunPolicy.occurrenceId("j", 1, 100, 1))
    }
    @Test fun processInterruptedClaimNeverRunsAgain() {
        assertFalse(CronRunPolicy.mayClaim(job, run.copy(status = "running")))
        assertFalse(CronRunPolicy.mayClaim(job, run.copy(status = "indeterminate")))
    }
    @Test fun trimmedHistoryDoesNotResetDurableReplayWatermarkOrRunLimit() {
        assertFalse(CronRunPolicy.mayClaim(job.copy(lastClaimedScheduledAtMs = 100), run))
        assertFalse(CronRunPolicy.mayClaim(job.copy(maxRuns = 1000, totalRuns = 1000), run))
        assertTrue(CronRunPolicy.mayClaim(job.copy(maxRuns = 1000, totalRuns = 999), run))
    }
    @Test fun pauseDeleteAndRevisionInvalidateStaleWorker() {
        assertFalse(CronRunPolicy.mayClaim(job.copy(enabled = false), run))
        assertFalse(CronRunPolicy.mayClaim(job.copy(deleted = true), run))
        assertFalse(CronRunPolicy.mayClaim(job.copy(revision = 2), run))
    }
    @Test fun manualReplayGuardSurvivesHistoryPruning() {
        val manual = run.copy(kind = "manual", manualSequence = 1)
        assertTrue(CronRunPolicy.mayClaim(job, manual))
        assertFalse(CronRunPolicy.mayClaim(job.copy(lastClaimedManualSequence = 1), manual))
    }
    @Test fun onlyFixedOutcomeCodesEnterHistory() {
        assertEquals("approval_required", CronRunPolicy.safeOutcome("approval_required"))
        assertEquals("execution_failed", CronRunPolicy.safeOutcome("password=xx"))
        assertEquals("execution_failed", CronRunPolicy.safeOutcome("ab"))
        assertNull(CronRunPolicy.safeOutcome(null))
    }
    @Test fun manualOverlapRetriesRegularBeforeClaimAndPreservesItsIdentity() {
        assertEquals(CronRunPolicy.ClaimDecision.RETRY_BEFORE_CLAIM, CronRunPolicy.claimDecision(job, run, listOf(run), true, 100))
        assertEquals("queued", run.status)
        assertEquals(CronRunPolicy.ClaimDecision.CLAIM, CronRunPolicy.claimDecision(job, run, listOf(run), false, 100))
    }
    @Test fun parallelCatchupClaimsOldestFirstBeforeAdvancingDurableWatermark() {
        val older = run.copy(occurrenceId = "older", scheduledAtMs = 80)
        val newer = run.copy(occurrenceId = "newer", scheduledAtMs = 100)
        assertEquals(CronRunPolicy.ClaimDecision.RETRY_BEFORE_CLAIM, CronRunPolicy.claimDecision(job, newer, listOf(newer, older), false, 100))
        assertEquals(CronRunPolicy.ClaimDecision.CLAIM, CronRunPolicy.claimDecision(job, older, listOf(newer, older), false, 100))
        assertEquals(CronRunPolicy.ClaimDecision.CLAIM, CronRunPolicy.claimDecision(job.copy(lastClaimedScheduledAtMs = 80), newer, listOf(newer), false, 100))
    }
    @Test fun manualSequenceOrderSurvivesWallClockMovingBackwards() {
        val first = run.copy(occurrenceId = "m1", kind = "manual", manualSequence = 1, scheduledAtMs = 100)
        val second = run.copy(occurrenceId = "m2", kind = "manual", manualSequence = 2, scheduledAtMs = 80)
        assertEquals(CronRunPolicy.ClaimDecision.RETRY_BEFORE_CLAIM, CronRunPolicy.claimDecision(job, second, listOf(first, second), false, 100))
        assertEquals(CronRunPolicy.ClaimDecision.CLAIM, CronRunPolicy.claimDecision(job, first, listOf(first, second), false, 100))
    }
    @Test fun freshLiveGateRevokesPausedEditedDeletedOrUnclaimedOccurrence() {
        val running = run.copy(status = "running", claimToken = "token")
        assertTrue(CronRunPolicy.isLive(job, running, job, "token"))
        assertFalse(CronRunPolicy.isLive(job.copy(enabled = false), running, job, "token"))
        assertFalse(CronRunPolicy.isLive(job.copy(deleted = true), running, job, "token"))
        assertFalse(CronRunPolicy.isLive(job.copy(revision = 2), running, job, "token"))
        assertFalse(CronRunPolicy.isLive(job.copy(ownerAssistantId = "other"), running, job, "token"))
        assertFalse(CronRunPolicy.isLive(job, running.copy(status = "indeterminate"), job, "token"))
        assertFalse(CronRunPolicy.isLive(job, running, job, "foreign-token"))
    }
    @Test fun disablingFeatureInvalidatesCancelledFutureIdWithoutResettingAttemptsOrReplayGuards() {
        val before = job.copy(nextRunAtMs = 100, totalRuns = 7, totalSucceeded = 4, lastClaimedScheduledAtMs = 80, manualSequence = 5, lastClaimedManualSequence = 5)
        val after = CronRunPolicy.invalidateSchedule(before)
        assertTrue(after.enabled) // Only the feature changed; a user-paused job remains a separate setting.
        assertNull(after.nextRunAtMs)
        assertNotEquals(CronRunPolicy.occurrenceId(before.id, before.revision, 100), CronRunPolicy.occurrenceId(after.id, after.revision, 100))
        assertEquals(7L, after.totalRuns)
        assertEquals(4L, after.totalSucceeded)
        assertEquals(80L, after.lastClaimedScheduledAtMs)
        assertEquals(5L, after.manualSequence)
        assertEquals(5L, after.lastClaimedManualSequence)
        assertFalse(CronRunPolicy.mayClaim(after, run.copy(jobRevision = after.revision, scheduledAtMs = 80)))
        assertTrue(CronRunPolicy.mayClaim(after, run.copy(jobRevision = after.revision, scheduledAtMs = 100)))
    }
}
