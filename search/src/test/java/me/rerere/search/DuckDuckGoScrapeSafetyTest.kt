package me.rerere.search

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class DuckDuckGoScrapeSafetyTest {
    private val options = SearchServiceOptions.DuckDuckGoOptions()
    private val common = SearchCommonOptions()

    private fun <T> withClient(client: OkHttpClient, block: () -> T): T {
        val previous = SearchService.httpClient
        SearchService.httpClient = client
        return try { block() } finally { SearchService.httpClient = previous }
    }

    @Test fun `scrape never reaches the phone itself or the home network`() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            val body = "<html><body><main>router admin</main></body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            for (host in listOf("127.0.0.1", "localhost")) {
                val result = withClient(OkHttpClient()) { runBlocking {
                    DuckDuckGoSearchService.scrape(buildJsonObject { put("url", "http://$host:${server.address.port}/") }, common, options)
                } }
                assertTrue(host, result.isFailure)
            }
            assertEquals(0, hits.get())
        } finally { server.stop(0) }
    }

    @Test fun `page without charset header is decoded from its meta charset`() {
        val html = "<html><head><meta charset=\"windows-1251\"></head><body><main>Привет, мир</main></body></html>"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(html.toByteArray(charset("windows-1251")).toResponseBody("text/html".toMediaType())).build()
        }.build()
        val page = withClient(client) { runBlocking {
            DuckDuckGoSearchService.scrape(buildJsonObject { put("url", "https://example.com/") }, common, options).getOrThrow()
        } }.urls.single()
        assertTrue(page.content, page.content.contains("Привет, мир"))
    }
}
