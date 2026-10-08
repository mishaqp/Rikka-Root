package me.rerere.rikkahub.subagent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable
class SubAgentRegistryTest {
    private val owner = SubAgentOwner("assistant", "chat")
    @Test fun concurrentReservationIsAtomicAndAssistantBound() {
        val registry = SubAgentRegistry()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val accepted = executor.invokeAll((1..40).map { i -> Callable {
                registry.reserve(SubAgentRun("$i", owner.copy(conversationId = "chat$i"), "run", "model"), 3)
            } }).count { it.get() }
            assertEquals(3, accepted)
        } finally { executor.shutdownNow() }
    }
    @Test fun ownerCannotObserveOrCancelOtherConversation() {
        val registry = SubAgentRegistry()
        val job = Job()
        assertTrue(registry.reserve(SubAgentRun("id", owner, "run", "model"), 3))
        assertTrue(registry.attach(owner, "id", job))
        val foreign = owner.copy(conversationId = "other")
        assertNull(registry.get(foreign, "id"))
        assertTrue(registry.list(foreign).isEmpty())
        assertFalse(registry.cancel(foreign, "id"))
        assertTrue(job.isActive)
        assertTrue(registry.cancel(owner, "id"))
        assertTrue(job.isCancelled)
    }
    @Test fun cancellationBeforeAttachmentCancelsGenerator() {
        val registry = SubAgentRegistry()
        registry.reserve(SubAgentRun("id", owner, "run", "model"), 1)
        assertTrue(registry.cancel(owner, "id"))
        val job = Job()
        assertFalse(registry.attach(owner, "id", job))
        assertTrue(job.isCancelled)
    }
    @Test fun cancelAllAndJoinWaitsForOwnedCleanupAndPreservesForeignJobs() = runBlocking {
        val registry = SubAgentRegistry()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var returned = false
        registry.reserve(SubAgentRun("owned", owner, "run", "model"), 3)
        val ownedJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    cleaned = true
                }
                registry.finish(owner, "owned") { it.copy(status = SubAgentStatus.CANCELLED) }
            }
        }
        registry.attach(owner, "owned", ownedJob)
        val foreign = owner.copy(conversationId = "foreign")
        val foreignJob = Job()
        registry.reserve(SubAgentRun("foreign", foreign, "run", "model"), 3)
        registry.attach(foreign, "foreign", foreignJob)
        val stopping = launch {
            assertEquals(1, registry.cancelAllAndJoin(owner))
            returned = true
        }
        withTimeout(1_000) { cleanupStarted.await() }
        assertFalse(returned)
        assertTrue(foreignJob.isActive)
        releaseCleanup.complete(Unit)
        stopping.join()
        assertTrue(returned)
        assertTrue(cleaned)
        assertTrue(ownedJob.isCompleted)
        assertTrue(foreignJob.isActive)
        foreignJob.cancel()
    }

}
