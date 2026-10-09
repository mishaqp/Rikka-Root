package me.rerere.rikkahub.data.ai

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkSettingsRequestInterceptorTest {
    @Test fun `non ASCII User-Agent reports onFailure without crashing Dispatcher`() {
        val failure = asyncOkHttpFailure(interceptor(userAgent = { "Rikka-Root/Тест" }))
        assertTrue(failure.cause is IllegalArgumentException)
        assertTrue(failure.message.orEmpty().contains("User-Agent"))
    }

    @Test fun `newline in User-Agent reports onFailure without crashing Dispatcher`() {
        val failure = asyncOkHttpFailure(interceptor(userAgent = { "Rikka-Root\nInjected: header" }))
        assertTrue(failure.cause is IllegalArgumentException)
        assertTrue(failure.message.orEmpty().contains("User-Agent"))
    }

    @Test fun `IO failure while applying current proxy settings is preserved`() {
        val cause = IOException("Proxy settings are unavailable")
        val failure = asyncOkHttpFailure(interceptor(applyProxyChanges = { throw cause }))
        assertSame(cause, failure)
    }

    @Test fun `shared headers and current proxy update retain their behavior`() {
        val proxyUpdates = AtomicInteger()
        val request = transformedRequest(
            interceptor(userAgent = { "  Rikka-Test/1  " }, applyProxyChanges = { proxyUpdates.incrementAndGet() }),
        )
        assertEquals("ru-RU,ru;q=0.9", request.header("Accept-Language"))
        assertEquals("Rikka-Test/1", request.header("User-Agent"))
        assertEquals(1, proxyUpdates.get())
    }

    @Test fun `empty configured User-Agent keeps the default`() {
        val request = transformedRequest(interceptor(userAgent = { "  " }))
        assertEquals("RikkaHub-Android/test", request.header("User-Agent"))
    }

    @Test fun `request supplied User-Agent remains unchanged without reading configured value`() {
        val request = transformedRequest(
            interceptor(userAgent = { throw AssertionError("Existing User-Agent must be retained") }),
            Request.Builder().url("https://fixture.invalid/").header("User-Agent", "Custom/1").build(),
        )
        assertEquals("Custom/1", request.header("User-Agent"))
    }

    private fun interceptor(
        userAgent: () -> String = { "Rikka-Test/1" },
        applyProxyChanges: () -> Unit = {},
    ): Interceptor = networkSettingsRequestInterceptor(
        acceptLanguage = "ru-RU,ru;q=0.9",
        defaultUserAgent = "RikkaHub-Android/test",
        userAgent = userAgent,
        applyProxyChanges = applyProxyChanges,
    )

    private fun transformedRequest(
        interceptor: Interceptor,
        request: Request = Request.Builder().url("https://fixture.invalid/").build(),
    ): Request {
        val transformed = AtomicReference<Request>()
        val client = OkHttpClient.Builder().addInterceptor(interceptor).addInterceptor { chain ->
            transformed.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("OK".toResponseBody()).build()
        }.build()
        try {
            client.newCall(request).execute().use { response -> assertEquals(200, response.code) }
            return requireNotNull(transformed.get())
        } finally {
            client.connectionPool.evictAll()
        }
    }
}
