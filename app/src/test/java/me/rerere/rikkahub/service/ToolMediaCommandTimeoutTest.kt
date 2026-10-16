package me.rerere.rikkahub.service

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ToolMediaCommandTimeoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val intents = mutableListOf<Intent>()
    private var cancelNewRequest = false
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun startForegroundService(service: Intent): ComponentName {
            intents += service
            if (cancelNewRequest) {
                val requests = ToolMediaPlaybackService::class.java.getDeclaredField("requests").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val pending = requests.get(null) as Map<String, CompletableDeferred<ToolMediaStatus>>
                pending.getValue(service.getStringExtra("request_id")!!).cancel()
            }
            return ComponentName(this, ToolMediaPlaybackService::class.java)
        }
        override fun startService(service: Intent): ComponentName {
            intents += service
            return ComponentName(this, ToolMediaPlaybackService::class.java)
        }
    }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun cancelledMediaRequestReturnsAnErrorAndCancelsQueuedPlaybackWithoutCancellingCaller() = runTest(dispatcher) {
        cancelNewRequest = true
        val outcome = runCatching { ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PLAY,
            source = "test", playbackUri = "https://example.com/media") }
        assertNull("A cancelled request must not cancel its active caller", outcome.exceptionOrNull())
        val result = outcome.getOrThrow()
        assertEquals("Выполнение медиакоманды отменено. Проверьте результат перед повторным запуском.", result.error)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(listOf(ToolMediaPlaybackService.ACTION_PLAY, "me.rerere.rikkahub.toolmedia.CANCEL"), intents.map { it.action })
        assertEquals(intents.first().getStringExtra("request_id"), intents.last().getStringExtra("request_id"))
    }

    @Test fun commandOwnedTimeoutReturnsAnErrorAndCancelsTheQueuedPlayback() = runTest(dispatcher) {
        val result = ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PLAY,
            source = "test", playbackUri = "https://example.com/media")
        val error = result.error.orEmpty()
        assertTrue(error, error.contains("вовремя") || error.contains("время ожидания"))
        assertTrue(error, error.contains("Повторите") || error.contains("повторите"))
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(listOf(ToolMediaPlaybackService.ACTION_PLAY, "me.rerere.rikkahub.toolmedia.CANCEL"), intents.map { it.action })
        assertEquals(intents.first().getStringExtra("request_id"), intents.last().getStringExtra("request_id"))
    }

    @Test fun parentTimeoutPropagatesAndCancelsTheQueuedPlayback() = runTest(dispatcher) {
        try {
            withTimeout(5) {
                ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PLAY, source = "test")
            }
            fail("The caller timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(listOf(ToolMediaPlaybackService.ACTION_PLAY, "me.rerere.rikkahub.toolmedia.CANCEL"), intents.map { it.action })
        assertEquals(intents.first().getStringExtra("request_id"), intents.last().getStringExtra("request_id"))
    }
}
