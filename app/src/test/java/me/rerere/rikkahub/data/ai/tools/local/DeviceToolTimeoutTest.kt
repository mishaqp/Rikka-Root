package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceToolTimeoutTest {
    @Test fun toolOwnedTimeoutReturnsAnActionableRussianErrorAndKeepsTheCallerActive() = runTest {
        val parts = deviceToolParts {
            withTimeout(10) { awaitCancellation() }
        }
        val error = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text)
            .jsonObject.getValue("error").jsonPrimitive.content
        assertTrue(error, error.contains("вовремя") || error.contains("время ожидания"))
        assertTrue(error, error.contains("Повторите") || error.contains("повторите"))
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun parentTimeoutStillCancelsTheToolInsteadOfProducingAResult() = runTest {
        var resultReturned = false
        try {
            withTimeout(10) {
                deviceToolParts { awaitCancellation() }
                resultReturned = true
            }
            fail("The parent timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertFalse(resultReturned)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun explicitCancellationStillPropagatesAndRunsCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        var resultReturned = false
        val job = launch {
            deviceToolResult {
                try { started.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }
            resultReturned = true
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
        assertFalse(resultReturned)
    }
}
