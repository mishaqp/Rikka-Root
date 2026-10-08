package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.McpUrlGuard
import me.rerere.rikkahub.reliability.SecretRedactor
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "McpOAuthCoordinator"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
internal const val MCP_OAUTH_CALLBACK_PORT = 52_134
internal const val MCP_OAUTH_CALLBACK_PATH = "/oauth/callback"
internal const val MCP_OAUTH_REDIRECT_URI =
    "http://localhost:$MCP_OAUTH_CALLBACK_PORT$MCP_OAUTH_CALLBACK_PATH"
// 托管在 GitHub Pages 上的 Client ID Metadata Document，其 redirect_uris 必须与 MCP_OAUTH_REDIRECT_URI 一致
internal const val MCP_OAUTH_CLIENT_METADATA_URL = "https://rikkahub.github.io/oauth/client.json"
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

/** Plain tokens exist only for an OAuth request; Settings and backups contain opaque refs. */
internal fun McpOAuthState.protectedSecrets(secretStore: McpControlSecretStore): McpOAuthState {
    fun protect(value: String?): String? = value?.let {
        if (it.isBlank() || McpControlSecretStore.isReference(it)) it else secretStore.put(it)
    }
    return copy(clientSecret = protect(clientSecret), accessToken = protect(accessToken), refreshToken = protect(refreshToken))
}

internal fun McpOAuthState.resolvedSecrets(secretStore: McpControlSecretStore): McpOAuthState {
    fun resolve(value: String?): String? = value?.let {
        if (McpControlSecretStore.isReference(it)) secretStore.resolve(it) else it
    }
    return copy(clientSecret = resolve(clientSecret), accessToken = resolve(accessToken), refreshToken = resolve(refreshToken))
}

internal fun secureMcpConfigForPersistence(
    config: McpServerConfig,
    secretStore: McpControlSecretStore,
): McpServerConfig = config.clone(commonOptions = config.commonOptions.copy(
    headers = config.commonOptions.headers.map { (name, value) ->
        name to if (value.isBlank() || McpControlSecretStore.isReference(value)) value else secretStore.put(value)
    },
    oauth = config.commonOptions.oauth?.protectedSecrets(secretStore),
))

