package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RequestLoggingInterceptorTest {
    @Before fun enableRequestLogging() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After fun disableRequestLogging() {
        Logging.setRequestLoggingEnabled(false)
        Logging.clear()
    }

    @Test fun `unchecked request body failure goes to onFailure without crashing Dispatcher`() {
        val cause = IllegalStateException("Request body is no longer available")
        val failure = asyncOkHttpFailure(RequestLoggingInterceptor(), failingRequestBody(cause))
        assertEquals(cause.message, failure.message)
        assertSame(cause, failure.cause)
    }

    @Test fun `request body IO failure is preserved in async onFailure`() {
        val cause = IOException("Request body stream is closed")
        val failure = asyncOkHttpFailure(RequestLoggingInterceptor(), failingRequestBody(cause))
        assertSame(cause, failure)
    }

    @Test fun `unchecked downstream failure cannot escape logging interceptor on Dispatcher`() {
        val cause = IllegalArgumentException("Request transport is unavailable")
        val failure = asyncOkHttpFailure(RequestLoggingInterceptor(), downstream = Interceptor { throw cause })
        assertEquals(cause.message, failure.message)
        assertSame(cause, failure.cause)
    }

    @Test fun `unchecked downstream failure is contained when request logging is disabled`() {
        Logging.setRequestLoggingEnabled(false)
        val cause = IllegalStateException("Request transport is unavailable")
        val failure = asyncOkHttpFailure(RequestLoggingInterceptor(), downstream = Interceptor { throw cause })
        assertEquals(cause.message, failure.message)
        assertSame(cause, failure.cause)
    }

    private fun failingRequestBody(error: Exception): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun writeTo(sink: BufferedSink) { throw error }
    }
}

/** A local executor catches failures only from these test workers, without changing JVM handlers. */
internal fun asyncOkHttpFailure(
    interceptor: Interceptor,
    body: RequestBody? = null,
    downstream: Interceptor = Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body("OK".toResponseBody()).build()
    },
): IOException {
    val uncaught = AtomicReference<Throwable>()
    val workers = CopyOnWriteArrayList<Thread>()
    val executor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "HTTP boundary regression Dispatcher").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                uncaught.compareAndSet(null, error)
            }
            workers.add(this)
        }
    }
    val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor))
        .addInterceptor(interceptor).addInterceptor(downstream).build()
    val request = Request.Builder().url("https://fixture.invalid/").apply {
        if (body != null) post(body)
    }.build()
    val call = client.newCall(request)
    val completed = CountDownLatch(1)
    val failure = AtomicReference<IOException>()
    val receivedResponse = AtomicReference(false)
    try {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                failure.set(error)
                completed.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                receivedResponse.set(true)
                completed.countDown()
            }
        })
        assertTrue("Async call must complete through its callback", completed.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Dispatcher executor must finish", executor.awaitTermination(5, TimeUnit.SECONDS))
        workers.forEach { worker ->
            worker.join(5_000)
            assertFalse("Dispatcher worker must exit", worker.isAlive)
        }
        assertNull("Unchecked failure must not escape onto Dispatcher", uncaught.get())
        assertFalse("Invalid request must never return a response", receivedResponse.get())
        return requireNotNull(failure.get()) { "Expected IOException in onFailure" }
    } finally {
        call.cancel()
        executor.shutdownNow()
        client.connectionPool.evictAll()
    }
}
