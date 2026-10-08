// Adapted from ExTV/rikkahub-agent (AGPL v3); API 2.5.6, approvals, secure storage and Russian UI.
package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.ai.mcp.secureMcpConfigForPersistence
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.reliability.SecretRedactor
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The nine LLM-callable mcp_* tools that let an assistant CRUD MCP servers without the
 * user opening Settings. Builders here are pure factories — they capture the manager and
 * settings store, then return a [Tool] whose execute lambda parses args, validates, calls
 * through to the existing infra, and returns a JSON envelope.
 *
 * All side-effecting tools share a common shape:
 *   - validate args (return `{error, detail}` on bad input)
 *   - mutate the settings store (PreferencesStore takes care of persistence + Flow refresh)
 *   - call McpManager methods to keep its in-memory client map in sync
 *   - return the canonical "server view" envelope so the LLM gets the post-state
 *
 * The header redactor + URL guard are shared with the approval-prompt rendering layer so
 * the user sees the same redacted view the tool result returns.
 */
private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15
private const val MAX_CONNECT_TIMEOUT_SECONDS = 60

/** ---------- Shared helpers ---------- */

private fun errEnv(error: String, detail: String, extra: Map<String, JsonElement> = emptyMap()): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", SecretRedactor.redact(detail))
        for ((k, v) in extra) put(k, if (v is kotlinx.serialization.json.JsonPrimitive && v.isString) kotlinx.serialization.json.JsonPrimitive(SecretRedactor.redact(v.content)) else v)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun okEnv(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> {
    return listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))
}

/**
 * Render the canonical server-view envelope used by mcp_add / mcp_update / mcp_test results
 * and as elements of mcp_list. Always redacts headers — the LLM doesn't need plain bytes
 * back since it just typed them. If the server isn't registered with the manager, status
 * defaults to DISABLED (the user can have a config in settings with enable=false).
 */
private fun serverViewEnvelope(
    config: McpServerConfig,
    status: McpStatus?,
    builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    put("id", config.id.toString())
    put("name", config.commonOptions.name)
    put("transport", transportLabel(config))
    put("url", urlOf(config))
    put("enabled", config.commonOptions.enable)
    val (statusLabel, errorMessage) = renderStatus(config.commonOptions.enable, status)
    put("status", statusLabel)
    put("tool_count", config.commonOptions.tools.size)
    if (errorMessage != null) put("error", SecretRedactor.redact(errorMessage))
    putJsonArray("headers") {
        McpHeaderRedactor.redactHeaders(config.commonOptions.headers).forEach { (n, v) ->
            addJsonObject {
                put("name", n)
                put("value", v)
            }
        }
    }
    builder()
}

private fun transportLabel(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http"
}

private fun urlOf(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> config.url
    is McpServerConfig.StreamableHTTPServer -> config.url
}

private fun renderStatus(enabled: Boolean, status: McpStatus?): Pair<String, String?> {
    if (!enabled) return "DISABLED" to null
    return when (status) {
        null, McpStatus.Idle, McpStatus.Connecting -> "CONNECTING" to null
        McpStatus.Connected -> "CONNECTED" to null
        is McpStatus.Reconnecting -> "CONNECTING" to "повторное подключение (${status.attempt}/${status.maxAttempts})"
        is McpStatus.Error -> "ERROR" to status.message
        McpStatus.Authorizing -> "CONNECTING" to "авторизация"
        McpStatus.NeedsAuthorization -> "ERROR" to "требуется авторизация"
    }
}

private fun parseHeaders(raw: JsonElement?): List<Pair<String, String>> {
    val arr = raw?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
    return arr.mapNotNull { entry ->
        val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
        name to value
    }
}

private fun parseUuid(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }

/**
 * #88: whether [serverId] is in the calling assistant's per-assistant MCP allowlist — the
 * same test [me.rerere.rikkahub.data.ai.mcp.McpManager.getAllAvailableTools] applies to decide
 * what actually gets dispatched. Shared by mcp_list / mcp_get / mcp_list_tools so their
 * `enabled_for_assistant` field can't drift from each other. Pure.
 */
