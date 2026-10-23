package me.rerere.rikkahub.miniapps

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// Behavioral reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_fetch.dart.
class MiniAppFetch(client: OkHttpClient = OkHttpClient()) {
    // This dedicated client never inherits application/provider auth, cookies or authenticators.
    // Injected interceptors are retained solely for transport testing; production uses the default client.
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetch(app: MiniApp, args: JsonObject): JsonObject {
        var url = url(app, args["url"])
        var method = (args["method"]?.takeUnless { it == JsonNull }?.wireText() ?: "GET").uppercase(Locale.ROOT)
        if (method !in METHODS) throw MiniAppException("invalid_method", "method must be one of ${METHODS.joinToString(", ")}.")
        val headers = Headers.Builder()
        val rawHeaders = args["headers"]?.takeUnless { it == JsonNull }
        if (rawHeaders != null && rawHeaders !is JsonObject) throw MiniAppException("invalid_headers", "headers must be an object of strings.")
        (rawHeaders as? JsonObject)?.forEach { (key, value) -> headers.add(key, value.wireText()) }
        val rawBody = args["body"]?.takeUnless { it == JsonNull }
        if (rawBody != null && (rawBody !is JsonPrimitive || !rawBody.isString)) throw MiniAppException("invalid_body", "body must be a string; use JSON.stringify for JSON.")
        var body = (rawBody as? JsonPrimitive)?.content
        if ((body?.toByteArray(Charsets.UTF_8)?.size ?: 0) > MAX_REQUEST_BYTES) throw MiniAppException("body_too_large", "The request body is over 1 MB.")
        for (redirects in 0..MAX_REDIRECTS) {
            val request = Request.Builder().url(url).headers(headers.build()).method(method,
                if (method in setOf("GET", "HEAD")) null else (body ?: "").toRequestBody(null)).build()
            // Consume the stream inside the callback: cancellation cancels the live call, including body reads.
            val result = execute(request, redirects)
            if (result.location != null) {
                url = url(app, JsonPrimitive(url.resolve(result.location)?.toString() ?: result.location))
                if (result.status == 303 || (method == "POST" && result.status in setOf(301, 302))) { method = "GET"; body = null }
            } else return buildJsonObject {
                put("url", url.toString()); put("status", result.status); put("ok", result.status in 200..299)
                put("headers", result.headers); put("body", result.body)
            }
        }
        error("unreachable")
    }
    private data class Result(val status: Int, val headers: JsonObject, val body: String, val location: String?)
    private suspend fun execute(request: Request, redirects: Int): Result = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val location = response.header("location")?.takeIf { response.code in REDIRECTS }
                        if (location != null && redirects == MAX_REDIRECTS) throw MiniAppException("too_many_redirects", "The server redirected too many times.")
                        val output = ByteArrayOutputStream()
                        // Redirect bodies are closed without draining an untrusted unlimited stream.
                        if (location == null) response.body?.byteStream()?.use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_RESPONSE_BYTES) throw MiniAppException("response_too_large", "The response is over 2 MB.")
                                output.write(buffer, 0, count)
                            }
                        }
                        Result(response.code, buildJsonObject { response.headers.names().forEach { name -> put(name.lowercase(Locale.ROOT), response.headers.values(name).joinToString(", ")) } }, output.toString(Charsets.UTF_8.name()), location)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun url(app: MiniApp, raw: JsonElement?): HttpUrl {
        val parsed = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.toHttpUrlOrNull()
            ?: throw MiniAppException("invalid_url", "url must be an absolute http(s) address.")
        if (!MiniAppStore.allowsHost(app, parsed.host)) throw MiniAppException("host_not_allowed", "${parsed.host} is not in \"network\" of moru-app.json.")
        return parsed
    }
    companion object {
        val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        const val MAX_REQUEST_BYTES = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
