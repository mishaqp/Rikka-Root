package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.data.db.entity.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class CronWorkerSafetyTest {
    @Test fun permissionRefusalCannotBeReportedAsSuccess() {
        assertEquals(CronExecutionOutcome("blocked", "approval_required"), CronExecutionOutcome.fromStatus("BLOCKED", "approval_required"))
        assertEquals("failed", CronExecutionOutcome.fromStatus("FAILED", "execution_failed").status)
        assertEquals("succeeded", CronExecutionOutcome.fromStatus("SUCCEEDED", null).status)
    }
    @Test fun timeoutOrCancellationAfterClaimCannotPromiseNoSideEffects() {
        assertEquals("indeterminate", CronExecutionOutcome.fromStatus("TIMED_OUT", null).status)
        assertEquals("indeterminate", CronExecutionOutcome.fromStatus("CANCELLED", null).status)
        assertEquals("indeterminate", CronExecutionOutcome.fromStatus("unexpected", null).status)
    }
    @Test fun providerErrorsAndSensitiveMessagesCannotBecomeHistoryText() {
        assertEquals("execution_failed", CronExecutionOutcome.fromStatus("FAILED", "password=xx").code)
    }
    @Test fun startupRecoveryAndWorkersShareOneBarrierBeforeAnyClaim() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recoveryCalls = AtomicInteger()
        val claims = AtomicInteger()
        val barrier = CronRecoveryBarrier { recoveryCalls.incrementAndGet(); entered.complete(Unit); release.await() }
        val startup = async(Dispatchers.Default) { barrier.awaitReady() }
        entered.await()
        val workerEntered = CompletableDeferred<Unit>()
        val worker = async(Dispatchers.Default) { workerEntered.complete(Unit); barrier.awaitReady(); claims.incrementAndGet() }
        workerEntered.await()
        try {
            assertEquals(0, claims.get())
        } finally { release.complete(Unit) }
        startup.await(); worker.await()
        assertEquals(1, recoveryCalls.get())
        assertEquals(1, claims.get())
    }
    @Test fun laterStartupCannotReclassifyNewlyLiveWorkAsInterrupted() = runBlocking {
        var recoveries = 0
        var persistedStatus = "queued"
        val barrier = CronRecoveryBarrier {
            recoveries++
            if (persistedStatus == "running") persistedStatus = "indeterminate"
        }
        barrier.awaitReady() // A worker may arrive before the startup coroutine.
        persistedStatus = "running"
        barrier.awaitReady() // Delayed startup must not sweep this newly claimed occurrence.
        assertEquals("running", persistedStatus)
        assertEquals(1, recoveries)
    }
    @Test fun actionableRefusalCodesRemainFixedAndUseful() {
        listOf("interactive_tool", "mandatory_confirmation", "token_budget_exceeded", "root_interaction_required", "run_no_longer_allowed").forEach { code ->
            assertEquals(code, CronExecutionOutcome.fromStatus("BLOCKED", code).code)
        }
    }
    @Test fun persistedQueueSurvivesProcessDeathBetweenRoomCommitAndWorkManagerEnqueue() = runBlocking {
        val job = ScheduledJobEntity("j", "a", "c", "encrypted", mode = "direct", scheduleType = "cron", cronExpression = "* * * * *", timezone = "UTC", createdAtMs = 0)
        val orphanManual = ScheduledJobRunEntity("manual-original-id", "j", 1, "manual", 10, manualSequence = 1)
        val orphanScheduled = ScheduledJobRunEntity("scheduled-original-id", "j", 1, "scheduled", 1)
        val persisted = listOf(orphanManual, orphanScheduled)
        val workQueue = mutableSetOf<String>() // WorkManager lost both requests before enqueue committed.
        val discarded = mutableListOf<String>()
        CronOutboxPlanner.reconcile(job, persisted, { workQueue.add(it.occurrenceId); Unit }, { discarded.add(it.occurrenceId); Unit })
        assertEquals(setOf("manual-original-id", "scheduled-original-id"), workQueue)
        assertTrue(discarded.isEmpty())
        assertEquals(1L, orphanManual.manualSequence) // Recovery must not allocate another manual occurrence.
    }
    @Test fun outboxCancelsStaleQueuedRowsAndNeverRequeuesClaimedEffects() = runBlocking {
        val job = ScheduledJobEntity("j", "a", "c", "encrypted", revision = 2, mode = "direct", scheduleType = "once", timezone = "UTC", createdAtMs = 0)
        val stale = ScheduledJobRunEntity("stale", "j", 1, "manual", 10, manualSequence = 1)
        val running = stale.copy(occurrenceId = "running", jobRevision = 2, status = "running", claimToken = "token")
        val workQueue = mutableSetOf<String>()
        val discarded = mutableSetOf<String>()
        CronOutboxPlanner.reconcile(job, listOf(stale, running), { workQueue.add(it.occurrenceId); Unit }, { discarded.add(it.occurrenceId); Unit })
        assertTrue(workQueue.isEmpty())
        assertEquals(setOf("stale"), discarded)
    }
}