internal fun isEnabledForAssistant(serverId: Uuid, assistantMcpServers: Set<Uuid>): Boolean =
    serverId in assistantMcpServers

/**
 * #88: add [newServerId] to the [callingAssistantId] assistant's `mcpServers` set, leaving
 * every other assistant untouched. A model that adds a server via mcp_add is adding it in
 * order to use it, and has no other way to reach the per-assistant picker. Pure.
 */
internal fun addServerToCallingAssistant(
    assistants: List<Assistant>,
    callingAssistantId: Uuid,
    newServerId: Uuid,
): List<Assistant> = assistants.map { a ->
    if (a.id == callingAssistantId) a.copy(mcpServers = a.mcpServers + newServerId) else a
}

/**
 * Render an [InputSchema] as a plain JSON-Schema-shaped object so the LLM can see each
 * MCP tool's argument shape. Returns null when the server didn't supply a schema (older
 * MCP servers, or tools/list responses without inputSchema) — callers omit the field
 * gracefully in that case rather than emitting an empty object.
 */
internal fun inputSchemaJson(schema: InputSchema?): JsonObject? = when (schema) {
    null -> null
    is InputSchema.Obj -> buildJsonObject {
        put("type", "object")
        put("properties", schema.properties)
        schema.required?.let { req ->
            putJsonArray("required") { req.forEach { add(it) } }
        }
    }
}

private fun buildConfig(
    id: Uuid,
    transport: String,
    name: String,
    url: String,
    enabled: Boolean,
    headers: List<Pair<String, String>>,
    existingTools: List<McpTool> = emptyList(),
): McpServerConfig {
    val common = McpCommonOptions(
        enable = enabled,
        name = name,
        headers = headers,
        tools = existingTools,
        publicAddressOnly = true,
    )
    return when (transport) {
        "sse" -> McpServerConfig.SseTransportServer(id = id, commonOptions = common, url = url)
        "streamable_http" -> McpServerConfig.StreamableHTTPServer(id = id, commonOptions = common, url = url)
        else -> error("Неподдерживаемый transport: $transport")
    }
}

/**
 * Wait until the manager reports a terminal status (Connected or Error) for [serverId], or
 * the timeout elapses. Returns the last observed status (which may still be Connecting if
 * the timeout was hit — caller renders that as CONNECTING and the LLM can poll with
 * mcp_test).
 */
