package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NfcOperationGateTest {
    @Test fun completedCancellationRejectsAQueuedPhysicalWrite() {
        val gate = NfcOperationGate()
        val writes = AtomicInteger()
        gate.requestStop()
        gate.stopAndClose()
        val result = gate.perform { writes.incrementAndGet(); NfcResult.WriteOk("01") }
        assertEquals(NfcResult.Cancelled, result)
        assertEquals(0, writes.get())
    }

    @Test fun cancellationClosesLiveIoAndWaitsForItBeforeReturning() {
        val gate = NfcOperationGate()
        val entered = CountDownLatch(1)
        val closeRequested = CountDownLatch(1)
        val allowIoToReturn = CountDownLatch(1)
        assertTrue(gate.attach { closeRequested.countDown() })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val operation = pool.submit<NfcResult> { gate.perform {
                entered.countDown()
                check(allowIoToReturn.await(5, TimeUnit.SECONDS))
                NfcResult.WriteOk("01")
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancel = pool.submit { gate.requestStop(); gate.stopAndClose() }
            assertTrue(closeRequested.await(5, TimeUnit.SECONDS))
            assertFalse(cancel.isDone)
            allowIoToReturn.countDown()
            operation.get(5, TimeUnit.SECONDS)
            cancel.get(5, TimeUnit.SECONDS)
            val calls = AtomicInteger()
            assertEquals(NfcResult.Cancelled, gate.perform { calls.incrementAndGet(); NfcResult.WriteOk("02") })
            assertEquals(0, calls.get())
        } finally { allowIoToReturn.countDown(); pool.shutdownNow() }
    }

    @Test fun technologyRegisteredAfterCancellationIsImmediatelyClosed() {
        val gate = NfcOperationGate()
        val closed = AtomicInteger()
        gate.requestStop()
        assertFalse(gate.attach { closed.incrementAndGet() })
        assertEquals(1, closed.get())
    }
}
