package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OpenAIOutputsPart3Test {
    private val refusal = "Я не могу помочь с этим запросом."

    @Test fun responsesNonstreamRefusalIsVisibleUnchanged() {
        val result = runCatching { ResponseAPI(OkHttpClient()).parseResponseOutput(json.parseToJsonElement("""{
            "id":"r","status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"$refusal"}]}]
        }""").jsonObject) }
        assertTrue("A valid refusal is a response, not a parse error: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(refusal, result.getOrThrow().message.toText())
    }
    @Test fun responsesStreamRefusalIsVisibleUnchanged() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = listOf(
            """{"type":"response.refusal.delta","item_id":"m","content_index":0,"delta":"$refusal"}""",
            """{"type":"response.refusal.done","item_id":"m","content_index":0,"refusal":"$refusal"}""",
            """{"type":"response.completed","response":{"id":"r","status":"completed"}}""",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks }
        assertEquals(refusal, materialize(chunks).toText())
    }
    @Test fun chatNonstreamRefusalIsVisibleUnchanged() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"r","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"refusal":"$refusal"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            assertEquals(refusal, OpenAIProvider(client).generateText(ProviderSetting.OpenAI(), listOf(UIMessage.user("Проверка")), TextGenerationParams(Model(modelId = "test"))).message.toText())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    @Test fun chatStreamRefusalIsVisibleUnchanged() {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = decoder.accept(SseEvent(data = """{"choices":[{"delta":{"refusal":"$refusal"},"finish_reason":"stop"}]}""")).chunks + decoder.onClosed()
        assertEquals(refusal, materialize(chunks).toText())
    }
    @Test fun responsesNonstreamBuiltinImagePreservesDataAndTextOrder() {
        val result = ResponseAPI(OkHttpClient()).parseResponseOutput(json.parseToJsonElement("""{
            "id":"r","status":"completed","output":[
              {"type":"message","content":[{"type":"output_text","text":"До"}]},
              {"type":"image_generation_call","id":"im","status":"completed","result":"aGVsbG8=","output_format":"png"},
              {"type":"message","content":[{"type":"output_text","text":"После"}]}
            ]
        }""").jsonObject)
        assertEquals(3, result.message.parts.size)
        assertEquals("До", (result.message.parts[0] as UIMessagePart.Text).text)
        assertEquals("data:image/png;base64,aGVsbG8=", (result.message.parts[1] as UIMessagePart.Image).url)
        assertEquals("После", (result.message.parts[2] as UIMessagePart.Text).text)
    }
    @Test fun failedBuiltinImageDoesNotInventImageData() {
        val result = ResponseAPI(OkHttpClient()).parseResponseOutput(json.parseToJsonElement("""{
          "id":"r","status":"completed","output":[{"type":"image_generation_call","id":"im","status":"failed","result":null}]
        }""").jsonObject)
        assertTrue(result.message.parts.none { it is UIMessagePart.Image })
    }
    private fun materialize(chunks: List<StreamChunk>): UIMessage {
        val handler = StreamChunkHandler(Model(modelId = "test"))
        return chunks.fold(listOf(UIMessage.user("Проверка"))) { messages, chunk -> handler.handle(messages, chunk) }.last()
    }
}
