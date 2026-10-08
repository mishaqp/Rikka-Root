package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonalToolSessionTest {
    @Test fun cameraHostCanReattachWithoutDiscardingItsPendingResult() = runBlocking {
        val buffer = PersonalToolSessions()
        val output = File.createTempFile("camera-session", ".jpg")
        try {
            val session = buffer.register(PersonalUiRequest.Camera(output))
            assertTrue(buffer.claim(session.id))
            // Android recreates the host while its external camera is still open.
            assertTrue(buffer.claim(session.id))
            assertTrue(buffer.isActive(session.id))
            output.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(buffer.complete(session.id, PersonalUiResult.Photo(output)))
            assertEquals(PersonalUiResult.Photo(output), session.await())
            assertTrue(output.isFile)
        } finally { output.delete() }
    }

    @Test fun aSecretCompletedAfterItsWaiterIsCancelledIsStillWiped() = runBlocking {
        val session=PersonalUiSession("test", PersonalUiRequest.Record(1000))
        session.destroyUndeliveredResult()
        val bytes=byteArrayOf(10,20)
        session.finish(PersonalUiResult.Secret(bytes))
        assertArrayEquals(byteArrayOf(0,0),bytes)
    }

    @Test fun aSecondOrForeignRequestCannotClaimTheCurrentUi() = runBlocking {
        val buffer=PersonalToolSessions()
        val first=buffer.register(PersonalUiRequest.Record(1000))
        assertFalse(buffer.claim("foreign")); assertTrue(buffer.claim(first.id)); assertFalse(buffer.claim(first.id))
        assertThrows(IllegalStateException::class.java) { buffer.register(PersonalUiRequest.Record(1000)) }
        assertFalse(buffer.complete("foreign", PersonalUiResult.Acknowledged))
        buffer.cancel(first.id)
        assertEquals(PersonalUiResult.Cancelled,first.await())
    }

    @Test fun cancellationWaitsForResourceCleanupBeforeFreeingTheSlot() = runBlocking {
        val buffer=PersonalToolSessions()
        val first=buffer.register(PersonalUiRequest.Record(1000))
        val started=CompletableDeferred<Unit>(); val released=CompletableDeferred<Unit>()
        assertTrue(buffer.setCancellationAction(first.id) { started.complete(Unit); released.await() })
        val cancellation=async { buffer.cancel(first.id) }
        started.await()
        assertTrue(buffer.isBusy()); assertFalse(buffer.isActive(first.id))
        assertThrows(IllegalStateException::class.java) { buffer.register(PersonalUiRequest.Record(1000)) }
        released.complete(Unit); cancellation.await()
        assertFalse(buffer.isBusy()); assertEquals(PersonalUiResult.Cancelled,first.await())
    }

    @Test fun backgroundAndTimeoutNeverLeaveAPendingUiRequest() = runBlocking {
        val buffer=PersonalToolSessions()
        var launches=0
        assertTrue(buffer.run(PersonalUiRequest.Record(1000),100,{ false },{ launches++ }) is PersonalUiResult.Error)
        assertEquals(0,launches); assertFalse(buffer.isBusy())
        assertTrue(buffer.run(PersonalUiRequest.Record(1000),20,{ true },{ launches++ }) is PersonalUiResult.Error)
        assertEquals(1,launches); assertFalse(buffer.isBusy())
    }

    @Test fun revealBytesAreWipedOnCancellationAndLateSecretResultsAreDestroyed() = runBlocking {
        val buffer=PersonalToolSessions()
        val bytes=byteArrayOf(1,2,3)
        val first=buffer.register(PersonalUiRequest.Reveal(bytes))
        buffer.cancel(first.id)
        assertArrayEquals(byteArrayOf(0,0,0),bytes)
        val late=byteArrayOf(4,5)
        assertFalse(buffer.complete(first.id,PersonalUiResult.Secret(late)))
        assertArrayEquals(byteArrayOf(0,0),late)
    }

    @Test fun aCancelledGenerationStillRunsNonCancellableCleanup() = runBlocking {
        val buffer=PersonalToolSessions()
        val ready=CompletableDeferred<Unit>(); var closed=false
        val job=launch {
            buffer.run(PersonalUiRequest.Record(1000),10000,{ true },{ id ->
                buffer.setCancellationAction(id) { delay(10); closed=true }
                ready.complete(Unit)
            })
        }
        ready.await(); job.cancelAndJoin()
        assertTrue(closed); assertFalse(buffer.isBusy())
    }
}
