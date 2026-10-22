package me.rerere.ai.provider.providers.google

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Model discovery must preserve its error path without consuming an arbitrary error body. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GoogleModelErrorBodyPart3Test {
    @Test fun failedModelListClosesBodyWithoutReadingIt() = runBlocking {
        val fixture = Fixture(503)
        assertTrue(fixture.provider.listModels(fixture.settings).isEmpty())
        assertEquals("Неуспешный список моделей не должен читать тело ошибки", 0, fixture.reads.get())
        assertTrue(fixture.closed.get())
    }

    @Test fun successfulModelListStillReadsAndClosesItsJsonBody() = runBlocking {
        val fixture = Fixture(200)
        assertTrue(fixture.provider.listModels(fixture.settings).isEmpty())
        assertTrue(fixture.reads.get() > 0)
        assertTrue(fixture.closed.get())
    }

    private class Fixture(status: Int) {
        val reads = AtomicInteger()
        val closed = AtomicBoolean()
        private val content = "{\"models\":[]}"
        private val source: BufferedSource = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet()
                if (status != 200) throw IOException("Тестовое тело ошибки не должно читаться")
                return super.read(sink, byteCount)
            }
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        private val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = if (status == 200) content.length.toLong() else Long.MAX_VALUE
            override fun source() = source
        }
        val settings = ProviderSetting.Google(baseUrl = "https://models.invalid", apiKey = "fixture")
        val provider = GoogleProvider(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body).build()
        }.build())
    }
}
