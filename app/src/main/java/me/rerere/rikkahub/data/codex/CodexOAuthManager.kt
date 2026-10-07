package me.rerere.rikkahub.data.codex

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.http.await
import me.rerere.rikkahub.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val authClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val sessions = CodexOAuthSessions()
    private val lifecycle = CodexOAuthLoginLifecycle(scope)
    private val _status = MutableStateFlow<CodexOAuthStatus>(CodexOAuthStatus.Idle)
    val status: StateFlow<CodexOAuthStatus> = _status.asStateFlow()

    @Synchronized
    fun startLogin() {
        _status.value = CodexOAuthStatus.Waiting
        lifecycle.start {
            val owner = currentCoroutineContext()[Job]
            try {
                withTimeout(CODEX_OAUTH_TIMEOUT_MS) {
                    val result = CompletableDeferred<OAuthCallback>()
                    val port = withContext(Dispatchers.IO) { ensureCallbackServer(result) }
                    val session = sessions.start("http://127.0.0.1:$port/auth/callback")
                    val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("client_id", CLIENT_ID)
                        .appendQueryParameter("redirect_uri", session.redirectUri)
                        .appendQueryParameter("scope", DEFAULT_SCOPES)
                        .appendQueryParameter("state", session.state)
                        .appendQueryParameter("code_challenge", session.challenge)
                        .appendQueryParameter("code_challenge_method", "S256")
                        .appendQueryParameter("id_token_add_organizations", "true")
                        .appendQueryParameter("codex_cli_simplified_flow", "true")
                        .appendQueryParameter("originator", CODEX_ORIGINATOR)
                        .build()
                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    val callback = result.await()
                    callback.error?.let { error(it) }
                    awaitNetworkUnblocked()
                    val account = exchangeCode(requireNotNull(callback.code), callback.session)
                    _status.value = CodexOAuthStatus.Success(account.id)
                }
            } catch (error: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                if (lifecycle.isCurrent(owner)) _status.value = CodexOAuthStatus.Error("OpenAI sign-in timed out")
            } catch (error: CancellationException) {
                if (lifecycle.isCurrent(owner)) _status.value = CodexOAuthStatus.Idle
                throw error
            } catch (error: Exception) {
                // Token/JWT decoding exceptions can contain the original response in their
                // message. Log only the type and show a fixed user-facing error.
                Log.e(TAG, "OAuth sign-in failed (${error::class.java.simpleName})")
                if (lifecycle.isCurrent(owner)) _status.value = CodexOAuthStatus.Error(
                    if (error.message == CALLBACK_PORTS_UNAVAILABLE) {
                        context.getString(R.string.codex_oauth_ports_unavailable)
                    } else "OpenAI sign-in failed"
                )
            } finally {
                sessions.clear()
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { server?.stop(0, 1_000) }
                    server = null
                }
            }
        }
    }

    @Synchronized
    fun cancelLogin() {
        lifecycle.cancel()
        _status.value = CodexOAuthStatus.Idle
    }

    fun consumeResult() {
        _status.value = CodexOAuthStatus.Idle
    }

    private fun ensureCallbackServer(result: CompletableDeferred<OAuthCallback>): Int {
        var lastError: Throwable? = null
        for (port in CALLBACK_PORTS) {
            if (!isLoopbackPortAvailable(port)) {
                lastError = java.net.BindException("127.0.0.1:$port is already in use")
                continue
            }
            try {
                server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    routing {
                        get("/auth/callback") {
                            val session = sessions.consume(call.request.queryParameters["state"])
                            val code = call.request.queryParameters["code"]
                            val error = call.request.queryParameters["error"]?.takeIf { it.isNotBlank() }
                                ?: if (code.isNullOrBlank()) "Missing authorization code" else null
                            // An unrelated or replayed request cannot terminate a valid login.
                            if (session != null) result.complete(OAuthCallback(session, code, error))
                            call.respondText(callbackPage(session != null && error == null), ContentType.Text.Html)
                        }
                    }
                }.start(wait = false)
                return port
            } catch (error: Exception) {
                server?.stop(0, 1_000)
                server = null
                lastError = error
            }
        }
        throw IllegalStateException(CALLBACK_PORTS_UNAVAILABLE, lastError)
    }

    private fun isLoopbackPortAvailable(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port))
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun awaitNetworkUnblocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        // Some Android versions do not deliver an initial blocked-status event. Bound this
        // wait; the token request still reports real network errors through OkHttp.
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                        if (!blocked && resumed.compareAndSet(false, true)) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            continuation.resume(Unit)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
                connectivityManager.registerDefaultNetworkCallback(callback)
                if (!continuation.isActive) {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    private suspend fun exchangeCode(code: String, session: CodexOAuthSession): CodexAccount {
        val response = authClient.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", CLIENT_ID)
                        .add("code", code)
                        .add("redirect_uri", session.redirectUri)
                        .add("code_verifier", session.verifier)
                        .build()
                )
                .build()
        ).await()
        val body = try {
            withContext(Dispatchers.IO) { response.body.string() }
        } finally { response.close() }
        currentCoroutineContext().ensureActive()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code}")
        }
        return repository.saveLogin(body)
    }

    private fun callbackPage(success: Boolean): String {
        val status = if (success) "success" else "error"
        val deepLink = "rikkaroot://codex/oauth?status=${URLEncoder.encode(status, Charsets.UTF_8.name())}"
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="0; url=$deepLink">
                <title>Rikka-Root Codex OAuth</title>
              </head>
              <body>
                <p>${if (success) "Returning to Rikka-Root..." else "Sign-in failed."}</p>
                <p><a href="$deepLink">Return to Rikka-Root</a></p>
                <script>
                  window.location.replace("$deepLink");
                  setTimeout(function () { window.location.href = "$deepLink"; }, 500);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "CodexOAuthManager"
        private const val CALLBACK_PORTS_UNAVAILABLE = "OAuth callback ports are unavailable"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val DEFAULT_SCOPES =
            "openid profile email offline_access api.connectors.read api.connectors.invoke"
        private val CALLBACK_PORTS = listOf(1455, 1457)
    }
}

