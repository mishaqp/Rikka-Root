package me.rerere.search

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** Exercises guarded DNS and actual OkHttp redirect/network interceptors using loopback fixtures. */
class DuckDuckGoScrapeNetworkAuditTest {
    private val options = SearchServiceOptions.DuckDuckGoOptions()
    private val common = SearchCommonOptions()

    @Test fun privateDnsAnswersAreRejectedBeforeCreatingASocket() {
        for (address in listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.1", "169.254.1.1", "100.64.0.1")) {
            assertDnsRejected(listOf(numeric(address)))
        }
    }

    @Test fun aMixedPublicAndPrivateDnsAnswerIsRejectedAsAWhole() {
        for (privateAddress in listOf("127.0.0.1", "192.168.1.1", "fd00::1")) {
            val public = numeric("93.184.216.34")
            val private = numeric(privateAddress)
            assertDnsRejected(listOf(public, private))
            assertDnsRejected(listOf(private, public))
        }
    }

    @Test fun privateIpv6DnsAnswersAreRejectedBeforeCreatingASocket() {
        for (address in listOf("::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:192.168.1.1")) {
            assertDnsRejected(listOf(numeric(address)))
        }
    }

    @Test fun publicIpv4AndIpv6AnswersCanReachTheReadablePage() {
        for (address in listOf("93.184.216.34", "2606:4700:4700::1111")) {
            val hits = AtomicInteger()
            val server = pageServer(hits)
            val sockets = RoutedSocketFactory(server.address)
            val lookups = mutableListOf<String>()
            val client = client(Dns { host -> lookups += host; listOf(numeric(address)) }, sockets)
            server.start()
            try {
                val result = withClient(client) { runBlocking {
                    DuckDuckGoSearchService.scrape(url("http://$HOST:${server.address.port}/page"), common, options)
                } }
                assertTrue("A public DNS answer must remain usable: $address, $result", result.isSuccess)
                assertTrue(result.getOrThrow().urls.single().content.contains("public fixture page"))
                assertEquals(listOf(HOST), lookups)
                assertEquals(1, hits.get())
                assertEquals(1, sockets.connections.get())
            } finally {
                server.stop(0)
                client.shutdown()
            }
        }
    }

    @Test fun aPublicPageRedirectToALoopbackLiteralNeverSendsThePrivateHttpRequest() {
        val targetHits = AtomicInteger()
        val target = pageServer(targetHits)
        val sourceHits = AtomicInteger()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/") { exchange ->
            sourceHits.incrementAndGet()
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val sockets = RoutedSocketFactory(source.address)
        val client = client(Dns { listOf(numeric("93.184.216.34")) }, sockets)
        source.start()
        target.start()
        try {
            val result = withClient(client) { runBlocking {
                DuckDuckGoSearchService.scrape(url("http://$HOST:${source.address.port}/start"), common, options)
            } }
            assertEquals("The public first hop must actually execute", 1, sourceHits.get())
            assertTrue("A private redirect must fail", result.isFailure)
            assertTrue("The connected-address guard must reject the literal IP", result.exceptionOrNull() is UnknownHostException)
            assertEquals("No HTTP request may reach the redirect target", 0, targetHits.get())
        } finally {
            source.stop(0)
            target.stop(0)
            client.shutdown()
        }
    }

    @Test fun aRedirectHostnameWithAPrivateDnsAnswerNeverCreatesItsConnection() {
        val hits = AtomicInteger()
        val lookups = mutableListOf<String>()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Location", "http://private.fixture.invalid:${source.address.port}/private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val sockets = RoutedSocketFactory(source.address)
        val client = client(Dns { host ->
            lookups += host
            listOf(numeric(if (host == HOST) "93.184.216.34" else "192.168.1.1"))
        }, sockets)
        source.start()
        try {
            val result = withClient(client) { runBlocking {
                DuckDuckGoSearchService.scrape(url("http://$HOST:${source.address.port}/start"), common, options)
            } }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is UnknownHostException)
            assertEquals(listOf(HOST, "private.fixture.invalid"), lookups)
            assertEquals("Only the public first-hop connection may be created", 1, sockets.connections.get())
            assertEquals("The private redirect must never send a second request", 1, hits.get())
        } finally {
            source.stop(0)
            client.shutdown()
        }
    }

    private fun assertDnsRejected(addresses: List<InetAddress>) {
        val lookups = AtomicInteger()
        val sockets = RoutedSocketFactory(InetSocketAddress("127.0.0.1", 1))
        val client = client(Dns { host ->
            assertEquals(HOST, host)
            lookups.incrementAndGet()
            addresses
        }, sockets)
        try {
            val result = withClient(client) { runBlocking {
                DuckDuckGoSearchService.scrape(url("http://$HOST/page"), common, options)
            } }
            assertTrue("Private DNS answer must fail: $addresses", result.isFailure)
            assertTrue("Failure must come from the DNS guard: $result", result.exceptionOrNull() is UnknownHostException)
            assertEquals(1, lookups.get())
            assertEquals("Rejected DNS answers must never create a socket: $addresses", 0, sockets.connections.get())
        } finally { client.shutdown() }
    }

    private fun pageServer(hits: AtomicInteger) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            hits.incrementAndGet()
            val bytes = "public fixture page".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    private fun client(dns: Dns, sockets: SocketFactory) = OkHttpClient.Builder()
        .dns(dns).socketFactory(sockets).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS).build()

    private fun url(value: String) = buildJsonObject { put("url", value) }
    private fun numeric(value: String): InetAddress = InetAddress.getByName(value)

    private fun <T> withClient(client: OkHttpClient, block: () -> T): T {
        val previous = SearchService.httpClient
        SearchService.httpClient = client
        return try { block() } finally { SearchService.httpClient = previous }
    }

    private fun OkHttpClient.shutdown() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    /** Preserve OkHttp's public route metadata; route the actual TCP bytes only to local fixtures. */
    private class RoutedSocketFactory(private val fixture: InetSocketAddress) : SocketFactory() {
        val connections = AtomicInteger()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                connections.incrementAndGet()
                val address = endpoint as InetSocketAddress
                // A literal loopback redirect really connects to its own local target fixture.
                super.connect(if (address.address.isLoopbackAddress) address else fixture, timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        private fun unsupported(): Nothing = error("OkHttp must use an unconnected fixture socket")
    }

    companion object { private const val HOST = "public.fixture.invalid" }
}
