package me.rerere.rikkahub.costguards

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.mockk.mockk
import io.mockk.coEvery
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AutoContextCompression
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** Real auxiliary request paths and durable ledger; only the paid provider is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class AuxiliaryTokenBudgetTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var settingsScope: AppScope
    private lateinit var manager: ProviderManager
    private lateinit var handler: TranslationHandler
    private lateinit var loop: GenerationLoop
    private lateinit var client: OkHttpClient
    private val fake = RecordingProvider()
    private val regular = Model(modelId = "translation")
    private val qwen = Model(modelId = "qwen-mt-plus")
    private val ocr = Model(modelId = "ocr", customHeaders = listOf(CustomHeader("X-OCR", "fixture")),
        customBodies = listOf(CustomBody("max_output_tokens", JsonPrimitive(99999)),
            CustomBody("generationConfig", Json.parseToJsonElement("{\"maxOutputTokens\":99999}"))))
    private val main = Model(modelId = "main")
    private val owner = Assistant(systemPrompt = "", streamOutput = false, tokenBudgetHardCap = 5000,
        localTools = listOf(LocalToolOption.CostGuards))
    private val conversationId = Uuid.random()
    private lateinit var settings: Settings

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val directory = folder.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        settingsScope = AppScope().apply { cancel() }
        settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(regular, qwen, ocr, main))),
            translateModeId = regular.id, translatePrompt = "{source_text}", ocrModelId = ocr.id,
            ocrPrompt = "Read image", assistants = listOf(owner), assistantId = owner.id)
        settingsStore = SettingsStore(context, settingsScope).also { it.settingsFlow.value = settings }
        client = OkHttpClient()
        manager = ProviderManager(client, context).apply { registerProvider("openai", fake) }
        handler = TranslationHandler(manager)
        loop = GenerationLoop(context, manager, Json { ignoreUnknownKeys = true })
        startKoin { modules(module {
            single<Context> { context }
            single { settingsStore }
            single { manager }
        }) }
    }

    @After fun tearDown() {
        stopKoin()
        settingsScope.cancel()
        Dispatchers.resetMain()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun `stream translation rejects exhausted inherited ledger before provider`() = runBlocking {
        assertExhaustedTranslation(regular)
    }

    @Test fun `Qwen MT rejects exhausted inherited ledger before provider`() = runBlocking {
        assertExhaustedTranslation(qwen)
    }

    private suspend fun assertExhaustedTranslation(model: Model) {
        val ledger = TokenBudgetLedger(true, hardCap = 100, initialSpentTokens = 100)
        val error = capture { translate(ledger, model) }
        assertTrue("Expected budget rejection; provider calls=${fake.requests.size}, error=$error",
            error is TokenBudgetExceededException)
        assertEquals("Достигнут предел токенов; продолжение остановлено.", error!!.message)
        assertEquals(0, fake.requests.size)
        assertState(ledger, 100)
    }

    @Test fun `real service message translation uses its conversation owner store ledger`() = runBlocking {
        val serviceScope = AppScope()
        val conversationOwner = owner.copy(tokenBudgetHardCap = 100)
        val selectedOther = Assistant()
        settingsStore.settingsFlow.value = settings.copy(assistants = listOf(conversationOwner, selectedOther),
            assistantId = selectedOther.id)
        val store = TokenBudgetStore(folder.newFolder(), assistantSource = { id ->
            settingsStore.settingsFlow.value.assistants.find { it.id == id }
        })
        val exhausted = store.getLedger(conversationOwner, conversationId, emptyList())
        exhausted.reserve(0, 100).let { it.markSubmitted(); it.observe(TokenUsage(totalTokens = 100)); it.markCompleted(); it.close() }
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns
            Conversation(id = conversationId, assistantId = conversationOwner.id, messageNodes = emptyList())
        val service = ChatService(RuntimeEnvironment.getApplication(), serviceScope, mockk(relaxed = true),
            settingsStore, repository, mockk(relaxed = true), loop, handler,
            mockk(relaxed = true), manager, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), store, mockk(relaxed = true))
        try {
            val message = UIMessage.assistant("old message")
            service.addConversationReference(conversationId)
            service.initializeConversation(conversationId)
            settingsStore.settingsFlow.value = settingsStore.settingsFlow.value.copy(assistantId = selectedOther.id)
            service.updateConversationState(conversationId) {
                it.copy(assistantId = conversationOwner.id, messageNodes = listOf(message.toMessageNode()))
            }
            service.translateMessage(conversationId, message, Locale.ENGLISH)
            withTimeout(10000) {
                serviceScope.coroutineContext[Job]!!.children.toList().joinAll()
            }
            val error = service.errors.value.singleOrNull()?.error
            assertTrue("Expected service budget error; provider calls=${fake.requests.size}, errors=${service.errors.value}",
                error is TokenBudgetExceededException)
            assertEquals(0, fake.requests.size)
            assertSame(exhausted, store.getLedger(conversationOwner, conversationId, emptyList()))
            assertNull(service.getConversationFlow(conversationId).value.currentMessages.single().translation)
        } finally {
            service.cleanup()
            serviceScope.cancel()
        }
    }

    @Test fun `service preserves provider cancellation as cancelled translation job`() = runBlocking {
        val serviceScope = AppScope()
        val store = TokenBudgetStore(folder.newFolder())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fake.streamUsages = emptyList()
        fake.afterStream = { started.complete(Unit); release.await(); throw CancellationException("translation cancelled") }
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns
            Conversation(id = conversationId, assistantId = owner.id, messageNodes = emptyList())
        val service = ChatService(RuntimeEnvironment.getApplication(), serviceScope, mockk(relaxed = true),
            settingsStore, repository, mockk(relaxed = true), loop, handler,
            mockk(relaxed = true), manager, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), store, mockk(relaxed = true))
        try {
            val message = UIMessage.assistant("source")
            service.addConversationReference(conversationId)
            service.initializeConversation(conversationId)
            service.updateConversationState(conversationId) { it.copy(messageNodes = listOf(message.toMessageNode())) }
            service.translateMessage(conversationId, message, Locale.ENGLISH)
            withTimeout(10000) { started.await() }
            val job = serviceScope.coroutineContext[Job]!!.children.single()
            release.complete(Unit)
            withTimeout(10000) { job.join() }
            assertTrue("Provider cancellation must remain cancellation at the service boundary", job.isCancelled)
            assertTrue(service.errors.value.isEmpty())
            assertNull(service.getConversationFlow(conversationId).value.currentMessages.single().translation)
            assertState(store.getLedger(owner, conversationId, emptyList()), 5000)
        } finally {
            release.complete(Unit)
            service.cleanup()
            serviceScope.cancel()
        }
    }

    @Test fun `both translation branches share measured spend with main and store reload`() = runBlocking {
        for (model in listOf(regular, qwen)) {
            fake.requests.clear()
            val directory = folder.newFolder()
            val store = TokenBudgetStore(directory)
            val ledger = store.getLedger(owner, conversationId, emptyList())
            fake.translationUsage = TokenUsage(10, 20)
            translate(ledger, model)
            val translationRequest = fake.requests.single()
            assertEquals((5000 - AutoContextCompression.estimateTokens(translationRequest.messages)).toInt(),
                translationRequest.params.maxTokens)
            collectMain(ledger)
            assertState(ledger, 45)
            val request = fake.requests.last()
            assertEquals(5000 - 30 - mainEstimate(request.messages), request.params.maxTokens!!.toLong())
            assertSame(ledger, store.getLedger(owner, conversationId, emptyList()))
            assertState(TokenBudgetStore(directory).getLedger(owner, conversationId, emptyList()), 45)
            assertEquals(2, fake.requests.size)
        }
    }

    @Test fun `stream cumulative split usage is charged once and rendered unchanged`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 5000)
        fake.streamUsages = listOf(TokenUsage(promptTokens = 10), TokenUsage(completionTokens = 5), TokenUsage(10, 8))
        val callbacks = mutableListOf<String>()
        val emitted = withContext(TokenBudgetContext(ledger)) {
            handler.translateText(settings, "source", Locale.ENGLISH) { callbacks += it }.toList()
        }
        assertState(ledger, 18)
        assertEquals(callbacks, emitted)
        assertEquals("translated", emitted.last())
        assertEquals(1, fake.requests.size)
    }

    @Test fun `stream cap crossing stops later chunks and future provider calls`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        fake.streamUsages = listOf(TokenUsage(totalTokens = 100))
        fake.afterStream = { error("must stop before following provider chunks") }
        assertTrue(capture { translate(ledger) } is TokenBudgetExceededException)
        assertState(ledger, 100)
        assertTrue(capture { translate(ledger) } is TokenBudgetExceededException)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `submitted stream failure conservatively charges and preserves provider error`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val failure = IOException("network unavailable")
        fake.streamUsages = emptyList()
        fake.afterStream = { throw failure }
        val error = capture { translate(ledger) }
        assertTrue(error is IOException)
        assertEquals(failure.message, error!!.message)
        assertState(ledger, 100)
        assertTrue(capture { translate(ledger) } is TokenBudgetExceededException)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `Qwen submitted failure preserves provider error and blocks next bill`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        val failure = IOException("MT unavailable")
        fake.beforeGenerate = { throw failure }
        val error = capture { translate(ledger, qwen) }
        assertTrue(error is IOException)
        assertEquals(failure.message, error!!.message)
        assertState(ledger, 100)
        assertTrue(capture { translate(ledger, qwen) } is TokenBudgetExceededException)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `successful translation without usage retains the conservative submitted charge`() = runBlocking {
        fake.omitUsage = true
        for (model in listOf(regular, qwen)) {
            val ledger = TokenBudgetLedger(true, hardCap = 100)
            assertEquals("translated", translate(ledger, model).last())
            assertState(ledger, 100)
            assertTrue(capture { translate(ledger, model) } is TokenBudgetExceededException)
        }
        assertEquals(2, fake.requests.size)
    }

    @Test fun `translation cancellation closes uncertain submitted capacity`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        fake.streamUsages = emptyList()
        fake.afterStream = { started.complete(Unit); awaitCancellation() }
        var observed: CancellationException? = null
        val job = launch {
            try { translate(ledger) } catch (error: CancellationException) { observed = error; throw error }
        }
        withTimeout(10000) { started.await() }
        job.cancelAndJoin()
        assertNotNull(observed)
        assertTrue(job.isCancelled)
        assertState(ledger, 100)
        assertTrue(capture { translate(ledger) } is TokenBudgetExceededException)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `translation cancellation preserves cleanup persistence failure as suppressed`() = runBlocking {
        var writes = 0
        val caller = owner.copy(tokenBudgetHardCap = 100)
        val ledger = TokenBudgetStore(folder.newFolder(), writeRecord = { file, value ->
            if (++writes > 1) throw IOException("disk full during cleanup")
            file.writeText(value)
        }).getLedger(caller, conversationId, emptyList())
        val started = CompletableDeferred<Unit>()
        fake.streamUsages = emptyList()
        fake.afterStream = { started.complete(Unit); awaitCancellation() }
        var observed: CancellationException? = null
        val job = launch {
            try { translate(ledger) } catch (error: CancellationException) { observed = error; throw error }
        }
        withTimeout(10000) { started.await() }
        val requestedCancellation = CancellationException("cancel translation with failed durable cleanup")
        job.cancel(requestedCancellation)
        job.join()
        assertNotNull(observed)
        assertTrue("Cleanup persistence error must be suppressed on cancellation",
            requestedCancellation.suppressed.any { it is TokenBudgetPersistenceException })
        assertTrue(capture { translate(ledger) } is TokenBudgetPersistenceException)
        assertEquals(0L, ledger.snapshot().reservedTokens)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `auxiliary mark submitted persistence failures stop before provider`() = runBlocking {
        for (model in listOf(regular, qwen, ocr)) {
            val ledger = TokenBudgetLedger(true, hardCap = 5000, durableCallback = { _, _ -> throw IOException("disk full") })
            val error = capture {
                if (model == ocr) withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image()) }
                else translate(ledger, model)
            }
            assertTrue("Expected durable failure for ${model.modelId}; calls=${fake.requests.size}, error=$error",
                error is TokenBudgetPersistenceException)
            assertEquals(0, fake.requests.size)
            assertEquals(0L, ledger.snapshot().reservedTokens)
        }
    }

    @Test fun `missing owner blocks translation and OCR without swallowing guard`() = runBlocking {
        var currentOwner: Assistant? = owner
        val ledger = TokenBudgetStore(folder.newFolder(), assistantSource = { currentOwner })
            .getLedger(owner, conversationId, emptyList())
        currentOwner = null
        assertTrue(capture { translate(ledger) } is TokenBudgetOwnerUnavailableException)
        assertTrue(capture { withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image()) } }
            is TokenBudgetOwnerUnavailableException)
        assertEquals(0, fake.requests.size)
    }

    @Test fun `OCR measured accounting persistence failure escapes and stops future billing`() = runBlocking {
        var writes = 0
        val ledger = TokenBudgetStore(folder.newFolder(), writeRecord = { file, value ->
            if (++writes > 1) throw IOException("disk full recording OCR usage")
            file.writeText(value)
        }).getLedger(owner, conversationId, emptyList())
        val error = capture { withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image()) } }
        assertTrue(error is TokenBudgetPersistenceException)
        assertTrue(capture { translate(ledger) } is TokenBudgetPersistenceException)
        assertEquals(0L, ledger.snapshot().reservedTokens)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `OCR provider failure retains suppressed durable cleanup failure instead of fallback`() = runBlocking {
        var writes = 0
        val ledger = TokenBudgetStore(folder.newFolder(), writeRecord = { file, value ->
            if (++writes > 1) throw IOException("disk full after OCR failure")
            file.writeText(value)
        }).getLedger(owner, conversationId, emptyList())
        val failure = IOException("OCR provider failed")
        fake.beforeGenerate = { throw failure }
        val error = capture { withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image()) } }
        assertTrue(error is IOException)
        assertEquals(failure.message, error!!.message)
        assertTrue(failure.suppressed.any { it is TokenBudgetPersistenceException })
        assertTrue(capture { translate(ledger) } is TokenBudgetPersistenceException)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `explicit GenerationLoop ledger reaches OCR before main reservation without external context`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 1500, initialSpentTokens = 1400)
        val error = capture { collectMain(ledger, image()) }
        assertTrue("Expected OCR budget rejection; models=${fake.requests.map { it.params.model.modelId }}, error=$error",
            error is TokenBudgetExceededException)
        assertEquals(0, fake.requests.size)
        assertState(ledger, 1400)
    }

    @Test fun `explicit OCR ledger takes precedence over unrelated inherited context`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 1500, initialSpentTokens = 1400)
        val unrelated = TokenBudgetLedger(false)
        assertTrue(capture { withContext(TokenBudgetContext(unrelated)) { collectMain(ledger, image()) } }
            is TokenBudgetExceededException)
        assertEquals(0, fake.requests.size)
        assertState(ledger, 1400)
        assertState(unrelated, 0)
    }

    @Test fun `OCR measured usage is charged before main and bounds custom output aliases`() = runBlocking {
        val directory = folder.newFolder()
        val ledger = TokenBudgetStore(directory).getLedger(owner, conversationId, emptyList())
        collectMain(ledger, image())
        assertState(ledger, 1115)
        val ocrRequest = fake.requests.first()
        val ocrMax = 5000 - AutoContextCompression.estimateTokens(ocrRequest.messages)
        assertEquals(ocrMax, ocrRequest.params.maxTokens!!.toLong())
        assertEquals(ocrMax, ocrRequest.params.customBody.first().value.jsonPrimitive.int.toLong())
        assertEquals(ocrMax, ocrRequest.params.customBody.last().value.jsonObject.getValue("maxOutputTokens").jsonPrimitive.int.toLong())
        assertEquals(ocr.customHeaders, ocrRequest.params.customHeaders)
        val mainRequest = fake.requests.last()
        assertTrue(mainRequest.messages.single().toText().contains("<image_file_ocr>"))
        assertEquals(5000 - 1100 - mainEstimate(mainRequest.messages), mainRequest.params.maxTokens!!.toLong())
        assertState(TokenBudgetStore(directory).getLedger(owner, conversationId, emptyList()), 1115)
        assertEquals(listOf("ocr", "main"), fake.requests.map { it.params.model.modelId })
    }

    @Test fun `OCR reaches cap and prevents main request`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 1500)
        fake.ocrUsage = TokenUsage(totalTokens = 1500)
        val error = capture { collectMain(ledger, image()) }
        assertTrue(error is TokenBudgetExceededException)
        assertEquals("Достигнут предел токенов; продолжение остановлено.", error!!.message)
        assertEquals(listOf("ocr"), fake.requests.map { it.params.model.modelId })
        assertState(ledger, 1500)
    }

    @Test fun `OCR budget and provider cancellation errors escape instead of fallback text`() = runBlocking {
        val exhausted = TokenBudgetLedger(true, hardCap = 100, initialSpentTokens = 100)
        assertTrue(capture { withContext(TokenBudgetContext(exhausted)) { OcrTransformer.performOcr(image()) } }
            is TokenBudgetExceededException)
        assertEquals(0, fake.requests.size)
        val ledger = TokenBudgetLedger(true, hardCap = 1500)
        val cancelled = CancellationException("OCR cancelled")
        fake.beforeGenerate = { throw cancelled }
        val error = capture { withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image()) } }
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message, error!!.message)
        assertState(ledger, 1500)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `ordinary OCR provider failure charges submitted uncertainty and blocks main`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 1500)
        fake.beforeGenerate = { throw IOException("OCR unavailable") }
        assertTrue(capture { collectMain(ledger, image()) } is TokenBudgetExceededException)
        assertState(ledger, 1500)
        assertEquals(listOf("ocr"), fake.requests.map { it.params.model.modelId })
    }

    @Test fun `OCR cache hit does not reserve or bill even after cap is exhausted`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 1500)
        val image = image()
        val first = withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image) }
        assertTrue(first.contains("<image_file_ocr>"))
        ledger.updateConfiguration(true, null, 1100)
        val second = withContext(TokenBudgetContext(ledger)) { OcrTransformer.performOcr(image) }
        assertEquals(first, second)
        assertState(ledger, 1100)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `standalone and disabled auxiliary requests preserve ordinary params and output`() = runBlocking {
        val disabled = TokenBudgetLedger(false, hardCap = 1, durableCallback = { _, _ -> error("disabled writes") })
        for (ledger in listOf(null, disabled)) {
            assertEquals("translated", translate(ledger).last())
            assertNull(fake.requests.last().params.maxTokens)
            assertEquals("translated", translate(ledger, qwen).single())
            val params = fake.requests.last().params
            assertNull(params.maxTokens)
            assertEquals(0.3f, params.temperature)
            assertEquals(0.95f, params.topP)
            assertEquals("English", params.customBody.single().value.jsonObject.getValue("target_lang").jsonPrimitive.content)
        }
        val result = withContext(TokenBudgetContext(disabled)) { OcrTransformer.performOcr(image()) }
        assertTrue(result.contains("<image_file_ocr>"))
        assertEquals(ocr.customBodies, fake.requests.last().params.customBody)
        assertEquals(ocr.customHeaders, fake.requests.last().params.customHeaders)
        assertNull(fake.requests.last().params.maxTokens)
        assertState(disabled, 0)
    }

    @Test fun `image capable main and remote images skip OCR`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 5000)
        collectMain(ledger, image(), main.copy(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)))
        collectMain(ledger, UIMessagePart.Image("https://fixture.invalid/image.png"))
        assertEquals(listOf("main", "main"), fake.requests.map { it.params.model.modelId })
        assertState(ledger, 30)
    }

    private suspend fun translate(ledger: TokenBudgetLedger?, model: Model = regular): List<String> {
        suspend fun collect() = handler.translateText(settings.copy(translateModeId = model.id), "source", Locale.ENGLISH).toList()
        return if (ledger == null) collect() else withContext(TokenBudgetContext(ledger)) { collect() }
    }

    private suspend fun collectMain(ledger: TokenBudgetLedger, image: UIMessagePart.Image? = null, model: Model = main) {
        val messages = if (image == null) listOf(UIMessage.user("next")) else listOf(UIMessage.user("").copy(parts = listOf(image)))
        loop.generateText(settings, model, messages, inputTransformers = listOf(OcrTransformer),
            assistant = owner, tokenBudget = ledger, maxSteps = 1).collect { chunk ->
            if (chunk is GenerationChunk.RootExecutionCheckpoint) chunk.ack.complete(Unit)
        }
    }

    private fun image() = UIMessagePart.Image("file:/fixture/${Uuid.random()}.png")
    private fun mainEstimate(messages: List<UIMessage>) = messages.sumOf { it.toText().toByteArray().size.toLong() / 3 + 16 }
    private fun assertState(ledger: TokenBudgetLedger, spent: Long) {
        assertEquals(spent, ledger.snapshot().spentTokens)
        assertEquals(0L, ledger.snapshot().reservedTokens)
    }
    private suspend fun capture(block: suspend () -> Any?): Throwable? = try { block(); null } catch (error: Throwable) { error }

    private data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)
    private class RecordingProvider : Provider<ProviderSetting> {
        val requests = mutableListOf<Request>()
        var translationUsage = TokenUsage(10, 20)
        var ocrUsage = TokenUsage(1000, 100)
        var omitUsage = false
        var streamUsages: List<TokenUsage>? = null
        var beforeGenerate: suspend () -> Unit = {}
        var afterStream: suspend () -> Unit = {}
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            requests += Request(messages, params)
            beforeGenerate()
            val usage = when (params.model.modelId) { "ocr" -> ocrUsage; "main" -> TokenUsage(10, 5); else -> translationUsage }
            return TextGenerationResult("result", params.model.modelId, UIMessage.assistant(if (params.model.modelId == "ocr") "visible text" else "translated"), usage = if (omitUsage) null else usage)
        }
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> {
            requests += Request(messages, params)
            return flow {
                emit(StreamChunk.TextDelta("text", "translated"))
                (streamUsages ?: if (omitUsage) emptyList() else listOf(translationUsage)).forEach { emit(StreamChunk.Usage(it)) }
                afterStream()
                emit(StreamChunk.Finish("stop"))
            }
        }
    }
}
