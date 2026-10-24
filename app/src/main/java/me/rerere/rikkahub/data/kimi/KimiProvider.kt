package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.*
import me.rerere.ai.util.HttpException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.io.IOException

class KimiProvider(
    private val client: OkHttpClient,
    private val repository: KimiAccountRepository,
    private val api: KimiApi,
    private val json: Json,
) : Provider<ProviderSetting.KimiCode> {
    override suspend fun listModels(providerSetting: ProviderSetting.KimiCode): List<Model> {
        val (account, payload) = authorizedGet("models")
        val models = parseKimiModels(payload)
        requireSession(account)
        repository.updateMetadata(account.sessionId) { it.copy(models = models) }
        return models.map(KimiModelInfo::toModel)
    }

    /** Profile and quota failures must not discard a valid login or overwrite cached metadata. */
    suspend fun refreshAccount(): List<String> {
        val errors = mutableListOf<String>()
        for (resource in listOf("me", "usages")) {
            try {
                val (account, payload) = authorizedGet(resource)
                requireSession(account)
                repository.updateMetadata(account.sessionId) { current ->
                    if (resource == "me") current.copy(
                        userId = payload.text("user_id") ?: current.userId,
                        name = payload.text("nickname")?.takeIf(String::isNotBlank)
                            ?: payload.text("email")?.takeIf(String::isNotBlank) ?: current.name,
                        membership = payload.text("user_level_name") ?: current.membership,
                        checkedAt = System.currentTimeMillis(),
                    ) else current.copy(quotas = parseKimiQuotas(payload), checkedAt = System.currentTimeMillis())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                errors += "${if (resource == "me") "Профиль" else "Квота"}: ${safeKimiError(error)}"
            }
        }
        return errors
    }

    private suspend fun authorizedGet(resource: String): Pair<KimiAccount, JsonObject> {
        var account = repository.acquire()
        val result = try { api.get(account.region, account.tokens.accessToken, resource) }
        catch (error: KimiApiException) {
            if (error.status != 401) throw error
            account = repository.acquire(forceRefresh = true, rejectedAccessToken = account.tokens.accessToken)
            api.get(account.region, account.tokens.accessToken, resource)
        }
        return account to result
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.KimiCode,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var replies = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            replies = handler.handle(replies, chunk)
            if (chunk is StreamChunk.Finish) finish = chunk
        }
        val completed = finish ?: throw IOException("Ответ Kimi прерван до завершения.")
        val message = replies.single()
        return TextGenerationResult(completed.responseId ?: message.id.toString(), completed.model ?: params.model.modelId,
            message, completed.finishReason, message.usage)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.KimiCode,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        var account = repository.acquire()
        if (account.models.none { it.id == params.model.modelId }) {
            listModels(providerSetting)
            account = repository.acquire()
        }
        val info = account.models.firstOrNull { it.id == params.model.modelId }
            ?: throw IOException("Эта модель недоступна в аккаунте Kimi. Обновите список моделей.")
        var retried = false
        while (true) {
            var emitted = false
            var finished = false
            try {
                transport(providerSetting, account, info, messages, params).collect {
                    emitted = true
                    if (it is StreamChunk.Finish) {
                        if (it.finishReason?.startsWith("incomplete") == true) {
                            throw IOException("Kimi не завершил ответ. Проверьте лимит токенов и повторите запрос.")
                        }
                        finished = true
                    }
                    emit(it)
                }
                if (!finished) throw IOException("Ответ Kimi прерван до завершения.")
                break
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val rejection = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<KimiApiException>().firstOrNull()
                if (rejection?.status != 401 || retried || emitted) throw error
                account = repository.acquire(forceRefresh = true, rejectedAccessToken = account.tokens.accessToken)
                retried = true
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        val responseError = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<KimiApiException>().firstOrNull()
        // OkHttp callbacks require IOException; outside that boundary an HTTP response is
        // a provider failure, so GenerationLoop must not label/retry it as a lost connection.
        if (responseError != null) throw HttpException(responseError.message.orEmpty())
        throw error
    }.flowOn(Dispatchers.IO)

    private suspend fun transport(
        setting: ProviderSetting.KimiCode, account: KimiAccount, info: KimiModelInfo,
        messages: List<UIMessage>, params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requireSession(account)
        val protocol = when (info.protocol) {
            "anthropic" -> "anthropic"
            "response", "openai_responses" -> "response"
            "kimi", "openai", "chat_completions" -> "chat_completions"
            else -> throw IOException("Версия приложения пока не поддерживает протокол этой модели Kimi.")
        }
        val scopedClient = client.newBuilder().followRedirects(false).followSslRedirects(false).apply {
            // Run before any test/network transport; credentials and errors never reach request logging.
            interceptors().add(0, Interceptor { chain ->
                val original = chain.request()
                if (!isKimiGenerationRequest(original, account.region, protocol)) {
                    val imageRequest = original.newBuilder().removeHeader("Authorization").removeHeader("x-api-key")
                    original.headers.names().filter { it.startsWith("X-Msh-", ignoreCase = true) }.forEach(imageRequest::removeHeader)
                    return@Interceptor chain.proceed(imageRequest.build())
                }
                requireSession(account)
                val builder = original.newBuilder().removeHeader("x-api-key")
                api.identityHeaders().forEach { (name, value) -> builder.header(name, value) }
                builder.header("Authorization", "Bearer ${account.tokens.accessToken}")
                if (protocol == "anthropic") builder.url(original.url.newBuilder().setQueryParameter("beta", "true").build())
                original.body?.let { body ->
                    val payload = json.parseToJsonElement(Buffer().also { body.writeTo(it) }.readUtf8()).jsonObject
                    builder.method(original.method, json.encodeToString(adaptKimiBody(payload, info, params.reasoningLevel))
                        .toRequestBody("application/json".toMediaType()))
                }
                val response = chain.proceed(builder.build())
                if (!response.isSuccessful) {
                    response.use { throw kimiGenerationError(it, account.tokens, json) }
                }
                response
            })
        }.build()
        // Tokens are intentionally transient: synthetic settings never enter SettingsStore.
        return if (protocol == "anthropic") {
            ClaudeProvider(scopedClient).streamText(ProviderSetting.Claude(id = setting.id,
                apiKey = account.tokens.accessToken, baseUrl = account.region.apiBase, customHeaders = setting.customHeaders), messages, params)
        } else {
            OpenAIProvider(scopedClient).streamText(ProviderSetting.OpenAI(id = setting.id,
                apiKey = account.tokens.accessToken, baseUrl = account.region.apiBase,
                useResponseApi = protocol == "response", customHeaders = setting.customHeaders), messages, params)
        }
    }

    private fun requireSession(account: KimiAccount) {
        if (repository.account.value?.sessionId != account.sessionId) throw CancellationException("Сессия Kimi изменилась.")
    }
}

/** OAuth credentials belong only to the exact TLS generation endpoint, never to attachments. */
internal fun isKimiGenerationRequest(request: Request, region: KimiRegion, protocol: String): Boolean {
    val base = region.apiBase.toHttpUrl()
    val path = when (protocol) {
        "anthropic" -> "/coding/v1/messages"
        "response" -> "/coding/v1/responses"
        "chat_completions" -> "/coding/v1/chat/completions"
        else -> return false
    }
    return request.method == "POST" && request.url.scheme == "https" && request.url.host == base.host &&
        request.url.port == base.port && request.url.username.isEmpty() && request.url.password.isEmpty() && request.url.encodedPath == path
}

internal fun adaptKimiBody(original: JsonObject, info: KimiModelInfo, level: ReasoningLevel): JsonObject {
    val body = original.toMutableMap()
    // Pro uses the server's fixed temperature 1; assistant/custom-body overrides are invalid.
    if (info.hasFixedTemperature) body.remove("temperature")
    listOf("thinking", "reasoning", "reasoning_effort", "output_config").forEach(body::remove)
    if (!info.reasoning && !info.requiresThinking) return JsonObject(body)
    val enabled = info.requiresThinking || level != ReasoningLevel.OFF
    val effort = level.effort.takeIf { level !in setOf(ReasoningLevel.AUTO, ReasoningLevel.OFF) && it in info.effectiveEfforts }
        ?: info.effectiveDefaultEffort
    when (info.protocol) {
        "anthropic" -> {
            body["thinking"] = buildJsonObject { put("type", if (enabled) "adaptive" else "disabled") }
            if (enabled && effort != null) body["output_config"] = buildJsonObject { put("effort", effort) }
        }
        "response", "openai_responses" -> if (enabled) {
            body["reasoning"] = buildJsonObject { if (effort != null) put("effort", effort) }
        }
        else -> {
            if (enabled) (body["messages"] as? JsonArray)?.let { messages ->
                body["messages"] = JsonArray(messages.map { element ->
                    val message = element as? JsonObject
                    if (message?.text("role") == "assistant" &&
                        (message["tool_calls"] as? JsonArray)?.isNotEmpty() == true &&
                        !message.containsKey("reasoning_content")) {
                        // An explicitly empty field is meaningful to Kimi; never invent thinking
                        // text or touch the original tool argument strings.
                        JsonObject(message + ("reasoning_content" to JsonPrimitive("")))
                    } else element
                })
            }
            body["thinking"] = buildJsonObject {
                put("type", if (enabled) "enabled" else "disabled")
                if (enabled && Regex("(?i)kimi[-_.]?k2[._-]6").containsMatchIn(info.id)) put("keep", "all")
            }
            if (enabled && effort != null) body["reasoning_effort"] = JsonPrimitive(effort)
        }
    }
    return JsonObject(body)
}

fun safeKimiError(error: Throwable): String = when (error) {
    is KimiApiException -> error.message.orEmpty()
    else -> "Проверьте подключение и повторите попытку. При необходимости войдите в Kimi заново."
}
