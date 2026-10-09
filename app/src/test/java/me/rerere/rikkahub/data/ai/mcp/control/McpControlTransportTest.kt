package me.rerere.rikkahub.data.ai.mcp.control

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class McpControlTransportTest {
    @Test fun `manual servers retain original DNS redirects and client instance`() {
        val base = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        assertSame(base, guardedMcpHttpClient(base, publicAddressOnly = false))
        assertTrue(base.followRedirects)
        assertTrue(base.followSslRedirects)
    }

    @Test fun `controlled transport disables redirects and guards actual DNS lookups`() {
        val base = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val client = guardedMcpHttpClient(base, publicAddressOnly = true)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(base.interceptors.size + 1, client.interceptors.size)
        assertTrue(runCatching { client.dns.lookup("remote.example") }.isFailure)
    }

    @Test fun `server supplied SSE endpoint cannot target local address and may keep session query`() {
        assertTrue(runCatching { McpUrlGuard.validateTarget("http://127.0.0.1/post?sessionId=1") }.isFailure)
        assertTrue(runCatching {
            McpUrlGuard.validateTarget("https://example.com/post?sessionId=1") { listOf(InetAddress.getByName("8.8.8.8")) }
        }.isSuccess)
        assertTrue(runCatching {
            McpUrlGuard.validateTarget("https://alias.example/post?sessionId=1") { listOf(InetAddress.getByName("192.168.1.1")) }
        }.isFailure)
    }

    @Test fun `unavailable DNS is reported to async onFailure without crashing Dispatcher`() {
        val lookups = AtomicInteger()
        val failure = asyncFailure("https://mcp-bootstrap.invalid/mcp", Dns {
            lookups.incrementAndGet()
            throw UnknownHostException("DNS is not ready")
        })
        assertEquals("Prevalidation must use the client's DNS", 1, lookups.get())
        assertEquals("host_not_found: Не удалось проверить DNS сервера MCP", failure.message)
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(failure.message, failure.cause?.message)
    }

    @Test fun `async local endpoint rejection is an IO failure and keeps SSRF protection`() {
        val failure = asyncFailure("http://127.0.0.1/mcp", Dns {
            throw AssertionError("A literal local target must be rejected before DNS")
        })
        assertEquals(
            "private_address_forbidden: Локальные адреса MCP запрещены; используйте публичный сервер",
            failure.message,
        )
    }

    @Test fun `runtime exception at actual DNS lookup cannot escape async Dispatcher`() {
        val unavailable = IllegalStateException("DNS service has not started")
        val lookups = AtomicInteger()
        val failure = asyncFailure("https://mcp-bootstrap.invalid/mcp", Dns {
            if (lookups.incrementAndGet() == 1) listOf(InetAddress.getByName("8.8.8.8"))
            else throw unavailable
        })
        assertTrue("Actual guarded DNS lookup must run after prevalidation", lookups.get() >= 2)
        assertEquals("host_not_found: Не удалось проверить DNS сервера MCP", failure.message)
        assertSame(unavailable, failure.cause)
    }

    @Test fun `DNS rebinding to local address is rejected asynchronously without connecting`() {
        val lookups = AtomicInteger()
        val failure = asyncFailure("https://mcp-rebinding.invalid/mcp", Dns {
            listOf(InetAddress.getByName(if (lookups.incrementAndGet() == 1) "8.8.8.8" else "127.0.0.1"))
        })
        assertTrue("Actual guarded DNS lookup must run after prevalidation", lookups.get() >= 2)
        assertEquals("MCP: локальный или служебный адрес запрещён", failure.message)
    }

    /** This executor owns its uncaught handler; the test never changes JVM-global handlers. */
    private fun asyncFailure(url: String, dns: Dns): IOException {
        val uncaught = AtomicReference<Throwable>()
        val workerThreads = CopyOnWriteArrayList<Thread>()
        val executor = Executors.newSingleThreadExecutor { command ->
            Thread(command, "MCP async regression Dispatcher").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    uncaught.compareAndSet(null, error)
                }
                workerThreads.add(this)
            }
        }
        val base = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).dns(dns).build()
        val client = guardedMcpHttpClient(base, publicAddressOnly = true)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        val receivedResponse = AtomicReference<Boolean>(false)
        val call = client.newCall(Request.Builder().url(url).build())
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
            assertTrue("Dispatcher must finish its worker", executor.awaitTermination(5, TimeUnit.SECONDS))
            // Await the actual Thread as well: the uncaught handler runs after executor termination.
            workerThreads.forEach { worker ->
                worker.join(5_000)
                assertFalse("Dispatcher worker must exit", worker.isAlive)
            }
            assertNull("OkHttp must not rethrow an unchecked failure on Dispatcher", uncaught.get())
            assertFalse("Rejected target must never produce a network response", receivedResponse.get())
            return requireNotNull(failure.get()) { "Expected IOException in onFailure" }
        } finally {
            call.cancel()
            executor.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
