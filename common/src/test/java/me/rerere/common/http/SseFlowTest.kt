package me.rerere.common.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class SseFlowTest {
    @Test
    fun `slow speech consumer receives every event in order and the close event`() = runBlocking {
        val received = mutableListOf<SseEvent>()

        withTimeout(10_000) {
            client(160).sseFlow(request()).buffer(0).collect { event ->
                received += event
                delay(1)
            }
        }

        assertEquals((0 until 160).map(Int::toString), received.filterIsInstance<SseEvent.Event>().map { it.data })
        assertEquals(SseEvent.Open, received.first())
        assertEquals(SseEvent.Closed, received.last())
    }

    @Test
    fun `cancellation releases a producer waiting for a slow consumer`() = runBlocking {
        val received = withTimeout(5_000) {
            client(160).sseFlow(request()).buffer(0).take(2).toList()
        }

        assertEquals(listOf(SseEvent.Open, SseEvent.Event(null, null, "0")), received)
    }

    private fun request() = Request.Builder().url("https://example.test/speech").build()

    private fun client(events: Int) = OkHttpClient.Builder().addInterceptor { chain ->
        val body = (0 until events).joinToString("") { "data: $it\n\n" }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
}
