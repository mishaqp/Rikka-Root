package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec

class CodexAccountRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `refresh rotates credentials and persists them before next acquisition`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val client = client(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")
        try {
            val repository = CodexAccountRepository(store, client, json)
            val account = repository.acquireAccount("gpt-6.1-sol")
            assertEquals("new-access", account.accessToken)
            assertEquals("new-refresh", account.refreshToken)
            assertEquals(CodexTokenStatus.AVAILABLE, account.tokenStatus)
            assertEquals(account, store.read().accounts.single())
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `transient refresh error preserves stored credentials for later retry`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val client = client(503, """{"error":"temporarily_unavailable"}""")
        try {
            val repository = CodexAccountRepository(store, client, json)
            assertNotNull(runCatching { repository.acquireAccount("any-new-model") }.exceptionOrNull())
            val account = repository.accounts.value.single()
            assertEquals("old-refresh", account.refreshToken)
            assertNotEquals(CodexTokenStatus.INVALID, account.tokenStatus)
            assertEquals("old-refresh", store.read().accounts.single().refreshToken)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `authentication rejection invalidates persisted account`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val client = client(400, """{"error":"invalid_grant"}""")
        try {
            val repository = CodexAccountRepository(store, client, json)
            assertNotNull(runCatching { repository.acquireAccount() }.exceptionOrNull())
            assertEquals(CodexTokenStatus.INVALID, repository.accounts.value.single().tokenStatus)
            assertEquals(CodexTokenStatus.INVALID, store.read().accounts.single().tokenStatus)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `refresh without rotation retains existing refresh token`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val client = client(200, """{"access_token":"new-access","expires_in":3600}""")
        try {
            assertEquals("old-refresh", CodexAccountRepository(store, client, json).acquireAccount().refreshToken)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `cancelled account refresh propagates cancellation without invalidating credentials`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"access_token":"cancelled-access"}""".toResponseBody()).build()
        }.build()
        try {
            val repository = CodexAccountRepository(store, client, json)
            val pending = launch(Dispatchers.IO) {
                failure.set(runCatching { repository.acquireAccount() }.exceptionOrNull())
            }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(failure.get() is CancellationException)
            assertNotEquals(CodexTokenStatus.INVALID, repository.accounts.value.single().tokenStatus)
            assertEquals("old-access", store.read().accounts.single().accessToken)
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `failed durable update does not publish modified account state`() = runBlocking {
        var encryptionKey = key
        val store = CodexCredentialStore(folder.root.resolve("codex_accounts.enc"), json) { encryptionKey }
        store.write(CodexAccountState(listOf(expiredAccount())))
        val client = client(200, "{}")
        try {
            val repository = CodexAccountRepository(store, client, json)
            encryptionKey = SecretKeySpec(ByteArray(3), "AES")
            assertNotNull(runCatching { repository.setEnabled("user:account", false) }.exceptionOrNull())
            assertTrue(repository.accounts.value.single().enabled)
            encryptionKey = key
            assertTrue(store.read().accounts.single().enabled)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `cancellation during successful refresh body commits rotated token before propagating`() = runBlocking {
        val store = store()
        store.write(CodexAccountState(listOf(expiredAccount())))
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val payload = """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600}"""
        val body = object : ResponseBody() {
            private val buffered = object : ForwardingSource(Buffer().writeUtf8(payload)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return super.read(sink, byteCount)
                }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = payload.length.toLong()
            override fun source(): BufferedSource = buffered
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        try {
            val repository = CodexAccountRepository(store, client, json)
            val pending = launch(Dispatchers.IO) {
                failure.set(runCatching { repository.acquireAccount() }.exceptionOrNull())
            }
            assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
            pending.cancel()
            release.countDown()
            pending.join()
            assertTrue(failure.get() is CancellationException)
            assertEquals("rotated-refresh", store.read().accounts.single().refreshToken)
            assertEquals("rotated-access", repository.accounts.value.single().accessToken)
        } finally { release.countDown(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `malformed OAuth payload errors do not expose token response`() = runBlocking {
        val client = client(200, "{}")
        try {
            val repository = CodexAccountRepository(store(), client, json)
            val failure = runCatching { repository.saveLogin("{\"access_token\":\"private-secret") }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("Invalid OpenAI token response", failure!!.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("private-secret"))
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    private fun store() = CodexCredentialStore(folder.root.resolve("codex_accounts.enc"), json) { key }
    private fun expiredAccount() = CodexAccount(id = "user:account", name = "User", chatgptAccountId = "account",
        accessToken = "old-access", refreshToken = "old-refresh", expiresAt = 0, tokenStatus = CodexTokenStatus.EXPIRED)
    private fun client(code: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
            .message("test response").body(body.toResponseBody()).build()
    }.build()
}