private suspend fun awaitTerminal(manager: McpManager, serverId: Uuid, timeoutMs: Long): McpStatus? {
    return withTimeoutOrNull(timeoutMs) {
        while (true) {
            val s = manager.syncingStatus.value[serverId]
            when (s) {
                McpStatus.Connected, is McpStatus.Error -> return@withTimeoutOrNull s
                else -> delay(150)
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    } ?: manager.syncingStatus.value[serverId]
}

/**
 * Classify a raw McpStatus.Error message into a normalized `error_kind` plus a short
 * actionable hint. The MCP SDK and underlying transport (OkHttp / kotlinx-serialization)
 * surface failures as opaque exception messages — without classification the LLM can't
 * tell a network problem from a malformed-tool-def from an auth failure, and tells the
 * user the wrong thing to fix.
 *
 * Returns (error_kind, hint). Both end up in the rollback envelope.
 */
internal fun classifyMcpError(message: String): Pair<String, String> {
    val m = message.lowercase()
    return when {
        // kotlinx-serialization shape: "Field 'X' is required for type with serial name 'io.modelcontextprotocol...'"
        m.contains("field '") && m.contains("is required for type") -> {
            "remote_invalid_tool_def" to
                "Сервер вернул описание инструмента без обязательного поля. " +
                "Исправьте MCP-сервер: нужны name, description и inputSchema."
        }
        m.contains("missingfieldexception") || m.contains("serializationexception") -> {
            "remote_invalid_response" to
                "Ответ сервера не соответствует протоколу MCP. Проверьте реализацию MCP и формат JSON на сервере."
        }
        m.contains("failed to connect to") || m.contains("connectexception") ||
            m.contains("connection refused") -> {
            "connect_failed" to
                "Не удалось установить TCP-соединение. Проверьте публичный адрес, порт и доступность сервера. " +
                "Локальные адреса (0.0.0.0, 127.0.0.1, LAN) для mcp_control запрещены."
        }
        m.contains("sockettimeoutexception") || m.contains("read timed out") ||
            m.contains("connect timed out") -> {
            "request_timeout" to
                "Сервер не ответил до таймаута. Увеличьте connect_timeout_seconds или проверьте работу сервера."
        }
        m.contains("unknownhostexception") || m.contains("no address associated") -> {
            "host_not_found" to
                "DNS не разрешил имя. Проверьте URL и доступность имени с устройства."
        }
        m.contains(" 401") || m.contains("unauthorized") -> {
            "auth_required" to
                "Сервер вернул 401. Проверьте секретный заголовок Authorization / API-key в настройках MCP."
        }
        m.contains(" 403") || m.contains("forbidden") -> {
            "auth_forbidden" to
                "Сервер вернул 403. Учётные данные не дают доступа к этому endpoint."
        }
        m.contains(" 404") || m.contains("not found") -> {
            "endpoint_not_found" to
                "Сервер вернул 404. Проверьте путь к MCP endpoint (обычно /mcp или /sse)."
        }
        else -> "connect_failed" to
            "Сервер не подключился. Проверьте URL, transport и headers, затем повторите."
    }
}

/** ---------- Tool factories ---------- */

fun mcpListTool(settingsStore: SettingsStore, manager: McpManager, caller: McpControlCaller): Tool = Tool(
    name = "mcp_list",
    description = """
        Список всех настроенных серверов MCP, состояние подключения и число инструментов. Только чтение. Используйте перед добавлением сервера или для проверки ранее добавленного. enabled_for_assistant=false означает, что сервер подключён, но его инструменты не включены для текущего чата и вызовы будут недоступны. mcp_add автоматически включает созданный сервер для вызывающего чата. Секретные заголовки скрыты.
    """.trimIndent(),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val settings = settingsStore.settingsFlow.value
        val servers = settings.mcpServers
        val statuses = manager.syncingStatus.value
        // #88: resolved the same way McpManager.getAllAvailableTools() resolves it, so this
        // matches what actually gets dispatched. Not-enabled servers stay in the listing —
        // this is the diagnostic that makes "connected + listed but zero callable tools"
        // visible instead of a silent gap.
        val assistantServerIds = caller.assistantMcpServers()
        val arr = buildJsonArray {
            servers.forEach { config ->
                add(
                    serverViewEnvelope(config, statuses[config.id]) {
                        put("enabled_for_assistant", isEnabledForAssistant(config.id, assistantServerIds))
                    }
                )
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("servers", arr) }.toString()))
    },
)

