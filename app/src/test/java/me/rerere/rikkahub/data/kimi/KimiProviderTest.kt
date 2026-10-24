package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.*
import org.junit.After
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class KimiProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun closeRefreshScope() { refreshScope.cancel() }
    private val json = Json { ignoreUnknownKeys = true }
    private fun store() = KimiCredentialStore(folder.root.resolve("kimi_session.enc"), json) {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    }
    private fun provider(client: OkHttpClient, info: KimiModelInfo = KimiModelInfo("kimi-for-coding", reasoning = true, thinkingType = "both")): KimiProvider {
        store().write(KimiAccount(KimiRegion.CN, KimiTokens("synthetic-access", "synthetic-refresh", Long.MAX_VALUE),
            models = listOf(info), quotas = listOf(KimiQuota("5h", 1.0))))
        val api = KimiApi(client, json, { mapOf("User-Agent" to "Rikka-Root/test", "X-Msh-Platform" to "rikka_root_android") })
        return KimiProvider(client, KimiAccountRepository(store(), api, refreshScope), api, json)
    }
    private fun client(respond: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody((if (body.startsWith("data:") || body.startsWith("event:")) "text/event-stream" else "application/json").toMediaType())).build()
    }.build()
    private fun event(value: JsonObject) = "data: $value\n\n"

    private fun temperatureReply(protocol: String, modelId: String): String = when (protocol) {
        "anthropic" -> listOf(
            """{"type":"message_start","message":{"id":"m","model":"$modelId","usage":{"input_tokens":1,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { frame ->
            val type = json.parseToJsonElement(frame).jsonObject["type"]!!.jsonPrimitive.content
            "event: $type\ndata: $frame\n\n"
        }
        "response" -> "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg\",\"delta\":\"OK\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"model\":\"$modelId\",\"status\":\"completed\"}}\n\n"
        else -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    }

    @Test fun `Pro configured temperatures use the supported server value across native protocols`() = runBlocking {
        for (id in listOf("k3.1-pro", "k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "KIMI-K3.1-PRO")) {
            for (protocol in listOf("chat_completions", "response", "anthropic")) {
                for (configured in listOf(0.6f, 1f, null)) {
                    val request = AtomicReference<Request>()
                    val client = client { outgoing ->
                        request.set(outgoing)
                        val body = json.parseToJsonElement(Buffer().also { outgoing.body!!.writeTo(it) }.readUtf8()).jsonObject
                        val temperature = body["temperature"]?.jsonPrimitive?.doubleOrNull
                        if (temperature != null && temperature != 1.0) {
                            400 to """{"error":{"message":"invalid temperature: only 1 is allowed for this model"}}"""
                        } else 200 to temperatureReply(protocol, id)
                    }
                    try {
                        val info = KimiModelInfo(id, protocol = protocol, reasoning = true, thinkingType = "only")
                        val result = provider(client, info).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("Привет")),
                            TextGenerationParams(info.toModel(), temperature = configured, reasoningLevel = ReasoningLevel.HIGH))
                        assertEquals("OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
                        val body = json.parseToJsonElement(Buffer().also { request.get().body!!.writeTo(it) }.readUtf8()).jsonObject
                        assertEquals(id, body["model"]!!.jsonPrimitive.content)
                        assertTrue("$id/$protocol sent ${body["temperature"]}",
                            body["temperature"] == null || body["temperature"]!!.jsonPrimitive.double == 1.0)
                    } finally { client.dispatcher.executorService.shutdownNow() }
                }
            }
        }
    }

    @Test fun `Pro custom body cannot reintroduce a rejected temperature`() = runBlocking {
        for (id in listOf("k3.1-pro", "k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "KIMI-K3.1-PRO")) {
            for (protocol in listOf("chat_completions", "response", "anthropic")) {
                val client = client { outgoing ->
                    val body = json.parseToJsonElement(Buffer().also { outgoing.body!!.writeTo(it) }.readUtf8()).jsonObject
                    val temperature = body["temperature"]?.jsonPrimitive?.doubleOrNull
                    if (temperature != null && temperature != 1.0) {
                        400 to """{"error":{"message":"invalid temperature: only 1 is allowed for this model"}}"""
                    } else 200 to temperatureReply(protocol, id)
                }
                try {
                    val info = KimiModelInfo(id, protocol = protocol, reasoning = true, thinkingType = "only")
                    val result = provider(client, info).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("Привет")),
                        TextGenerationParams(info.toModel(), temperature = 1f, reasoningLevel = ReasoningLevel.HIGH,
                            customBody = listOf(CustomBody("temperature", JsonPrimitive(0.2)))))
                    assertEquals("OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
                } finally { client.dispatcher.executorService.shutdownNow() }
            }
        }
    }

    @Test fun `non Pro models keep configured sampling and literal message contents`() {
        val original = buildJsonObject {
            put("temperature", 0.6); put("top_p", 0.8)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "temperature=0.6\n'a b' 2>/dev/null") } }
        }
        assertEquals(original, adaptKimiBody(original, KimiModelInfo("other-model"), ReasoningLevel.OFF))
    }

    @Test fun `catalog discovery uses OAuth despite exhausted generation quota`() = runBlocking {
        val request = AtomicReference<Request>()
        val client = client { request.set(it); 200 to """{"data":[{"id":"kimi-for-coding","supports_tool_use":true}]}""" }
        try {
            val models = provider(client).listModels(ProviderSetting.KimiCode())
            assertEquals("kimi-for-coding", models.single().modelId)
            assertEquals("Bearer synthetic-access", request.get().header("Authorization"))
            assertEquals("https://api.kimi.com/coding/v1/models", request.get().url.toString())
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `nonstream caller uses native SSE and preserves whitespace only tool argument fragments`() = runBlocking {
        val request = AtomicReference<Request>()
        val command = "printf '[%s]\\n' 'a b';\nchmod 0644 file 2>/dev/null"
        val encodedArguments = buildJsonObject { put("command", command) }.toString()
        val fragments = Regex(" +|[^ ]+").findAll(encodedArguments).map { it.value }.toList()
        val sse = buildString {
            fragments.forEachIndexed { index, fragment -> append(event(buildJsonObject {
                put("id", "completion"); putJsonArray("choices") { addJsonObject {
                    put("index", 0); putJsonObject("delta") { putJsonArray("tool_calls") { addJsonObject {
                        put("index", 0); if (index == 0) { put("id", "call-1"); put("type", "function") }
                        putJsonObject("function") { if (index == 0) put("name", "root_exec"); put("arguments", fragment) }
                    } } }
                } }
            })) }
            append("data: {\"id\":\"completion\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":5,\"total_tokens\":9}}\n\n")
            append("data: [DONE]\n\n")
        }
        val client = client { request.set(it); 200 to sse }
        try {
            val result = provider(client).generateText(ProviderSetting.KimiCode(customHeaders = listOf(CustomHeader("Authorization", "wrong"))),
                listOf(UIMessage.user("Run")), TextGenerationParams(Model(modelId = "kimi-for-coding", abilities = listOf(ModelAbility.TOOL)),
                    customHeaders = listOf(CustomHeader("User-Agent", "kimi-cli/spoof"))))
            val tool = result.message.parts.filterIsInstance<UIMessagePart.Tool>().single()
            assertEquals(fragments.joinToString(""), tool.input)
            assertEquals(command, json.parseToJsonElement(tool.input).jsonObject["command"]!!.jsonPrimitive.content)
            assertEquals("Bearer synthetic-access", request.get().header("Authorization"))
            assertEquals("Rikka-Root/test", request.get().header("User-Agent"))
            val body = json.parseToJsonElement(Buffer().also { request.get().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(9, result.usage!!.totalTokens)
            assertNotNull(result.message.finishedAt)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `401 refresh retries once before streaming and persists rotated credentials`() = runBlocking {
        val generations = AtomicInteger()
        val client = client { request -> when {
            request.url.encodedPath.endsWith("/token") -> 200 to """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
            generations.incrementAndGet() == 1 -> 401 to """{"error":"unauthorized"}"""
            else -> {
                assertEquals("Bearer new-access", request.header("Authorization"))
                200 to "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
        } }
        try {
            val result = provider(client).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("hi")), TextGenerationParams(Model(modelId = "kimi-for-coding")))
            assertEquals("OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
            assertEquals(2, generations.get())
            assertEquals("new-refresh", store().read()!!.tokens.refreshToken)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `permission errors and truncated streams cannot become successful responses`() = runBlocking {
        for ((status, body) in listOf(403 to """{"error":"denied"}""", 200 to "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n")) {
            val client = client { status to body }
            try {
                val error = runCatching { provider(client).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("hi")), TextGenerationParams(Model(modelId = "kimi-for-coding"))) }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(store().read()!!.needsLogin)
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun `anthropic mandatory thinking uses beta endpoint without Claude only fields`() = runBlocking {
        val request = AtomicReference<Request>()
        val sse = "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"kimi-k3\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
        val framed = sse.split("\n\n").filter { it.isNotBlank() }.joinToString("") { frame ->
            val type = json.parseToJsonElement(frame.removePrefix("data: ")).jsonObject["type"]!!.jsonPrimitive.content
            "event: $type\n$frame\n\n"
        }
        val client = client { request.set(it); 200 to framed }
        try {
            val model = KimiModelInfo("kimi-k3", protocol = "anthropic", reasoning = true, thinkingType = "only", efforts = listOf("high", "max"), defaultEffort = "max")
            val chunks = provider(client, model).streamText(ProviderSetting.KimiCode(), listOf(UIMessage.user("hi")), TextGenerationParams(model.toModel(), reasoningLevel = ReasoningLevel.OFF)).toList()
            assertTrue(chunks.any { it is StreamChunk.Finish })
            assertEquals("/coding/v1/messages", request.get().url.encodedPath)
            assertEquals("true", request.get().url.queryParameter("beta"))
            assertNull(request.get().header("x-api-key"))
            val body = json.parseToJsonElement(Buffer().also { request.get().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertNull(body["thinking"]!!.jsonObject["display"])
            assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun `Responses model uses account protocol native text usage and supported effort`() = runBlocking {
        val request = AtomicReference<Request>()
        val sse = """data: {"type":"response.output_text.delta","item_id":"msg","delta":"Response OK"}

 data: {"type":"response.completed","response":{"id":"resp-1","model":"kimi-response","status":"completed","usage":{"input_tokens":2,"output_tokens":1,"total_tokens":3}}}

""".replace("\n data:", "\ndata:")
        val client = client { request.set(it); 200 to sse }
        try {
            val info = KimiModelInfo("kimi-response", protocol = "response", reasoning = true, thinkingType = "both",
                efforts = listOf("low", "high"), defaultEffort = "high")
            val result = provider(client, info).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("hi")),
                TextGenerationParams(info.toModel(), reasoningLevel = ReasoningLevel.HIGH))
            assertEquals("Response OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
            assertEquals(3, result.usage!!.totalTokens)
            assertEquals("/coding/v1/responses", request.get().url.encodedPath)
            val body = json.parseToJsonElement(Buffer().also { request.get().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertNull(body["thinking"])
            assertNull(body["reasoning_effort"])
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `reasoning adaptation preserves messages quotes newlines and redirections literally`() {
        val command = "printf '[%s]\\n' 'a b'\nstatus 2>/dev/null"
        val arguments = buildJsonObject { put("command", command) }.toString()
        val original = buildJsonObject {
            putJsonArray("messages") { addJsonObject {
                put("role", "assistant")
                put("reasoning_content", "Existing reasoning")
                putJsonArray("tool_calls") { addJsonObject {
                    put("id", "call-1"); putJsonObject("function") { put("name", "root_exec"); put("arguments", arguments) }
                } }
            } }
            put("stream", true)
        }
        for (protocol in listOf("chat_completions", "anthropic", "response")) {
            val adapted = adaptKimiBody(original, KimiModelInfo("model", protocol = protocol, reasoning = true, thinkingType = "both"), ReasoningLevel.AUTO)
            assertEquals(original["messages"], adapted["messages"])
            assertEquals(original["stream"], adapted["stream"])
        }
    }

    @Test fun `remote images never receive account credentials on http alternate ports or API image paths`() = runBlocking {
        val urls = listOf("http://api.kimi.com/coding/v1/picture", "https://api.kimi.com:8443/coding/v1/picture",
            "https://api.kimi.com/coding/v1/messages", "https://api.kimi.com/coding/v1/picture")
        for (url in urls) {
            val imageRequest = AtomicReference<Request>()
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJXcAAAAASUVORK5CYII=")
            val sse = """event: message_start
 data: {"type":"message_start","message":{"id":"m","model":"image-model","usage":{"input_tokens":1,"output_tokens":0}}}

 event: message_delta
 data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":0}}

 event: message_stop
 data: {"type":"message_stop"}

""".replace("\n ", "\n")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val image = request.method == "GET"
                if (image) imageRequest.set(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(if (image) png.toResponseBody("image/png".toMediaType()) else sse.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            try {
                val info = KimiModelInfo("image-model", protocol = "anthropic", images = true)
                val message = UIMessage.user("image").copy(parts = listOf(UIMessagePart.Image(url)))
                provider(client, info).streamText(ProviderSetting.KimiCode(), listOf(message), TextGenerationParams(info.toModel())).toList()
                assertNotNull(imageRequest.get())
                assertNull("Image URL must not receive bearer credentials: $url", imageRequest.get().header("Authorization"))
                assertNull(imageRequest.get().header("X-Msh-Platform"))
                assertNull(imageRequest.get().header("x-api-key"))
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun `Responses incomplete terminal event remains an error and cannot finish generation successfully`() = runBlocking {
        val sse = """data: {"type":"response.output_text.delta","item_id":"m","delta":"partial"}

 data: {"type":"response.incomplete","response":{"id":"r","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}

""".replace("\n data:", "\ndata:")
        val client = client { 200 to sse }
        try {
            val info = KimiModelInfo("kimi-response", protocol = "response")
            val result = runCatching { provider(client, info).generateText(ProviderSetting.KimiCode(), listOf(UIMessage.user("hi")), TextGenerationParams(info.toModel())) }
            assertTrue("Partial Responses output must be returned as an error", result.isFailure)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `credential origin predicate requires TLS canonical port POST and exact endpoint`() {
        for (region in KimiRegion.entries) {
            val base = region.apiBase
            fun request(url: String, post: Boolean = true) = Request.Builder().url(url).apply {
                if (post) post("{}".toRequestBody())
            }.build()
            assertTrue(isKimiGenerationRequest(request("$base/messages?beta=true"), region, "anthropic"))
            assertTrue(isKimiGenerationRequest(request("$base/responses"), region, "response"))
            assertFalse(isKimiGenerationRequest(request("$base/messages", false), region, "anthropic"))
            assertFalse(isKimiGenerationRequest(request(base.replace("https://", "http://") + "/messages"), region, "anthropic"))
            assertFalse(isKimiGenerationRequest(request(base.replace("/coding", ":8443/coding") + "/messages"), region, "anthropic"))
            assertFalse(isKimiGenerationRequest(request("$base/picture"), region, "anthropic"))
            assertFalse(isKimiGenerationRequest(request("$base/messages/extra"), region, "anthropic"))
        }
    }

}