/**
 * 负责 MCP OAuth 的授权、令牌刷新与持久化。
 *
 * 连接生命周期由配置流的消费者管理；令牌持久化后，配置变化会自然触发连接替换。
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val oauthClient: OAuthHttpClient,
    private val discoveryClient: McpOAuthDiscoveryClient,
    private val callbackServer: OAuthLoopbackCallbackServer,
    private val authorizationLauncher: OAuthAuthorizationLauncher,
    private val updateStatus: (Uuid, McpStatus) -> Unit,
    private val secretStore: McpControlSecretStore,
    private val publicOAuthClient: OAuthHttpClient,
    private val publicDiscoveryClient: McpOAuthDiscoveryClient,
) {
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val diagnostic = SecretRedactor.redact("Не удалось авторизовать MCP через OAuth (${e.javaClass.simpleName}). Проверьте настройки сервера; если секрет восстановлен из бэкапа, введите его заново в настройках MCP.")
                Log.e(TAG, diagnostic)
                updateStatus(config.id, McpStatus.Error(diagnostic))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization)
    }

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        persistOAuthState(config.id, null)
        return settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
            ?: config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * 按 serverId 串行刷新。获得锁后重新读取配置，避免并发工具调用重复使用同一个 refresh token。
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
                ?: configInput
            val oauth = config.commonOptions.oauth?.resolvedSecrets(secretStore) ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config

            val expired = oauth.expiresAt > 0 &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!oauth.accessToken.isNullOrBlank() && !expired) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                val token = oauthClientFor(config).refreshToken(
                    OAuthHttpClient.RefreshTokenRequest(
                        tokenEndpoint = tokenEndpoint,
                        clientId = clientId,
                        clientSecret = oauth.clientSecret,
                        refreshToken = oauth.refreshToken,
                        resources = listOf(McpOAuthDiscoveryClient.canonicalResource(config.serverUrl)),
                        scope = oauth.scope,
                    )
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                val protected = persistOAuthState(config.id, updated)
                config.clone(commonOptions = config.commonOptions.copy(oauth = protected))
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, SecretRedactor.redact("Не удалось обновить OAuth-токен MCP (${it.javaClass.simpleName})."))
                config
            }
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (config.commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }) {
            return false
        }
        if (looksUnauthorized(error)) return true
        return runCatching { discoveryClientFor(config).discoverProtectedResource(config.serverUrl) }
            .onFailure {
                Log.i(TAG, SecretRedactor.redact("Проверка OAuth MCP завершилась ошибкой (${it.javaClass.simpleName})."))
            }
            .isSuccess
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) = withContext(Dispatchers.IO) {
        val oauthClient = oauthClientFor(config)
        val discoveryClient = discoveryClientFor(config)
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "URL сервера пустой; OAuth-авторизация недоступна." }

        // 部分服务器（如 Zomato）不提供 RFC 9728 元数据，按旧版规范退回到服务器 origin 作为授权服务器
        val protectedResource = runCatching { discoveryClient.discoverProtectedResource(serverUrl) }
            .onFailure { Log.i(TAG, SecretRedactor.redact("Метаданные защищённого ресурса MCP недоступны (${it.javaClass.simpleName}); проверяем адрес сервера.")) }
            .getOrNull()
        val issuer = protectedResource?.authorizationServers?.firstOrNull()
            ?: McpOAuthDiscoveryClient.serverOrigin(serverUrl)
            ?: error("Не удалось определить сервер OAuth-авторизации.")
        val metadata = discoveryClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("В метаданных OAuth отсутствует authorization_endpoint.")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("В метаданных OAuth отсутствует token_endpoint.")
        val scope = config.commonOptions.oauth?.scope
            ?: protectedResource?.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val resource = McpOAuthDiscoveryClient.canonicalResource(serverUrl)
        val callbackSession = callbackServer.openSession(context, state)
        try {
            val redirectUri = callbackSession.redirectUri
            check(redirectUri == MCP_OAUTH_REDIRECT_URI) {
                "Адрес обратного вызова OAuth не совпадает с ожидаемым: $redirectUri"
            }
            val existing = config.commonOptions.oauth?.resolvedSecrets(secretStore)
            val canReuseClient = existing?.redirectUri == redirectUri && !existing.clientId.isNullOrBlank()
            var clientId = existing?.clientId.takeIf { canReuseClient }
            var clientSecret = existing?.clientSecret.takeIf { canReuseClient }
            if (clientId.isNullOrBlank() && metadata.registrationEndpoint == null &&
                metadata.clientIdMetadataDocumentSupported
            ) {
                // 无动态注册端点时，使用 Client ID Metadata Document（URL 即 client_id）
                clientId = MCP_OAUTH_CLIENT_METADATA_URL
                clientSecret = null
            }
            if (clientId.isNullOrBlank()) {
                val registrationEndpoint = metadata.registrationEndpoint
                    ?: error("Сервер OAuth не поддерживает динамическую регистрацию, client_id не задан.")
                val registration = oauthClient.registerClient(
                    registrationEndpoint = registrationEndpoint,
                    request = OAuthHttpClient.ClientRegistrationRequest(
                        clientName = config.commonOptions.name.ifBlank { "RikkaHub" },
                        redirectUris = listOf(redirectUri),
                        scope = scope,
                    ),
                )
                clientId = registration.clientId
                clientSecret = registration.clientSecret
            }

            persistOAuthState(
                config.id,
                (existing ?: McpOAuthState()).copy(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = scope,
                )
            )

            val authorizationUrl = oauthClient.buildAuthorizationUrl(
                OAuthHttpClient.AuthorizationRequest(
                    authorizationEndpoint = authorizationEndpoint,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    pkce = pkce,
                    state = state,
                    scope = scope,
                    resources = listOf(resource),
                )
            )
            if (config.commonOptions.publicAddressOnly) McpUrlGuard.validateTarget(authorizationEndpoint)
            withContext(Dispatchers.Main) {
                authorizationLauncher.launch(context, authorizationUrl)
            }

            val callback = callbackSession.awaitCallback(OAUTH_CALLBACK_TIMEOUT)
                ?: error("Истекло время ожидания OAuth-авторизации.")
            callback.error?.let { error(buildAuthorizationError(it, callback.errorDescription)) }
            val code = callback.code ?: error("OAuth-авторизация не выполнена: сервер не вернул код.")

            val token = oauthClient.exchangeAuthorizationCode(
                OAuthHttpClient.AuthorizationCodeTokenRequest(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = redirectUri,
                    resources = listOf(resource),
                )
            )
            persistOAuthState(
                config.id,
                McpOAuthState(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = token.scope ?: scope,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                )
            )
        } finally {
            withContext(NonCancellable) {
                callbackSession.close()
            }
        }
    }

    private fun buildAuthorizationError(error: String, description: String?): String =
        if (description.isNullOrBlank()) "OAuth-авторизация не выполнена: $error" else "OAuth-авторизация не выполнена: $error ($description)"

    private fun oauthClientFor(config: McpServerConfig): OAuthHttpClient =
        if (config.commonOptions.publicAddressOnly) publicOAuthClient else oauthClient

    private fun discoveryClientFor(config: McpServerConfig): McpOAuthDiscoveryClient =
        if (config.commonOptions.publicAddressOnly) publicDiscoveryClient else discoveryClient

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?): McpOAuthState? {
        val protected = oauth?.protectedSecrets(secretStore)
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = protected))
                }
            )
        }
        return protected
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    private fun looksUnauthorized(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid")
    }
}