/** Each login owns the shared listener until its non-cancellable cleanup has finished. */
internal class CodexOAuthLoginLifecycle(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    @Volatile private var current: Job? = null

    @Synchronized
    fun start(block: suspend () -> Unit): Job {
        current?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) { mutex.withLock { block() } }
        current = next
        next.start()
        return next
    }

    @Synchronized
    fun cancel() { current?.cancel() }
    fun isCurrent(job: Job?): Boolean = current === job
}

internal const val CODEX_OAUTH_TIMEOUT_MS = 10 * 60 * 1_000L

/** One in-flight authorization, with monotonic expiry and one-time state consumption. */
internal class CodexOAuthSessions(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var pending: CodexOAuthSession? = null

    @Synchronized
    fun start(redirectUri: String): CodexOAuthSession = CodexOAuthSession(
        state = randomUrlSafe(32), verifier = randomUrlSafe(64), redirectUri = redirectUri,
        expiresAt = nowMillis() + CODEX_OAUTH_TIMEOUT_MS,
    ).also { pending = it }

    @Synchronized
    fun consume(state: String?): CodexOAuthSession? {
        val session = pending ?: return null
        if (nowMillis() >= session.expiresAt) {
            pending = null
            return null
        }
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), session.state.toByteArray())) return null
        pending = null
        return session
    }

    @Synchronized
    fun clear() { pending = null }

    private fun randomUrlSafe(size: Int): String = ByteArray(size).let { bytes ->
        SecureRandom().nextBytes(bytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal data class CodexOAuthSession(
    val state: String,
    val verifier: String,
    val redirectUri: String,
    val expiresAt: Long,
) {
    val challenge: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )
}

private data class OAuthCallback(val session: CodexOAuthSession, val code: String?, val error: String?)

sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object Waiting : CodexOAuthStatus
    data class Success(val accountId: String) : CodexOAuthStatus
    data class Error(val message: String) : CodexOAuthStatus
}
