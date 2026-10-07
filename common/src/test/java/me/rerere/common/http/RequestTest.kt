package me.rerere.common.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RequestTest {
    @Test
    fun `cancelling await cancels the underlying HTTP call`() = runBlocking {
        val call = PendingCall()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
        request.cancelAndJoin()
        assertTrue(call.isCanceled())
    }

    @Test
    fun `response delivered after coroutine cancellation closes its body`() = runBlocking {
        val call = PendingCall()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
        request.cancelAndJoin()
        val body = TrackingBody()
        call.callback!!.onResponse(call, Response.Builder().request(call.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
        assertTrue(body.closed)
    }

    @Test
    fun `successful await returns usable response to caller`() = runBlocking {
        val call = PendingCall()
        val body = TrackingBody()
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            call.await().use { assertEquals("hello", it.body.string()) }
        }
        call.callback!!.onResponse(call, Response.Builder().request(call.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
        request.join()
        assertTrue(body.closed)
        assertFalse(call.isCanceled())
    }

    private class PendingCall(
        private val delegate: Call = OkHttpClient().newCall(Request.Builder().url("https://example.com").build()),
    ) : Call by delegate {
        var callback: Callback? = null
        private var cancelled = false
        override fun request() = Request.Builder().url("https://example.com").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("Synchronous execution unused")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall()
    }

    private class TrackingBody : ResponseBody() {
        var closed = false
        private val source = object : ForwardingSource(Buffer().writeUtf8("hello")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 5L
        override fun source(): BufferedSource = source
    }
}
