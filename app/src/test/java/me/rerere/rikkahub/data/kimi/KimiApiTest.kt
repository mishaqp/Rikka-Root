package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class KimiApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val request = AtomicReference<Request>()
    private fun client(status: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
        request.set(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
            .message("test").body(body.toResponseBody()).build()
    }.build()
    private fun api(client: OkHttpClient) = KimiApi(client, json,
        identity = { mapOf("User-Agent" to "Rikka-Root/test", "X-Msh-Platform" to "rikka_root_android") },
        nowMillis = { 1_000L })

    @Test fun `device authorization uses region form fields and truthful client identity`() = runBlocking {
        for (region in KimiRegion.entries) {
            val client = client(200, """{"user_code":"ABCD","device_code":"secret-device","verification_uri_complete":"${region.oauthHost}/device?user_code=ABCD","expires_in":600,"interval":5}""")
            try {
                val auth = api(client).authorize(region)
                assertEquals("ABCD", auth.userCode)
                assertEquals(5_000L, auth.intervalMillis)
                assertEquals(600_000L, auth.expiresInMillis)
                assertEquals("${region.oauthHost}/api/oauth/device_authorization", request.get().url.toString())
                assertEquals("17e5f671-d194-4dfb-9706-5516cb48c098", (request.get().body as FormBody).value(0))
                assertEquals("Rikka-Root/test", request.get().header("User-Agent"))
                assertEquals("rikka_root_android", request.get().header("X-Msh-Platform"))
                assertFalse(auth.toString().contains("secret-device"))
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun `device polling distinguishes pending slowdown expiry and denial`() = runBlocking {
        val cases = mapOf("authorization_pending" to KimiPollResult.Pending(false),
            "slow_down" to KimiPollResult.Pending(true), "expired_token" to KimiPollResult.Expired,
            "access_denied" to KimiPollResult.Denied)
        for ((error, expected) in cases) {
            val client = client(400, """{"error":"$error"}""")
            try {
                assertEquals(expected, api(client).poll(KimiRegion.CN, "device"))
                val form = request.get().body as FormBody
                assertEquals("urn:ietf:params:oauth:grant-type:device_code", form.value((0 until form.size).first { form.name(it) == "grant_type" }))
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun `successful poll validates tokens and records actual expiry`() = runBlocking {
        val client = client(200, """{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600,"token_type":"Bearer"}""")
        try {
            val result = api(client).poll(KimiRegion.GLOBAL, "device") as KimiPollResult.Success
            assertEquals(3_601_000L, result.tokens.expiresAt)
            assertEquals("test-access", result.tokens.accessToken)
            assertFalse(result.tokens.toString().contains("test-access"))
            assertFalse(result.tokens.toString().contains("test-refresh"))
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `malformed tokens and hostile authorization urls never become a session`() = runBlocking {
        val bodies = listOf("""{"access_token":"secret-that-must-not-leak","expires_in":3600}""",
            """{"access_token":"secret-that-must-not-leak","refresh_token":"refresh","expires_in":-1}""")
        for (body in bodies) {
            val client = client(200, body)
            try {
                val error = runCatching { api(client).poll(KimiRegion.CN, "device") }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(error.toString().contains("secret-that-must-not-leak"))
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
        val client = client(200, """{"user_code":"A","device_code":"B","verification_uri_complete":"https://kimi.com.attacker.invalid/login"}""")
        try { assertTrue(runCatching { api(client).authorize(KimiRegion.CN) }.isFailure) }
        finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `refresh sends correct grant and keeps prior refresh token if not rotated`() = runBlocking {
        val client = client(200, """{"access_token":"new","expires_in":120}""")
        try {
            val tokens = api(client).refresh(KimiRegion.CN, KimiTokens("old", "refresh-old", 0))
            assertEquals("new", tokens.accessToken)
            assertEquals("refresh-old", tokens.refreshToken)
            val form = request.get().body as FormBody
            assertEquals("refresh_token", form.value((0 until form.size).first { form.name(it) == "grant_type" }))
            assertEquals("refresh-old", form.value((0 until form.size).first { form.name(it) == "refresh_token" }))
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `server errors are retryable and cannot reveal response credentials`() = runBlocking {
        val client = client(503, """{"error_description":"secret-access test-refresh"}""")
        try {
            val error = runCatching { api(client).poll(KimiRegion.CN, "device") }.exceptionOrNull() as KimiApiException
            assertTrue(error.retryable)
            assertEquals(503, error.status)
            assertFalse(error.message.orEmpty().contains("secret-access"))
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `invalid grant is terminal while subscription denial does not revoke refresh token`() = runBlocking {
        val client = client(400, """{"error":"invalid_grant"}""")
        try {
            val error = runCatching { api(client).refresh(KimiRegion.CN, KimiTokens("a", "r", 0)) }.exceptionOrNull() as KimiApiException
            assertTrue(error.reauthenticationRequired)
        } finally { client.dispatcher.executorService.shutdownNow() }
        val denied = this@KimiApiTest.client(403, """{"error":{"message":"access denied"}}""")
        try {
            val error = runCatching { api(denied).get(KimiRegion.CN, "token", "models") }.exceptionOrNull() as KimiApiException
            assertFalse(error.reauthenticationRequired)
            assertTrue(error.message.orEmpty().contains("подписк"))
        } finally { denied.dispatcher.executorService.shutdownNow() }
    }
    @Test fun `server failure takes precedence over pending oauth fields`() = runBlocking {
        val client = client(503, """{"error":"authorization_pending"}""")
        try {
            val failure = runCatching { api(client).poll(KimiRegion.CN, "device") }.exceptionOrNull()
            assertTrue(failure is KimiApiException && failure.retryable)
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `temporary server failure cannot invalidate a refresh token even if body says invalid grant`() {
        assertFalse(KimiApiException(503, "invalid_grant").reauthenticationRequired)
    }

}
