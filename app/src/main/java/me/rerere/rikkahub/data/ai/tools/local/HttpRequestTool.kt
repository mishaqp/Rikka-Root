package me.rerere.rikkahub.data.ai.tools.local

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.tools.HttpToolSecretSanitizer
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink

internal fun httpRequestTool(client: OkHttpClient): Tool = Tool(
    name = "http_request",
    description = "Выполнить HTTP(S) запрос GET, POST, PUT, PATCH, DELETE или HEAD. " +
        "url без встроенного логина и пароля; headers — объект строковых заголовков; " +
        "body — строка либо json — JSON-объект, одновременно не передавать; GET/HEAD без тела. " +
        "timeout_seconds: 1–90, по умолчанию 30; max_bytes: 1–8388608, по умолчанию 1048576. " +
        "Возвращает status, headers (массивы значений без Set-Cookie), body, encoding (text/base64), truncated. " +
        "Перенаправления и автоматические повторы изменяющих запросов отключены; при 3xx проверьте Location и вызовите конечный URL отдельно. " +
        "Секретные заголовки скрываются в истории; после перезапуска передайте их заново. " +
        "GET/HEAD не требуют подтверждения; остальные методы используют обычное подтверждение инструмента.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject { put("type", "string"); put("description", "HTTP(S) URL без логина и пароля") })
            put("method", buildJsonObject {
                put("type", "string"); put("default", "GET")
                put("enum", JsonArray(HTTP_METHODS.map(::JsonPrimitive)))
            })
            put("headers", buildJsonObject { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "string") }) })
            put("body", buildJsonObject { put("type", "string"); put("description", "Тело запроса; несовместимо с json и GET/HEAD") })
            put("json", buildJsonObject { put("type", "object"); put("description", "JSON-тело запроса; несовместимо с body и GET/HEAD") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 90); put("default", 30) })
            put("max_bytes", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", HTTP_MAX_BYTES); put("default", HTTP_DEFAULT_BYTES) })
        }, required = listOf("url"))
    },
    needsApproval = { input ->
        val method = (input as? JsonObject)?.get("method")
        val name = if (method == null) "GET" else (method as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase(Locale.ROOT)
        name !in setOf("GET", "HEAD")
    },
    execute = { input ->
        val result = try {
            val prepared = prepareHttpRequest(input)
            // Keep the app's proxy/TLS/network settings, without logging credentials or caching responses.
            val boundedClient = client.newBuilder().apply {
                interceptors().removeAll { it is RequestLoggingInterceptor || it is AIRequestInterceptor }
                networkInterceptors().removeAll { it is RequestLoggingInterceptor || it is AIRequestInterceptor }
            }.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).cache(null)
                .callTimeout(prepared.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(prepared.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(prepared.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(prepared.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
            executeHttpRequest(boundedClient, prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: HttpRequestInputException) {
            httpError(invalid.message.orEmpty())
        } catch (_: Exception) {
            // Native header/URL/transport exceptions can contain the actual supplied credentials.
            httpError("Не удалось выполнить HTTP-запрос. Проверьте адрес, заголовки и подключение к интернету.")
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private const val HTTP_DEFAULT_BYTES = 1024 * 1024
private const val HTTP_MAX_BYTES = 8 * 1024 * 1024
private val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
private class HttpRequestInputException(message: String) : IllegalArgumentException(message)
private data class PreparedHttpRequest(val request: Request, val timeoutSeconds: Int, val maxBytes: Int, val secrets: List<String>)

private fun invalidHttp(message: String): Nothing = throw HttpRequestInputException(message)
private fun httpError(message: String): JsonObject = buildJsonObject { put("error", message) }

private fun prepareHttpRequest(input: JsonElement): PreparedHttpRequest {
    val args = input as? JsonObject ?: invalidHttp("Передайте параметры HTTP-запроса в JSON-объекте.")
    fun string(name: String, fallback: String? = null): String {
        val value = args[name] ?: return fallback ?: invalidHttp("Укажите адрес HTTP(S) в url.")
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalidHttp("Параметр $name должен быть строкой.")
    }
    fun boundedInt(name: String, default: Int, maximum: Int): Int {
        val raw = args[name] ?: return default
        val value = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: invalidHttp("Параметр $name должен быть целым числом от 1 до $maximum.")
        if (value !in 1..maximum) invalidHttp("Параметр $name должен быть от 1 до $maximum.")
        return value
    }
    val url = string("url")
    val uri = try { URI(url) } catch (_: Exception) { invalidHttp("Укажите корректный адрес HTTP(S).") }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
        invalidHttp("Разрешены только адреса HTTP(S) без встроенного логина и пароля.")
    }
    val method = string("method", "GET").uppercase(Locale.ROOT)
    if (method !in HTTP_METHODS) invalidHttp("Допустимы методы GET, POST, PUT, PATCH, DELETE и HEAD.")
    val timeout = boundedInt("timeout_seconds", 30, 90)
    val maxBytes = boundedInt("max_bytes", HTTP_DEFAULT_BYTES, HTTP_MAX_BYTES)
    if ("body" in args && "json" in args) invalidHttp("Передайте только один параметр тела: body или json.")
    if (method in setOf("GET", "HEAD") && ("body" in args || "json" in args)) invalidHttp("Методы GET и HEAD не поддерживают body и json.")
    val rawBody = if ("body" in args) string("body") else null
    val jsonBody = args["json"]?.let { it as? JsonObject ?: invalidHttp("Параметр json должен быть JSON-объектом.") }
    val headers = args["headers"]?.let { it as? JsonObject ?: invalidHttp("Параметр headers должен быть объектом строковых заголовков.") }
        ?: JsonObject(emptyMap())
    val builder = Request.Builder().url(url)
    val secrets = mutableListOf<String>()
    headers.forEach { (name, raw) ->
        val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalidHttp("Значения headers должны быть строками.")
        if (HttpToolSecretSanitizer.isSecretHeader(name)) {
            if (value.contains(HttpToolSecretSanitizer.MASK)) invalidHttp("Секретный заголовок скрыт в истории. Повторно передайте его значение перед выполнением HTTP-запроса.")
            if (value.isNotEmpty()) {
                secrets += value
                if (name.equals("Authorization", true) || name.equals("Proxy-Authorization", true)) {
                    value.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() }?.let { secrets += it }
                }
            }
        }
        try { builder.header(name, value) } catch (_: IllegalArgumentException) {
            invalidHttp("Недопустимое имя или значение заголовка. Удалите переносы строк и проверьте формат headers.")
        }
    }
    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        ?.let { (it as JsonPrimitive).content }
    val mediaType = contentType?.toMediaTypeOrNull()
        ?: if (contentType != null) invalidHttp("Укажите корректный заголовок Content-Type.")
        else if (jsonBody != null) "application/json; charset=utf-8".toMediaTypeOrNull()
        else "text/plain; charset=utf-8".toMediaTypeOrNull()
    val payload = (rawBody ?: jsonBody?.toString())?.toRequestBody(mediaType)
        ?: if (method !in setOf("GET", "HEAD")) "".toRequestBody(mediaType) else null
    // retryOnConnectionFailure(false) alone does not stop OkHttp's 503/421 follow-ups.
    // Mark every mutation non-replayable, including DELETE with no supplied payload.
    val requestBody = payload?.let(::HttpSingleUseBody)
    return PreparedHttpRequest(builder.method(method, requestBody).build(), timeout, maxBytes,
        secrets.distinct().sortedByDescending { it.length })
}

private class HttpSingleUseBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

/** The continuation stays suspended through body reading, so Stop closes the socket immediately. */
private suspend fun executeHttpRequest(client: OkHttpClient, prepared: PreparedHttpRequest): JsonObject =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(prepared.request)
        val activeResponse = AtomicReference<Response?>(null)
        continuation.invokeOnCancellation {
            call.cancel()
            runCatching { activeResponse.get()?.close() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(httpError(
                    "Не удалось выполнить HTTP-запрос в отведённое время. Проверьте адрес и подключение; перед повтором изменяющего запроса проверьте его результат."
                ))
            }

            override fun onResponse(call: Call, response: Response) {
                activeResponse.set(response)
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        val buffer = Buffer()
                        if (prepared.request.method != "HEAD") {
                            val source = response.body.source()
                            val limit = prepared.maxBytes.toLong() + 1
                            while (buffer.size < limit && continuation.isActive) {
                                if (source.read(buffer, minOf(8192L, limit - buffer.size)) == -1L) break
                            }
                        }
                        if (!continuation.isActive) return
                        val truncated = buffer.size > prepared.maxBytes
                        val bytes = buffer.readByteArray(minOf(buffer.size, prepared.maxBytes.toLong()))
                        // Do not drain a remaining large/infinite response when closing it.
                        if (truncated) call.cancel()
                        httpResponse(response, bytes, truncated, prepared.secrets)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(httpError(
                        "Не удалось полностью прочитать HTTP-ответ. Проверьте подключение; перед повтором изменяющего запроса проверьте его результат."
                    ))
                } finally {
                    activeResponse.set(null)
                }
            }
        })
    }

private fun redactHttpText(value: String, secrets: List<String>): String =
    secrets.fold(value) { text, secret -> text.replace(secret, HttpToolSecretSanitizer.MASK) }

private fun httpResponse(response: Response, bytes: ByteArray, truncated: Boolean, secrets: List<String>): JsonObject {
    val mediaType = response.body.contentType()
    val text = decodeHttpText(bytes, mediaType)
    val body = if (text != null) redactHttpText(text, secrets) else {
        // Redact the original bytes too: base64 must not become a way to recover echoed credentials.
        var binary = bytes.toString(Charsets.ISO_8859_1)
        for (secret in secrets) {
            val encoded = secret.toByteArray().toString(Charsets.ISO_8859_1)
            binary = binary.replace(encoded, "*".repeat(encoded.length))
        }
        Base64.getEncoder().encodeToString(binary.toByteArray(Charsets.ISO_8859_1))
    }
    return buildJsonObject {
        put("status", response.code)
        put("headers", buildJsonObject {
            response.headers.names().filterNot { it.equals("Set-Cookie", true) }.forEach { name ->
                val safeName = redactHttpText(name, secrets)
                put(safeName, JsonArray(response.headers.values(name).map { value ->
                    JsonPrimitive(if (HttpToolSecretSanitizer.isSecretHeader(name)) HttpToolSecretSanitizer.MASK else redactHttpText(value, secrets))
                }))
            }
        })
        put("body", body)
        put("encoding", if (text != null) "text" else "base64")
        put("truncated", truncated)
    }
}

private fun decodeHttpText(bytes: ByteArray, mediaType: MediaType?): String? {
    val subtype = mediaType?.subtype?.lowercase(Locale.ROOT)
    val declaredText = mediaType?.type == "text" || subtype in setOf("json", "xml", "javascript", "x-www-form-urlencoded") ||
        subtype?.endsWith("+json") == true || subtype?.endsWith("+xml") == true
    if (mediaType != null && !declaredText) return null
    val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return try {
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        if (mediaType == null && text.any { it.isISOControl() && it !in "\r\n\t" }) null else text
    } catch (_: Exception) {
        // A truncated final code point must not fabricate text or corrupt binary data.
        null
    }
}
