package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NfcResultBufferTest {
    @Test fun cancellationRejectsLateCallbacksAndReleasesTheSlot() = runBlocking {
        val buffer = NfcResultBuffer()
        val first = buffer.register("first", false, 30)
        assertTrue(buffer.cancel("first"))
        assertEquals(NfcResult.Cancelled, first.await())
        assertFalse(buffer.claim("first"))
        assertFalse(buffer.complete("first", NfcResult.ReadOk("[]", "01")))
        val second = buffer.register("second", false, 30)
        assertFalse(buffer.cancel("first"))
        assertTrue(buffer.isActive("second"))
        assertTrue(buffer.complete("second", NfcResult.ReadOk("[]", "02")))
        assertEquals(NfcResult.ReadOk("[]", "02"), second.await())
    }

    @Test fun simultaneousSessionsCannotStealAnotherRequestsResult() = runBlocking {
        val buffer = NfcResultBuffer()
        val request = buffer.register("one", false, 30)
        assertThrows(IllegalStateException::class.java) { buffer.register("two", false, 30) }
        assertNull(buffer.get("two"))
        assertFalse(buffer.complete("two", NfcResult.WriteOk("00")))
        assertTrue(buffer.isActive("one"))
        buffer.complete("one", NfcResult.Timeout)
        assertEquals(NfcResult.Timeout, request.await())
    }

    @Test fun onlyOneTagCallbackMayClaimTheLiveSession() = runBlocking {
        val buffer = NfcResultBuffer()
        buffer.register("one", true, 30, kotlinx.serialization.json.Json.parseToJsonElement("[{\"kind\":\"text\",\"value\":\"тест\"}]") as kotlinx.serialization.json.JsonArray)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..20).map { Callable { buffer.claim("one") } }).map { it.get() }
            assertEquals(1, results.count { it })
            buffer.cancel("one")
            assertFalse(buffer.isActive("one"))
        } finally { pool.shutdownNow() }
    }

    @Test fun invalidTimeoutCannotCreateAnUnboundedRequest() {
        val buffer = NfcResultBuffer()
        assertThrows(IllegalArgumentException::class.java) { buffer.register("one", false, 4) }
        assertThrows(IllegalArgumentException::class.java) { buffer.register("one", false, 121) }
        assertNull(buffer.get("one"))
    }

    @Test fun cancellationInterruptsAndDrainsPhysicalIoBeforePublishingItsResult() {
        val buffer = NfcResultBuffer()
        val session = buffer.register("one", false, 30)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(session.operationGate.attach { closed.countDown() })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val io = pool.submit<NfcResult> { session.operationGate.perform {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                NfcResult.ReadOk("[]", "01")
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancel = pool.submit<Boolean> { runBlocking { buffer.cancel("one") } }
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(cancel.isDone)
            release.countDown()
            io.get(5, TimeUnit.SECONDS)
            assertTrue(cancel.get(5, TimeUnit.SECONDS))
            assertEquals(NfcResult.Cancelled, runBlocking { session.await() })
            assertFalse(buffer.isActive("one"))
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
