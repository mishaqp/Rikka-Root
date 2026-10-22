package me.rerere.rikkahub.data.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

internal class BackupNetworkFixture(private val respond: (HttpExchange, Int) -> Pair<Int, ByteArray>) : AutoCloseable {
    data class Request(val method: String, val rawPath: String, val rawQuery: String?, val authorization: String?)
    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin: String get() = "http://127.0.0.1:${server.address.port}"
    val client = HttpClient(OkHttp)
    init {
        server.createContext("/") { exchange ->
            requests.add(Request(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization")))
            try {
                val (status, body) = respond(exchange, requests.size)
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        server.start()
    }
    override fun close() { client.close(); server.stop(0) }
}
