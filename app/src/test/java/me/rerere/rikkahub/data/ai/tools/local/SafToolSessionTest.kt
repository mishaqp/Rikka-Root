package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SafToolSessionTest {
    @Test fun recreationReattachesWithoutLaunchingASecondPicker() = runBlocking {
        val sessions = SafToolSessions()
        val request = sessions.register(SafUiRequest(null, null))
        assertTrue(sessions.attach(request.id) {})
        assertTrue(sessions.beginPicker(request.id))
        assertTrue(sessions.attach(request.id) {})
        assertFalse(sessions.beginPicker(request.id))
        assertTrue(sessions.complete(request.id, SafUiResult.Cancelled))
        assertEquals(SafUiResult.Cancelled, request.await())
    }

    @Test fun cancellationDrainsTheHostAndRejectsLateGrantSideEffects() = runBlocking {
        val sessions = SafToolSessions()
        val request = sessions.register(SafUiRequest(null, null))
        val closing = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        assertTrue(sessions.attach(request.id) { closing.complete(Unit); drained.await() })
        val job = async { sessions.cancel(request.id) }
        closing.await()
        assertTrue(sessions.isBusy())
        assertFalse(sessions.isActive(request.id))
        var persisted = false
        assertFalse(sessions.grant(request.id, SafUiResult.Granted("content://provider/tree/root")) { persisted = true })
        assertFalse(persisted)
        assertThrows(IllegalStateException::class.java) { sessions.register(SafUiRequest(null, null)) }
        drained.complete(Unit)
        job.await()
        assertFalse(sessions.isBusy())
        assertEquals(SafUiResult.Cancelled, request.await())
    }

    @Test fun generationCancellationAlwaysReleasesTheSlot() = runBlocking {
        val sessions = SafToolSessions()
        val ready = CompletableDeferred<Unit>()
        var hostClosed = false
        val job = launch {
            sessions.run(SafUiRequest(null, null), 10_000, { true }) { id ->
                sessions.attach(id) { delay(10); hostClosed = true }
                ready.complete(Unit)
            }
        }
        ready.await()
        job.cancelAndJoin()
        assertTrue(hostClosed)
        assertFalse(sessions.isBusy())
    }

    @Test fun timeoutAndBackgroundCannotLeaveAPendingPicker() = runBlocking {
        val sessions = SafToolSessions()
        var launches = 0
        assertTrue(sessions.run(SafUiRequest(null, null), 10, { false }) { launches++ } is SafUiResult.Error)
        assertEquals(0, launches)
        assertTrue(sessions.run(SafUiRequest(null, null), 10, { true }) { launches++ } is SafUiResult.Error)
        assertEquals(1, launches)
        assertFalse(sessions.isBusy())
    }

    @Test fun onlyTheActiveRequestMayPersistAGrant() = runBlocking {
        val sessions = SafToolSessions()
        val request = sessions.register(SafUiRequest(null, null))
        var writes = 0
        assertFalse(sessions.grant("foreign", SafUiResult.Granted("content://provider/tree/root")) { writes++ })
        assertTrue(sessions.grant(request.id, SafUiResult.Granted("content://provider/tree/root")) { writes++ })
        assertEquals(1, writes)
        assertFalse(sessions.grant(request.id, SafUiResult.Granted("content://provider/tree/other")) { writes++ })
        assertEquals(1, writes)
        assertFalse(sessions.isBusy())
    }
}
