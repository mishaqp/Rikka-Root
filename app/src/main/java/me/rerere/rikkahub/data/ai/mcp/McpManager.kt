package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.runtime.SettingsDashboardRuntimeStatusStore
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.classifyMcpError
import me.rerere.rikkahub.data.ai.mcp.control.guardedMcpHttpClient
import me.rerere.rikkahub.reliability.SecretRedactor
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** Set only by our failure producer; successful MCP text remains untrusted data. */
internal const val MCP_TOOL_FAILURE_METADATA_KEY = "rikkahub.mcp.tool_failure"

private fun mcpFailurePart(payload: JsonObject): UIMessagePart.Text = UIMessagePart.Text(
    payload.toString(), metadata = buildJsonObject { put(MCP_TOOL_FAILURE_METADATA_KEY, true) },
)

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表与 UI 内容转换；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    private val secretStore: McpControlSecretStore,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .followRedirects(false)
        .addInterceptor(sameOriginMcpRedirects)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        // Every endpoint change must pass the request-origin credential check again.
        // Only same-origin redirects (`/mcp` → `/mcp/`) are followed, by the OkHttp interceptor.
        followRedirects = false
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val publicOkHttpClient = guardedMcpHttpClient(okHttpClient, publicAddressOnly = true)
    private val publicHttpClient = HttpClient(OkHttp) {
        followRedirects = false
        engine { preconfigured = publicOkHttpClient }
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; isLenient = true })
        }
        install(SSE)
    }

    private val statusStore = McpStatusStore()
    private val oauthCallbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        publicOAuthClient = OAuthHttpClient(publicOkHttpClient),
        publicDiscoveryClient = McpOAuthDiscoveryClient(publicOkHttpClient),
        callbackServer = oauthCallbackServer,
        authorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
        updateStatus = statusStore::update,
        secretStore = secretStore,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        httpClient = httpClient,
        publicHttpClient = publicHttpClient,
        secretStore = secretStore,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )

    init {
        SettingsDashboardRuntimeStatusStore.shared.publishMcpStatus(statusStore.status)
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val result = try {
            sessionRegistry.callTool(serverId, toolName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            return listOf(mcpFailurePart(buildJsonObject {
                put("error", "mcp_client_unavailable")
                put("detail", SecretRedactor.redact("Не удалось выполнить инструмент MCP. ${e.message}"))
            }))
        } catch (e: Exception) {
            val rawError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            val (error, hint) = classifyMcpError(rawError)
            val startsInRussian = rawError.trimStart().firstOrNull()?.let {
                it in 'А'..'я' || it == 'Ё' || it == 'ё'
            } == true
            val detail = when {
                error == "request_timeout" ->
                    "Сервер MCP не ответил вовремя. Проверьте работу сервера и повторите запрос."
                error == "connect_failed" && !startsInRussian ->
                    "Не удалось выполнить инструмент MCP. Проверьте адрес, порт и доступность сервера, затем повторите вызов."
                else -> hint
            }
            return listOf(mcpFailurePart(buildJsonObject {
                put("error", error)
                put("detail", SecretRedactor.redact(detail))
                put("raw_error", SecretRedactor.redact(rawError))
            }))
        }
        val contentParts = result.content.map { content ->
            when (content) {
                is TextContent -> UIMessagePart.Text(content.text)
                is ImageContent -> convertImageContentToFilePart(content)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(content))
            }
        }
        if (result.isError == true) {
            val rawError = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            val explanation = mcpFailurePart(buildJsonObject {
                put("error", "mcp_tool_error")
                put("detail", "Сервер MCP сообщил об ошибке инструмента. Проверьте параметры вызова и повторите запрос.")
                put("raw_error", SecretRedactor.redact(rawError))
            })
            return listOf(explanation) + contentParts
        }
        return contentParts
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    /** Agent forceResync adaptation: detach resets backoff, then reconnect and sync. */
    suspend fun forceResync(serverId: Uuid) {
        val config = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: return
        sessionRegistry.removeClient(config)
        sessionRegistry.addClient(config)
    }

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$extension",
            mimeType = image.mimeType,
        )
        return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
    }
}

// Adapted from ExTV/rikkahub-agent McpManager.kt (AGPL v3), shared with mcp_list_tools.
// Registration retains the exact remote name in its execute closure, never decodes this name.
fun buildMcpToolName(serverId: Uuid, serverName: String, toolName: String): String {
    val serverSlug = serverId.toString().take(8).replace("-", "")
    val original = "mcp__" + serverSlug + "_" + serverName + "__" + toolName
    // Valid names stay unchanged so saved approvals and scheduled-task tool lists keep matching.
    if (original.length <= MCP_TOOL_NAME_MAX && MCP_TOOL_NAME_VALID.matches(original)) return original
    val identity = "$serverId\u0000$serverName\u0000$toolName"
    val suffix = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    return original.replace(MCP_TOOL_NAME_INVALID_CHAR, "_").take(MCP_TOOL_NAME_MAX - suffix.length - 1) + "_" + suffix
}

private const val MCP_TOOL_NAME_MAX = 64
private val MCP_TOOL_NAME_VALID = Regex("[a-zA-Z0-9_-]+")
private val MCP_TOOL_NAME_INVALID_CHAR = Regex("[^a-zA-Z0-9_-]")

private val MCP_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * Follows a redirect only while scheme, host and port stay the same, so credentials never reach
 * another origin. A cross-origin redirect is returned as-is and needs an explicit URL change.
 */
internal val sameOriginMcpRedirects = Interceptor { chain ->
    var request = chain.request()
    var response = chain.proceed(request)
    var hops = 0
    while (response.code in MCP_REDIRECT_CODES && hops < 5) {
        val target = response.header("Location")?.let(request.url::resolve) ?: break
        if (target.scheme != request.url.scheme || target.host != request.url.host || target.port != request.url.port) break
        val keepMethod = response.code == 307 || response.code == 308 ||
            request.method == "GET" || request.method == "HEAD"
        if (keepMethod && request.body?.isOneShot() == true) break
        val next = request.newBuilder().url(target)
        if (!keepMethod) next.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
        response.close()
        request = next.build()
        response = chain.proceed(request)
        hops++
    }
    response
}
