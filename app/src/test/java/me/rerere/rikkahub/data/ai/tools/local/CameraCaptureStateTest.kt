package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameraCaptureStateTest {
    private fun withState(test: (CameraCaptureState) -> Unit) {
        val output = File.createTempFile("camera-state", ".jpg")
        val state = CameraCaptureState(output)
        try { test(state) } finally { state.cancel() }
    }

    @Test fun reattachmentRetiresOldCaptureAndItsCallbacks() = withState { state ->
        var released = 0
        val oldHost = state.attachHost({ released++ }, {})
        val oldAttempt = state.beginCamera(oldHost)!!
        val newHost = state.attachHost({}, {})
        assertEquals(1, released)
        assertFalse(state.completePhoto(oldHost, oldAttempt.id))
        val newAttempt = state.beginCamera(newHost)!!
        assertNotEquals(oldAttempt.file, newAttempt.file)
        assertTrue(state.beginCapture(newHost, newAttempt.id))
        assertTrue(state.completePhoto(newHost, newAttempt.id))
        assertFalse(state.completePhoto(newHost, newAttempt.id))
    }

    @Test fun pendingCaptureCanFinishAfterBackgroundAndRecreation() = withState { state ->
        val oldHost = state.attachHost({}, {})
        val attempt = state.beginCamera(oldHost)!!
        assertTrue(state.beginCapture(oldHost, attempt.id))
        attempt.file.writeBytes(byteArrayOf(1, 2, 3))
        state.suspendHost(oldHost)
        val replacement = state.attachHost({}, {})
        // A second capture must wait until the original save callback is resolved.
        assertNull(state.beginCamera(replacement))
        assertTrue(state.completePhoto(oldHost, attempt.id))
        assertTrue(attempt.file.isFile)
        assertEquals(CameraCapturePhase.FINISHED, state.phase)
    }

    @Test fun interruptedSaveWaitsForTerminalFailureBeforeStartingAnotherCapture() = withState { state ->
        val host = state.attachHost({}, {})
        val attempt = state.beginCamera(host)!!
        state.selectCamera(host, attempt.id, "back:0")
        assertTrue(state.beginCapture(host, attempt.id))
        var timerCancelled = 0
        state.setCaptureDeadline(host, attempt.id) { timerCancelled++ }
        state.suspendHost(host)
        assertEquals(0, timerCancelled)
        assertNull(state.beginCamera(host))
        val timeout = CameraCaptureFailure("capturing", "timeout", 15000, "back:0")
        assertTrue(state.offerFallback(host, attempt.id, timeout))
        assertEquals(CameraCapturePhase.SUSPENDED, state.phase)
        assertEquals(1, timerCancelled)
        assertEquals(timeout, state.failure)
        assertNotNull(state.beginCamera(host))
        assertFalse(state.completePhoto(host, attempt.id))
    }

    @Test fun duplicateSaveCallbacksNeverDeleteTheAcceptedPhoto() = withState { state ->
        val host = state.attachHost({}, {})
        val attempt = state.beginCamera(host)!!
        assertTrue(state.beginCapture(host, attempt.id))
        attempt.file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(state.completePhoto(host, attempt.id))
        assertFalse(state.completePhoto(host, attempt.id))
        state.discardAttempt(attempt)
        assertArrayEquals(byteArrayOf(1, 2, 3), attempt.file.readBytes())
    }

    @Test fun oldHostCannotDetachOrCloseTheReplacementHost() = withState { state ->
        val oldHost = state.attachHost({}, {})
        var replacementReleased = 0
        var replacementClosed = 0
        val replacement = state.attachHost({ replacementReleased++ }, { replacementClosed++ })
        assertFalse(state.ownsHost(oldHost))
        assertTrue(state.ownsHost(replacement))
        state.detachHost(oldHost)
        assertEquals(0, replacementReleased)
        state.cancel()
        assertEquals(1, replacementReleased)
        assertEquals(1, replacementClosed)
    }

    @Test fun externalCameraHandoffSurvivesBackgroundAndReattachment() = withState { state ->
        val host = state.attachHost({}, {})
        val attempt = state.beginCamera(host)!!
        assertTrue(state.offerFallback(host, attempt.id, CameraCaptureFailure("initializing", "timeout", 10000)))
        val external = state.beginExternal(host)!!
        state.suspendHost(host)
        val nextHost = state.attachHost({}, {})
        assertEquals(CameraCapturePhase.EXTERNAL, state.phase)
        assertNull(state.beginCamera(nextHost))
        assertNull(state.beginExternal(nextHost))
        assertEquals(external, state.externalAttempt)
        assertTrue(state.completeExternal(nextHost))
    }

    @Test fun backgroundSuspendsCaptureWithoutCancellingTheSession() = withState { state ->
        var released = 0
        val host = state.attachHost({ released++ }, {})
        val attempt = state.beginCamera(host)!!
        state.suspendHost(host)
        assertEquals(CameraCapturePhase.SUSPENDED, state.phase)
        assertEquals(1, released)
        assertFalse(state.beginCapture(host, attempt.id))
        assertNotNull(state.beginCamera(host))
    }

    @Test fun fallbackIsSingleUseAndRejectsLateCameraCallbacks() = withState { state ->
        val host = state.attachHost({}, {})
        val attempt = state.beginCamera(host)!!
        val failure = CameraCaptureFailure("capturing", "capture_failed", 123)
        assertTrue(state.offerFallback(host, attempt.id, failure))
        assertFalse(state.offerFallback(host, attempt.id, failure))
        assertFalse(state.completePhoto(host, attempt.id))
        assertNotNull(state.beginExternal(host))
        assertNull(state.beginExternal(host))
        assertEquals(failure, state.failure)
    }

    @Test fun cancellationDrainsTheCurrentHostAndDeletesEveryAttempt() = withState { state ->
        var released = 0
        var closed = 0
        val host = state.attachHost({ released++ }, { closed++ })
        val attempt = state.beginCamera(host)!!
        attempt.file.writeBytes(byteArrayOf(1, 2, 3))
        state.cancel()
        assertEquals(1, released)
        assertEquals(1, closed)
        assertFalse(attempt.file.exists())
        assertFalse(state.completePhoto(host, attempt.id))
        assertNull(state.beginCamera(host))
        state.cancel()
        assertEquals(1, closed)
    }
}
