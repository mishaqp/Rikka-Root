package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

interface KimiAuthApi {
    suspend fun authorize(region: KimiRegion): KimiDeviceAuthorization
    suspend fun poll(region: KimiRegion, deviceCode: String): KimiPollResult
    suspend fun refresh(region: KimiRegion, tokens: KimiTokens): KimiTokens
}

data class KimiDeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUrl: String,
    val expiresInMillis: Long,
    val intervalMillis: Long,
) {
    override fun toString() = "KimiDeviceAuthorization([redacted], expiresInMillis=$expiresInMillis)"
}

sealed interface KimiPollResult {
    data class Success(val tokens: KimiTokens) : KimiPollResult
    data class Pending(val slowDown: Boolean) : KimiPollResult
    data object Expired : KimiPollResult
    data object Denied : KimiPollResult
}

private val TERMINAL_KIMI_CODES = setOf("invalid_grant", "invalid_token", "token_revoked")
private fun invalidKimiCredentials(status: Int, code: String) = status in setOf(400, 401, 403) && code in TERMINAL_KIMI_CODES

class KimiApiException(val status: Int, val code: String = "") : IOException(when {
    invalidKimiCredentials(status, code) -> "Сессия Kimi недействительна. Войдите снова."
    status == 401 -> "Kimi отклонил сессию (HTTP 401). Повторите вход."
    status == 402 || status == 403 -> "Kimi отклонил доступ (HTTP $status). Проверьте подписку Kimi Code и доступность модели для этого клиента."
    status == 429 -> "Квота или частота запросов Kimi исчерпана (HTTP 429). Попробуйте позже."
    status >= 500 -> "Сервис Kimi временно недоступен (HTTP $status). Попробуйте позже."
    else -> "Не удалось выполнить запрос Kimi (HTTP $status)."
}) {
    val retryable get() = status == 429 || status in 500..599
    val reauthenticationRequired get() = invalidKimiCredentials(status, code)
}

/** Native implementation of the published MoonshotAI/kimi-code device-code contract. */
class KimiApi(
    client: OkHttpClient,
    private val json: Json,
    private val identity: () -> Map<String, String>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : KimiAuthApi {
    private val authClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    fun identityHeaders(): Map<String, String> = identity()

    override suspend fun authorize(region: KimiRegion): KimiDeviceAuthorization = withContext(Dispatchers.IO) {
        val data = form(region, "device_authorization", mapOf("client_id" to CLIENT_ID)).let {
            if (it.first !in 200..299) throw apiError(it.first, it.second)
            it.second
        }
        val code = data.text("device_code")?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = data.text("user_code")?.takeIf(String::isNotBlank) ?: invalidResponse()
        val url = data.text("verification_uri_complete")?.toHttpUrlOrNull() ?: invalidResponse()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.host !in VERIFICATION_HOSTS) invalidResponse()
        val expires = positiveMillis(data.text("expires_in") ?: "600", 1_800)
        val interval = positiveMillis(data.text("interval") ?: "5", 300)
        KimiDeviceAuthorization(userCode, code, url.toString(), expires, interval)
    }

    override suspend fun poll(region: KimiRegion, deviceCode: String): KimiPollResult = withContext(Dispatchers.IO) {
        val (status, data) = form(region, "token", mapOf("client_id" to CLIENT_ID,
            "device_code" to deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (status in 200..299) return@withContext KimiPollResult.Success(parseTokens(data))
        if (status >= 500 || status == 429) throw apiError(status, data)
        when (data.text("error")) {
            "authorization_pending" -> KimiPollResult.Pending(false)
            "slow_down" -> KimiPollResult.Pending(true)
            "expired_token" -> KimiPollResult.Expired
            "access_denied" -> KimiPollResult.Denied
            else -> throw apiError(status, data)
        }
    }

    override suspend fun refresh(region: KimiRegion, tokens: KimiTokens): KimiTokens = withContext(Dispatchers.IO) {
        val (status, data) = form(region, "token", mapOf("client_id" to CLIENT_ID,
            "grant_type" to "refresh_token", "refresh_token" to tokens.refreshToken))
        if (status !in 200..299) throw apiError(status, data)
        parseTokens(data, tokens.refreshToken)
    }

    suspend fun get(region: KimiRegion, accessToken: String, resource: String): JsonObject = withContext(Dispatchers.IO) {
        require(resource in setOf("models", "me", "usages"))
        val request = Request.Builder().url("${region.apiBase}/$resource").apply {
            identityHeaders().forEach { (key, value) -> header(key, value) }
        }.header("Authorization", "Bearer $accessToken").header("Accept", "application/json").build()
        authClient.newCall(request).await().use { response ->
            val data = readJson(response)
            if (!response.isSuccessful) throw apiError(response.code, data)
            data
        }
    }

    private suspend fun form(region: KimiRegion, endpoint: String, fields: Map<String, String>): Pair<Int, JsonObject> {
        val request = Request.Builder().url("${region.oauthHost}/api/oauth/$endpoint")
            .apply { identityHeaders().forEach { (key, value) -> header(key, value) } }
            .header("Accept", "application/json")
            .post(FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()).build()
        return authClient.newCall(request).await().use { it.code to readJson(it) }
    }

    private fun readJson(response: Response): JsonObject {
        val source = response.body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) invalidResponse()
        val raw = source.readUtf8()
        return try {
            json.parseToJsonElement(raw) as? JsonObject ?: if (response.isSuccessful) invalidResponse() else JsonObject(emptyMap())
        } catch (_: kotlinx.serialization.SerializationException) {
            if (response.isSuccessful) invalidResponse() else JsonObject(emptyMap())
        } catch (_: IllegalArgumentException) {
            if (response.isSuccessful) invalidResponse() else JsonObject(emptyMap())
        }
    }

    private fun parseTokens(data: JsonObject, priorRefreshToken: String? = null): KimiTokens {
        val access = data.text("access_token")?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refresh = data.text("refresh_token")?.takeIf(String::isNotBlank) ?: priorRefreshToken ?: invalidResponse()
        val expires = positiveMillis(data.text("expires_in") ?: "", 366L * 86_400)
        val tokenType = data.text("token_type") ?: "Bearer"
        if (!tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        return KimiTokens(access, refresh, nowMillis() + expires, data.text("scope").orEmpty())
    }

    private fun positiveMillis(value: String, maximumSeconds: Long): Long {
        val seconds = value.toLongOrNull()?.takeIf { it in 1..maximumSeconds } ?: invalidResponse()
        return seconds * 1_000
    }

    private fun apiError(status: Int, data: JsonObject): KimiApiException {
        val code = data.text("error") ?: (data["error"] as? JsonObject)?.text("type")
        // Never reflect arbitrary upstream text: token errors may echo submitted credentials.
        return KimiApiException(status, code?.takeIf { it in TERMINAL_KIMI_CODES }.orEmpty())
    }

    private fun invalidResponse(): Nothing = throw IOException("Kimi вернул некорректный ответ. Повторите попытку.")

    companion object {
        private const val CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        private val VERIFICATION_HOSTS = setOf("auth.kimi.com", "auth.kimi.ai", "www.kimi.com", "www.kimi.ai",
            "kimi.com", "kimi.ai", "code.kimi.com", "code.kimi.ai")
    }
}
