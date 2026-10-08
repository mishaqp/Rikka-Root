package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class NotificationIdAllocatorTest {
    @Test fun aFreshAllocatorKeepsThePersistedSequenceAfterRestart() {
        var stored = 0x60000000
        fun fresh() = NotificationIdAllocator(readNext = { stored }, storeNext = { stored = it; true })
        assertEquals(0x60000000, fresh().allocate())
        assertEquals(0x60000001, fresh().allocate())
        assertEquals(0x60000002, stored)
    }

    @Test fun failedPersistenceAndExhaustionNeverReuseAnId() {
        assertNull(NotificationIdAllocator({ 0x60000000 }, { false }).allocate())
        assertNull(NotificationIdAllocator({ Int.MAX_VALUE }, { error("Must not wrap") }).allocate())
    }

    @Test fun concurrentToolInstancesReserveDifferentIds() {
        var stored = 0x60000000
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..40).map { Callable { NotificationIdAllocator({ stored }, { stored = it; true }).allocate() } }
            val ids = executor.invokeAll(jobs).map { it.get()!! }
            assertEquals(40, ids.distinct().size)
            assertEquals((0x60000000 until 0x60000000 + 40).toList(), ids.sorted())
        } finally { executor.shutdownNow() }
    }
}
