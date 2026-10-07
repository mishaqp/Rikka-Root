package me.rerere.search

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DuckDuckGoSearchServiceTest {
    private val options = SearchServiceOptions.DuckDuckGoOptions()
    private val common = SearchCommonOptions(resultSize = 2)
    private val results = """
        <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2&amp;rut=ignore">First &amp; best</a><a class="result__snippet">Useful snippet</a></div>
        <div class="result"><a class="result__a" href="javascript:alert(1)">Unsafe</a></div>
        <div class="result"><a class="result__a" href="https://example.org/path?uddg=keep">Second</a></div>
        <div class="result"><a class="result__a" href="https://example.net/third">Third</a></div>
    """.trimIndent()

    private fun response(request: Request, body: String, status: Int = 200, type: String = "text/html") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message("fixture").body(body.toResponseBody(type.toMediaType())).build()

    private fun query(value: String = "cats & dogs") = buildJsonObject { put("query", value) }
    private fun url(value: String = "https://example.com/start") = buildJsonObject { put("url", value) }

    private fun <T> withClient(client: OkHttpClient, block: () -> T): T {
        val previous = SearchService.httpClient
        SearchService.httpClient = client
        return try { block() } finally { SearchService.httpClient = previous }
    }

    @Test
    fun `new provider serializes and dispatches without changing the existing default`() {
        val encoded = SearchService.json.encodeToString<SearchServiceOptions>(options)
        val decoded = SearchService.json.decodeFromString<SearchServiceOptions>(encoded)
        assertTrue(decoded is SearchServiceOptions.DuckDuckGoOptions)
        assertEquals(options.id, decoded.id)
        assertSame(DuckDuckGoSearchService, SearchService.getService(decoded))
        assertSame(BingSearchService, SearchService.getService(SearchServiceOptions.DEFAULT))
        assertEquals("DuckDuckGo", options.displayName)
        val scrapeSchema = SearchService.getService(decoded).scrapingParameters(decoded) as InputSchema.Obj
        assertEquals(listOf("url"), scrapeSchema.required)
    }

    @Test
    fun `search decodes redirect links preserves order and filters unsafe links before applying limit`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("html.duckduckgo.com", chain.request().url.host)
            assertEquals("cats & dogs", chain.request().url.queryParameter("q"))
            assertNull(chain.request().header("Authorization"))
            response(chain.request(), results)
        }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.search(query(), common, options).getOrThrow() } }
        assertEquals(listOf("First & best", "Second"), result.items.map { it.title })
        assertEquals(listOf("https://example.com/a?x=1&y=2", "https://example.org/path?uddg=keep"), result.items.map { it.url })
        assertEquals("Useful snippet", result.items.first().text)
        assertNull(result.items.first().publishedDate)
    }

    @Test
    fun `malformed and foreign redirect wrappers never become trusted result URLs`() {
        val html = """
            <div class="result"><a class="result__a" href="">Missing target</a></div>
            <div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=javascript%3Aalert(1)">Bad target</a></div>
            <div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=%ZZ">Bad escape</a></div>
            <div class="result"><a class="result__a" href="https://example.org/l/?uddg=https%3A%2F%2Fother.org">Foreign wrapper</a></div>
            <div class="result"><a class="result__a" href="https://example.com/direct">Direct</a></div>
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), html) }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.search(query(), common, options).getOrThrow() } }
        assertEquals(listOf("Foreign wrapper", "Direct"), result.items.map { it.title })
        assertTrue(result.items.first().url.startsWith("https://example.org/l/?uddg="))
    }

    @Test
    fun `genuine no-results response succeeds with no items`() {
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), "<div class='no-results'>No results.</div>") }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.search(query(), common, options).getOrThrow() } }
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `challenge unrecognized markup and HTTP failures are errors rather than empty search answers`() {
        for ((status, body) in listOf(
            202 to "<div class='anomaly-modal'>confirm this search was made by a human</div>",
            200 to "<div class='anomaly-modal'>Challenge</div>",
            200 to "<html>Markup changed</html>",
            429 to results,
            503 to results,
        )) {
            val client = OkHttpClient.Builder().addInterceptor { response(it.request(), body, status) }.build()
            val result = withClient(client) { runBlocking { DuckDuckGoSearchService.search(query(), common, options) } }
            assertTrue("status $status should surface a failure", result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.isNotBlank())
        }
    }

    @Test
    fun `invalid query and URL fail before any request is made`() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; response(it.request(), results) }.build()
        withClient(client) {
            runBlocking {
                assertTrue(DuckDuckGoSearchService.search(query(" "), common, options).isFailure)
                assertTrue(DuckDuckGoSearchService.search(buildJsonObject { }, common, options).isFailure)
                for (invalid in listOf("file:///etc/passwd", "javascript:alert(1)", "not a url", " ")) {
                    assertTrue(DuckDuckGoSearchService.scrape(url(invalid), common, options).isFailure)
                }
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `scrape extracts readable text and canonical metadata using the final response URL`() {
        val html = """
            <html lang="en"><head><title>Fallback title</title><meta property="og:title" content="Article title">
            <meta name="description" content="Article description"><meta property="article:published_time" content="2026-09-01T00:00:00Z"></head>
            <body><nav>Navigation junk</nav><article><h1>Main heading</h1><p>First paragraph.</p><p>Second paragraph.</p>
            <script>malicious script</script></article><footer>Footer junk</footer></body></html>
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val finalRequest = chain.request().newBuilder().url("https://example.com/final").build()
            response(finalRequest, html)
        }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options).getOrThrow() } }.urls.single()
        assertEquals("https://example.com/final", result.url)
        assertTrue(result.content.contains("First paragraph."))
        assertTrue(result.content.contains("Second paragraph."))
        assertFalse(result.content.contains("junk"))
        assertFalse(result.content.contains("malicious"))
        assertEquals("Article title", result.metadata?.title)
        assertEquals("Article description", result.metadata?.description)
        assertEquals("en", result.metadata?.language)
        assertEquals("2026-09-01T00:00:00Z", result.metadata?.publishedDate)
    }

    @Test
    fun `scrape handles plain text and rejects binary pages and HTTP errors`() {
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), "Plain content", type = "text/plain") }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options).getOrThrow() } }.urls.single()
        assertEquals("Plain content", result.content)
        assertNull(result.metadata)
        for ((status, type) in listOf(200 to "application/pdf", 404 to "text/html", 500 to "text/html")) {
            val failedClient = OkHttpClient.Builder().addInterceptor { response(it.request(), "not a page", status, type) }.build()
            assertTrue(withClient(failedClient) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options).isFailure } })
        }
    }

    @Test
    fun `oversized response fails and readable output stays bounded`() {
        val oversized = OkHttpClient.Builder().addInterceptor { response(it.request(), "x".repeat(2 * 1024 * 1024)) }.build()
        assertTrue(withClient(oversized) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options).isFailure } })
        val longPage = OkHttpClient.Builder().addInterceptor { response(it.request(), "<article><p>${"a".repeat(40_000)}</p></article>") }.build()
        val page = withClient(longPage) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options).getOrThrow() } }.urls.single()
        assertEquals(32 * 1024, page.content.length)
    }

    @Test
    fun `unknown length response stops reading at its byte limit and closes the body`() {
        val data = Buffer().writeUtf8("x".repeat(2 * 1024 * 1024))
        var bytesRead = 0L
        var closed = false
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = data.read(sink, byteCount).also {
                if (it > 0) bytesRead += it
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor {
            response(it.request(), "").newBuilder().body(object : ResponseBody() {
                override fun contentType() = "text/html".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.scrape(url(), common, options) } }
        assertTrue(result.isFailure)
        assertTrue(closed)
        assertTrue(bytesRead <= 1024L * 1024 + 8192)
        assertTrue(bytesRead < 2 * 1024L * 1024)
    }

    @Test
    fun `transport failure is returned with its actionable message`() {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("fixture connection failed") }.build()
        val result = withClient(client) { runBlocking { DuckDuckGoSearchService.search(query(), common, options) } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("fixture connection failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancelling search cancels the real OkHttp call and propagates cancellation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(it.request(), results)
        }.build()
        withClient(client) {
            runBlocking {
                val pending = async(Dispatchers.Default) { DuckDuckGoSearchService.search(query(), common, options) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    pending.cancel()
                    assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                    release.countDown()
                    try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
                } finally {
                    release.countDown()
                    pending.cancel()
                }
            }
        }
    }

    @Test
    fun `cancellation remains attached while reading the response body`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("cancelled body read")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor {
            response(it.request(), "").newBuilder().body(object : ResponseBody() {
                override fun contentType() = "text/html".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build()
        withClient(client) {
            runBlocking {
                val pending = async(Dispatchers.Default) { DuckDuckGoSearchService.scrape(url(), common, options) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    pending.cancel()
                    assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                    release.countDown()
                    try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
                    assertTrue(closed.await(5, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                    pending.cancel()
                }
            }
        }
    }
}
