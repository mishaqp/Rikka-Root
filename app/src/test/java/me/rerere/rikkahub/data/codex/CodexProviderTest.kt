package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import me.rerere.rikkahub.AppScope
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import javax.crypto.spec.SecretKeySpec

class CodexProviderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `live model discovery uses latest protocol and quota independent authenticated account`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val store = CodexCredentialStore(folder.root.resolve("codex_accounts.enc"), json) {
            SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        }
        store.write(CodexAccountState(listOf(CodexAccount(
            id = "user:account", name = "User", chatgptAccountId = "account", accessToken = "real-access",
            refreshToken = "refresh", expiresAt = Long.MAX_VALUE, tokenStatus = CodexTokenStatus.AVAILABLE,
            usage = CodexUsageSnapshot(primary = CodexUsageWindow(100.0, resetsAt = System.currentTimeMillis() / 1000 + 3600)),
        ))))
        val request = AtomicReference<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"slug":"gpt-6.1-sol","visibility":"list","supported_in_api":false},
                    {"slug":"future-model","visibility":"list"}]}""".toResponseBody()).build()
        }.build()
        val scope = AppScope()
        try {
            val repository = CodexAccountRepository(store, client, json)
            val models = CodexProvider(client, repository, json, scope).listModels(ProviderSetting.Codex())
            assertEquals(listOf("gpt-6.1-sol", "future-model"), models.map { it.modelId })
            assertEquals("0.160.1", request.get().url.queryParameter("client_version"))
            assertEquals("0.160.1", request.get().header("version"))
            assertTrue(request.get().header("User-Agent")!!.startsWith("codex_cli_rs/0.160.1"))
            assertEquals("Bearer real-access", request.get().header("Authorization"))
            assertEquals("account", request.get().header("ChatGPT-Account-Id"))
            assertTrue(repository.accounts.value.single().usage != null)
        } finally {
            scope.cancel()
            client.dispatcher.executorService.shutdownNow()
        }
    }
    @Test
    fun `nonstream caller sends SSE request and receives complete text tools and usage`() = runBlocking {
        val request = AtomicReference<Request>()
        val sse = listOf(
            """{"type":"response.output_text.delta","item_id":"msg","delta":"A title"}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc","call_id":"call-1","name":"search","arguments":""}}""",
            """{"type":"response.function_call_arguments.done","item_id":"fc","arguments":"{}"}""",
            """{"type":"response.completed","response":{"id":"resp-1","model":"gpt-6.1-sol","status":"completed","usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val client = sseClient(sse, request)
        val scope = AppScope()
        try {
            val provider = provider(client, scope)
            val result = provider.generateText(ProviderSetting.Codex(), listOf(UIMessage.user("Suggest a title")),
                TextGenerationParams(model = Model(modelId = "gpt-6.1-sol", abilities = listOf(ModelAbility.TOOL))))
            val body = Buffer().also { request.get().body!!.writeTo(it) }.readUtf8()
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals("true", payload["stream"]!!.jsonPrimitive.content)
            assertEquals("false", payload["store"]!!.jsonPrimitive.content)
            assertEquals("A title", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
            assertEquals("call-1", result.message.parts.filterIsInstance<UIMessagePart.Tool>().single().toolCallId)
            assertEquals("{}", result.message.parts.filterIsInstance<UIMessagePart.Tool>().single().input)
            assertEquals("resp-1", result.id)
            assertEquals(13, result.usage!!.totalTokens)
            assertNotNull(result.message.finishedAt)
        } finally { scope.cancel(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun `failed incomplete and truncated SSE cannot become successful nonstream results`() = runBlocking {
        val events = listOf(
            """{"type":"response.failed","response":{"error":{"message":"provider failed"}}}""",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
            """{"type":"response.output_text.delta","item_id":"msg","delta":"partial"}""",
        )
        for (event in events) {
            val client = sseClient("data: $event\n\n", AtomicReference())
            val scope = AppScope()
            try {
                assertNotNull(runCatching {
                    provider(client, scope).generateText(ProviderSetting.Codex(), listOf(UIMessage.user("title")),
                        TextGenerationParams(model = Model(modelId = "gpt-6.1-sol")))
                }.exceptionOrNull())
            } finally { scope.cancel(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test
    fun `cancelling SSE generation propagates cancellation without replay`() = runBlocking {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val source = object : ForwardingSource(Buffer().writeUtf8(
            "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg\",\"delta\":\"partial\"}\n\n")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count == -1L) {
                    blocked.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return count
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream").body(body).build()
        }.build()
        val scope = AppScope()
        try {
            val provider = provider(client, scope)
            val pending = launch(Dispatchers.IO) {
                failure.set(runCatching {
                    provider.generateText(ProviderSetting.Codex(), listOf(UIMessage.user("title")),
                        TextGenerationParams(model = Model(modelId = "gpt-6.1-sol")))
                }.exceptionOrNull())
            }
            assertTrue(withContext(Dispatchers.IO) { blocked.await(5, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(failure.get() is CancellationException)
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            scope.cancel()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun sseClient(sse: String, captured: AtomicReference<Request>) = OkHttpClient.Builder().addInterceptor { chain ->
        captured.set(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/event-stream")
            .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()

    private fun provider(client: OkHttpClient, scope: AppScope): CodexProvider {
        val json = Json { ignoreUnknownKeys = true }
        val store = CodexCredentialStore(folder.newFile(), json) { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        store.write(CodexAccountState(listOf(CodexAccount(id = "user:account", name = "User", chatgptAccountId = "account",
            accessToken = "access", refreshToken = "refresh", expiresAt = Long.MAX_VALUE, tokenStatus = CodexTokenStatus.AVAILABLE))))
        return CodexProvider(client, CodexAccountRepository(store, client, json), json, scope)
    }

}