fun mcpGetTool(settingsStore: SettingsStore, manager: McpManager, caller: McpControlCaller): Tool = Tool(
    name = "mcp_get",
    description = """
        Полная конфигурация одного сервера MCP по id, со скрытыми секретными заголовками и списком доступных инструментов. Только чтение.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "UUID сервера из mcp_list или mcp_add")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
        val settings = settingsStore.settingsFlow.value
        val config = settings.mcpServers.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        val status = manager.syncingStatus.value[serverId]
        // #88: same resolution as mcp_list / McpManager.getAllAvailableTools().
        val enabledForAssistant = isEnabledForAssistant(serverId, caller.assistantMcpServers())
        val toolList = buildJsonArray {
            config.commonOptions.tools.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description ?: "")
                    put("enabled", tool.enable)
                    put("needs_approval", tool.needsApproval)
                }
            }
        }
        listOf(
            UIMessagePart.Text(
                serverViewEnvelope(config, status) {
                    put("enabled_for_assistant", enabledForAssistant)
                    put("tools", toolList)
                }.toString()
            )
        )
    },
)

fun mcpAddTool(settingsStore: SettingsStore, manager: McpManager, caller: McpControlCaller, secretStore: McpControlSecretStore): Tool = Tool(
    name = "mcp_add",
    description = """
        Добавить сервер MCP. transport="sse" или "streamable_http", уникальное name (до 60 символов), публичный http(s) url, optional enabled (по умолчанию true), headers как список {name,value} (до 32). Секретные заголовки передавайте только ссылкой AndroidKeyStore из защищённого поля настроек MCP; не отправляйте токены в чат или URL. Локальные/частные IP, DNS на них и редиректы запрещены. После регистрации ожидает до connect_timeout_seconds (15 по умолчанию, максимум 60); если CONNECTING — используйте mcp_test. Обычно сервер устанавливают на удалённой машине и публикуют endpoint HTTP/SSE, затем вызывают mcp_add. Требуется одобрение изменения конфигурации.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("transport", buildJsonObject {
                    put("type", "string")
                    put("description", "sse или streamable_http. Stdio не поддерживается.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Название для пользователя: только латинские буквы и цифры, уникальное без учёта регистра, до 60 символов.")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Публичный http:// или https:// URL endpoint MCP. Другие схемы, локальные адреса и редиректы запрещены.")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Включить сервер при создании. По умолчанию true.")
                })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("description", "Необязательные заголовки [{name,value},...], до 32. Для секретов используйте ссылки AndroidKeyStore из настроек MCP.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("name"); add("value") })
                    })
                })
                put("connect_timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Секунды ожидания первой синхронизации. По умолчанию 15, максимум 60.")
                })
            },
            required = listOf("transport", "name", "url"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val transport = params["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@Tool errEnv("invalid_transport", "transport обязателен: sse или streamable_http")
        if (transport != "sse" && transport != "streamable_http") {
            return@Tool errEnv(
                "unsupported_transport",
                "Неподдерживаемый transport: $transport. Доступны sse и streamable_http"
            )
        }
        val rawName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val rawUrl = params["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeaders(params["headers"])
        val timeoutSec = (params["connect_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)

        val urlCheck = withContext(Dispatchers.IO) { McpUrlGuard.check(rawUrl, headless = false) }
        if (urlCheck is McpUrlGuard.Result.Reject) {
            return@Tool errEnv(urlCheck.error, urlCheck.detail)
        }
        val existing = settingsStore.settingsFlow.value.mcpServers
        val nameCheck = McpControlValidation.validateName(rawName, existing, excludingId = null)
        if (nameCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(nameCheck.error, nameCheck.detail)
        }
        val headerCheck = McpControlValidation.validateSecureHeaders(headers)
        if (headerCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(headerCheck.error, headerCheck.detail)
        }
        val secretCheck = withContext(Dispatchers.IO) { runCatching {
            headers.filter { McpControlSecretStore.isReference(it.second) }.forEach { secretStore.resolve(it.second) }
        } }
        if (secretCheck.isFailure) return@Tool errEnv("missing_mcp_secret", "Секрет MCP недоступен. Введите его на экране настроек MCP этого устройства.")
        val name = (nameCheck as McpControlValidation.Result.Ok).value
        val newId = Uuid.random()
        val config = buildConfig(
            id = newId,
            transport = transport,
            name = name,
            url = rawUrl.trim(),
            enabled = enabled,
            headers = headers,
        )
        settingsStore.update { old ->
            // #88: without this the server connects and lists tools but is invisible to
            // getAllAvailableTools(), so every call fails NOT_FOUND.
            old.copy(
                mcpServers = old.mcpServers + config,
            )
        }
        caller.addServer(newId)
        if (enabled) {
            manager.addClient(config)
            awaitTerminal(manager, newId, timeoutSec * 1000L)
        }
        val finalStatus = manager.syncingStatus.value[newId]
        // Rollback on permanent failure (Error). Without this the failed config sits in
        // settings; the next mcp_add with the same name hits name_already_in_use and the
        // user has to manually mcp_delete first. Connecting/null timeouts are KEPT
        // because the LLM is told to poll with mcp_test — pulling the row out from
        // under the next poll would be a worse UX than leaving a row marked CONNECTING.
        if (finalStatus is McpStatus.Error) {
            manager.removeClient(config)
            settingsStore.update { s ->
                s.copy(
                    mcpServers = s.mcpServers.filter { it.id != newId },
                    // Undo the calling-assistant enable from above so a rolled-back server
                    // doesn't leave a dangling reference in any assistant's set.
                    assistants = s.assistants.map { a -> a.copy(mcpServers = a.mcpServers - newId) },
                )
            }
            caller.removeServer(newId)
            val (kind, hint) = classifyMcpError(finalStatus.message)
            return@Tool errEnv(
                kind,
                "Не удалось добавить MCP: ${finalStatus.message}. $hint Конфигурация удалена, можно повторить с тем же именем.",
                extra = mapOf(
                    "raw_error" to kotlinx.serialization.json.JsonPrimitive(finalStatus.message),
                    "name" to kotlinx.serialization.json.JsonPrimitive(name),
                    "url" to kotlinx.serialization.json.JsonPrimitive(rawUrl.trim()),
                ),
            )
        }
        // Re-read after sync — sync mutates tool list.
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == newId } ?: config
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpUpdateTool(settingsStore: SettingsStore, manager: McpManager, secretStore: McpControlSecretStore): Tool = Tool(
    name = "mcp_update",
    description = """
        Заменить конфигурацию сервера MCP. Аргументы как у mcp_add плюс id. Старый клиент отключается, новый подключается: изменения transport, URL и headers применяются. Список известных инструментов сохраняется; синхронизация запускается автоматически. Секретные значения — только ссылки AndroidKeyStore из настроек MCP. Публичные адреса, без редиректов. Требуется одобрение изменения конфигурации.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("transport", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
                put("url", buildJsonObject { put("type", "string") })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("name"); add("value") })
                    })
                })
                put("connect_timeout_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("id", "transport", "name", "url"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
        val transport = params["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@Tool errEnv("invalid_transport", "transport обязателен")
        if (transport != "sse" && transport != "streamable_http") {
            return@Tool errEnv("unsupported_transport", "Неподдерживаемый transport: $transport")
        }
        val rawName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val rawUrl = params["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeaders(params["headers"])
        val timeoutSec = (params["connect_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)

        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        val urlCheck = withContext(Dispatchers.IO) { McpUrlGuard.check(rawUrl, headless = false) }
        if (urlCheck is McpUrlGuard.Result.Reject) {
            return@Tool errEnv(urlCheck.error, urlCheck.detail)
        }
        val nameCheck = McpControlValidation.validateName(rawName, all, excludingId = serverId.toString())
        if (nameCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(nameCheck.error, nameCheck.detail)
        }
        val headerCheck = McpControlValidation.validateSecureHeaders(headers)
        if (headerCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(headerCheck.error, headerCheck.detail)
        }
        val secretCheck = withContext(Dispatchers.IO) { runCatching {
            headers.filter { McpControlSecretStore.isReference(it.second) }.forEach { secretStore.resolve(it.second) }
        } }
        if (secretCheck.isFailure) return@Tool errEnv("missing_mcp_secret", "Секрет MCP недоступен. Введите его на экране настроек MCP этого устройства.")
        val name = (nameCheck as McpControlValidation.Result.Ok).value
        val newConfig = buildConfig(
            id = serverId,
            transport = transport,
            name = name,
            url = rawUrl.trim(),
            enabled = enabled,
            headers = headers,
            existingTools = old.commonOptions.tools, // preserve known tools across update
        )
        manager.removeClient(old)
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newConfig else it })
        }
        if (enabled) {
            manager.addClient(newConfig)
            awaitTerminal(manager, serverId, timeoutSec * 1000L)
        }
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: newConfig
        val finalStatus = manager.syncingStatus.value[serverId]
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpDeleteTool(settingsStore: SettingsStore, manager: McpManager, caller: McpControlCaller): Tool = Tool(
    name = "mcp_delete",
    description = """
        Удалить сервер MCP: отключить клиент, отменить повторные подключения и удалить конфигурацию. Ассистенты и текущий чат потеряют доступ к его инструментам. Необратимое изменение, требует одобрения.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", buildJsonObject { put("type", "string") }) },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        manager.removeClient(old)
        settingsStore.update { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != serverId }) }
        // Drop any assistant references to this server so the LLM doesn't keep trying to
        // call its (now-orphan) tools. Mirrors the cleanup done by PreferencesStore.
        settingsStore.update { s ->
            s.copy(
                assistants = s.assistants.map { a ->
                    a.copy(mcpServers = a.mcpServers.filter { it != serverId }.toSet())
                }
            )
        }
        caller.removeServer(serverId)
        okEnv {
            put("deleted", true)
            put("id", serverId.toString())
            put("name", old.commonOptions.name)
        }
    },
)

fun mcpSetEnabledTool(settingsStore: SettingsStore, manager: McpManager, secretStore: McpControlSecretStore): Tool = Tool(
    name = "mcp_set_enabled",
    description = """
        Изменить флаг enabled сервера MCP. При отключении клиент закрывается, при включении подключается заново. Требует одобрения.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("enabled", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("id", "enabled"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_enabled", "enabled обязателен: true или false")
        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        if (enabled) {
            val urlCheck = withContext(Dispatchers.IO) { McpUrlGuard.check(urlOf(old), headless = false) }
            if (urlCheck is McpUrlGuard.Result.Reject) return@Tool errEnv(urlCheck.error, urlCheck.detail)
        }
        if (old.commonOptions.enable == enabled && (!enabled || old.commonOptions.publicAddressOnly)) {
            // No-op: still return the current view so the LLM knows.
            return@Tool listOf(
                UIMessagePart.Text(
                    serverViewEnvelope(old, manager.syncingStatus.value[serverId]).toString()
                )
            )
        }
        val protected = if (enabled) {
            withContext(Dispatchers.IO) { runCatching { secureMcpConfigForPersistence(old, secretStore) } }
                .getOrElse { return@Tool errEnv("missing_mcp_secret", "Не удалось сохранить защищённые заголовки MCP. Введите их в настройках MCP этого устройства.") }
        } else old
        val newConfig = protected.clone(commonOptions = protected.commonOptions.copy(
            enable = enabled, publicAddressOnly = protected.commonOptions.publicAddressOnly || enabled,
        ))
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newConfig else it })
        }
        // Manager's settings-flow collector will pick up the change and add/remove the
        // client. We don't await sync here — the call returns the post-write view.
        listOf(
            UIMessagePart.Text(
                serverViewEnvelope(newConfig, manager.syncingStatus.value[serverId]).toString()
            )
        )
    },
)

fun mcpTestTool(settingsStore: SettingsStore, manager: McpManager, secretStore: McpControlSecretStore): Tool = Tool(
    name = "mcp_test",
    description = """
        Принудительно переподключить сервер MCP, синхронизировать инструменты и вернуть результат. Выполняется сетевое подключение к публичному адресу без редиректов; требуется одобрение. Полезно после mcp_add в состоянии CONNECTING или для проверки работы сервера. Повторное подключение начинается заново.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("wait_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
        val timeoutSec = (params["wait_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)
        val current = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        if (!current.commonOptions.enable) {
            return@Tool errEnv("server_disabled", "Сервер '${current.commonOptions.name}' отключён; сначала включите через mcp_set_enabled")
        }
        val urlCheck = withContext(Dispatchers.IO) { McpUrlGuard.check(urlOf(current), headless = false) }
        if (urlCheck is McpUrlGuard.Result.Reject) return@Tool errEnv(urlCheck.error, urlCheck.detail)
        val protected = withContext(Dispatchers.IO) { runCatching { secureMcpConfigForPersistence(current, secretStore) } }
            .getOrElse { return@Tool errEnv("missing_mcp_secret", "Не удалось сохранить защищённые заголовки MCP. Введите их в настройках MCP этого устройства.") }
        val guarded = protected.clone(commonOptions = protected.commonOptions.copy(publicAddressOnly = true))
        if (guarded != current) settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) guarded else it })
        }
        manager.forceResync(serverId)
        awaitTerminal(manager, serverId, timeoutSec * 1000L)
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: current
        val finalStatus = manager.syncingStatus.value[serverId]
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpListToolsTool(settingsStore: SettingsStore, manager: McpManager, caller: McpControlCaller): Tool = Tool(
    name = "mcp_list_tools",
    description = """
        Список инструментов одного сервера по id или всех включённых серверов, если id отсутствует. Возвращает имя и id сервера, tool_name, dispatchable_name (точное имя для вызова; одного tool_name недостаточно), enabled_for_assistant (false — сервер не включён для текущего чата), необходимость одобрения и input_schema, если сервер её предоставил. Перед вызовом отключённого для чата сервера добавьте его через mcp_add или попросите пользователя включить его в настройках чата.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val settings = settingsStore.settingsFlow.value
        val all = settings.mcpServers
        val targets = if (rawId.isNullOrBlank()) {
            all.filter { it.commonOptions.enable }
        } else {
            val serverId = parseUuid(rawId)
                ?: return@Tool errEnv("invalid_id", "id обязателен и должен быть UUID; получено '$rawId'")
            val one = all.firstOrNull { it.id == serverId }
                ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
            listOf(one)
        }
        // #88: same resolution as mcp_list / McpManager.getAllAvailableTools(). Not-enabled
        // servers stay in the listing (kept above, unchanged) — this only adds the flag so
        // the gap is diagnosable rather than filtering it away.
        val assistantServerIds = caller.assistantMcpServers()
        val arr = buildJsonArray {
            for (server in targets) {
                val enabledForAssistant = isEnabledForAssistant(server.id, assistantServerIds)
                for (tool in server.commonOptions.tools) {
                    addJsonObject {
                        put("server_id", server.id.toString())
                        put("server_name", server.commonOptions.name)
                        put("tool_name", tool.name)
                        // The name to actually call this tool by — built by the exact same
                        // helper the registration path uses, so a model can never end up
                        // with a name that was never offered (#88).
                        put("dispatchable_name", buildMcpToolName(server.id, server.commonOptions.name, tool.name))
                        put("enabled_for_assistant", enabledForAssistant)
                        put("description", tool.description ?: "")
                        put("enabled", tool.enable)
                        put("needs_approval", tool.needsApproval)
                        // Pass the MCP server's per-tool inputSchema through so the LLM
                        // can see each tool's argument shape. Omitted when the server
                        // didn't supply one.
                        inputSchemaJson(tool.inputSchema)?.let { put("input_schema", it) }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("tools", arr) }.toString()))
    },
)

fun mcpSetToolApprovalTool(settingsStore: SettingsStore): Tool = Tool(
    name = "mcp_set_tool_approval",
    description = """
        Установить needsApproval для одного инструмента MCP на заданном сервере. Позволяет пометить конкретный инструмент как требующий одобрения без изменения остальных инструментов. Любое изменение требует одобрения.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("server_id", buildJsonObject { put("type", "string") })
                put("tool_name", buildJsonObject { put("type", "string") })
                put("needs_approval", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("server_id", "tool_name", "needs_approval"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val rawServerId = params["server_id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawServerId)
            ?: return@Tool errEnv("invalid_id", "server_id обязателен и должен быть UUID; получено '$rawServerId'")
        val toolName = params["tool_name"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_tool_name", "tool_name обязателен")
        val needs = params["needs_approval"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_needs_approval", "needs_approval обязателен: true или false")
        val all = settingsStore.settingsFlow.value.mcpServers
        val server = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "Сервер MCP с id $serverId не найден")
        val tool = server.commonOptions.tools.firstOrNull { it.name == toolName }
            ?: return@Tool errEnv(
                "unknown_tool_name",
                "На сервере '${server.commonOptions.name}' нет инструмента '$toolName'"
            )
        if (tool.needsApproval == needs) {
            return@Tool okEnv {
                put("server_id", serverId.toString())
                put("tool_name", toolName)
                put("needs_approval", needs)
                put("changed", false)
            }
        }
        val newServer = server.clone(
            commonOptions = server.commonOptions.copy(
                tools = server.commonOptions.tools.map { t ->
                    if (t.name == toolName) t.copy(needsApproval = needs) else t
                }
            )
        )
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newServer else it })
        }
        okEnv {
            put("server_id", serverId.toString())
            put("tool_name", toolName)
            put("needs_approval", needs)
            put("changed", true)
        }
    },
)
