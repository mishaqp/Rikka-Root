package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class ToolMediaPlaybackStateTest {
    @Test fun pauseDuringPreparationSurvivesThePreparedCallback() {
        val state = ToolPlaybackState()
        val first = state.begin("first", 0)
        state.pause()
        assertTrue(state.prepared(first, 4000))
        assertFalse(state.playWhenReady)
        assertEquals("paused", state.phase)
    }

    @Test fun replacingTheTrackRejectsOldPreparedAndSeekCallbacks() {
        val state = ToolPlaybackState()
        val first = state.begin("first", 0)
        val second = state.begin("second", 0)
        assertFalse(state.prepared(first, 4000))
        assertTrue(state.prepared(second, 8000))
        assertFalse(state.seekComplete(first, 3500))
        assertEquals("second", state.source)
        assertEquals(0L, state.positionMs)
    }

    @Test fun seekingWhilePausedClampsAndPreservesThePause() {
        val state = ToolPlaybackState()
        val session = state.begin("track", 0)
        state.prepared(session, 5000)
        state.pause()
        assertEquals(5000L, state.seekTarget(9000))
        assertTrue(state.seekComplete(session, 5000))
        assertFalse(state.playWhenReady)
        assertEquals("paused", state.phase)
        assertEquals(5000L, state.positionMs)
    }

    @Test fun stopRetiresCallbacksAndResumeKeepsTheSavedPosition() {
        val state = ToolPlaybackState()
        val session = state.begin("track", 1234)
        state.prepared(session, 10000)
        state.stop(2468)
        assertFalse(state.prepared(session, 10000))
        assertFalse(state.seekComplete(session, 9000))
        assertEquals("stopped", state.phase)
        val resumed = state.begin(state.source!!, state.positionMs)
        assertTrue(state.prepared(resumed, 10000))
        assertEquals(2468L, state.positionMs)
    }

    @Test fun negativeSeekDoesNotChangeTheSession() {
        val state = ToolPlaybackState()
        val session = state.begin("track", 0)
        state.prepared(session, 1000)
        try { state.seekTarget(-1); fail("negative seek was accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, state.positionMs)
    }
}
