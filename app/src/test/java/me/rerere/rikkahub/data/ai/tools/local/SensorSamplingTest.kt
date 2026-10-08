package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SensorSamplingTest {
    @Test fun samplesAreAveragedWithoutAcceptingNonFiniteValues() = runBlocking {
        var closed = 0
        val values = collectSensorValues(1, register = { receive ->
            receive(floatArrayOf(1f, 2f))
            receive(floatArrayOf(Float.NaN, 8f))
            receive(floatArrayOf(3f, 4f))
            true
        }, unregister = { closed++ })
        assertEquals(listOf(2.0, 3.0), values)
        assertEquals(1, closed)
    }

    @Test fun noSamplesAndFailedRegistrationDoNotPretendToBeAReading() = runBlocking {
        var closed = 0
        assertNull(collectSensorValues(1, register = { false }, unregister = { closed++ }))
        assertNull(collectSensorValues(1, register = { true }, unregister = { closed++ }))
        assertEquals(2, closed)
    }

    @Test fun cancellingSamplingAlwaysUnregistersListener() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        var closed = 0
        val job = launch {
            collectSensorValues(5000, register = { registered.complete(Unit); true }, unregister = { closed++ })
        }
        registered.await()
        job.cancelAndJoin()
        assertEquals(1, closed)
        assertTrue(job.isCancelled)
    }
}
