package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import java.net.URI
import java.net.URLDecoder
import kotlin.uuid.Uuid

/** Only public metadata belongs here. Credentials must never be loaded into the catalog. */
data class AssistantManagerOption(val id: String, val name: String, val description: String = "", val enabled: Boolean = true)
data class AssistantManagerCatalog(
    val skills: List<AssistantManagerOption> = emptyList(),
    val workspaces: List<AssistantManagerOption> = emptyList(),
    val localTools: List<AssistantManagerOption> = emptyList(),
)

/** The trusted owner of the invoking conversation is independent of the next-chat selection. */
data class AssistantManagerCaller(
    val currentAssistantId: () -> Uuid?,
    val switchAssistant: suspend (Uuid) -> Unit,
)

/** Persisted records that must retain an assistant owner when the assistant is removed. */
data class AssistantManagerDependencies(
    val conversations: Int = 0,
    val memories: Int = 0,
    val cronJobs: Int = 0,
    val workflows: Int = 0,
)

private const val MANUAL_ONLY = "Это можно изменить только вручную в настройках"
private const val MASK = "••••"
private val assistantJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
private class AssistantManagerFailure(val code: String, override val message: String) : IllegalArgumentException(message)
private fun fail(message: String, code: String = "INVALID_ARGUMENT") : Nothing = throw AssistantManagerFailure(code, message)

fun manageAssistantsNeedsApproval(input: JsonElement): Boolean =
    (input as? JsonObject)?.get("action")?.let { it as? JsonPrimitive }?.content !in
        setOf("list", "get", "list_options")

fun manageAssistantsApprovalDescription(input: JsonElement): String {
    val args = input as? JsonObject ?: return "Управление ассистентами"
    val frozen = args["_approval_preview"] as? JsonObject
    val target = (frozen?.get("assistant_name") as? JsonPrimitive)?.contentOrNull
        ?: (args["assistant_id"] as? JsonPrimitive)?.content.orEmpty()
    val fields = ((args["settings"] as? JsonObject)?.keys.orEmpty() +
        ((args["clear"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()))
        .map { assistantSettingsSchema()[it]?.jsonObject?.get("title")?.jsonPrimitive?.content ?: it }
    val action = when ((args["action"] as? JsonPrimitive)?.content) {
        "create" -> "Создать ассистента"
        "update" -> "Изменить ассистента $target"
        "duplicate" -> "Скопировать ассистента $target"
        "switch" -> "Выбрать ассистента $target для следующих чатов. Текущий чат и его конфигурация сохранятся"
        "delete" -> "Удалить ассистента $target. Удаление возможно только без связанных чатов, памяти, заданий и сценариев"
        else -> "Управление ассистентами"
    }
    val description = if (fields.isEmpty()) action else "$action. Поля: ${fields.joinToString()}"
    if (frozen != null && frozen["before"] == args["_expected_before"]) {
        val before = safeAssistantPatchPreview(frozen["before"] as? JsonObject ?: JsonObject(emptyMap()))
        val after = safeAssistantPatchPreview(frozen["after"] as? JsonObject ?: JsonObject(emptyMap()))
        val diff = after.entries.joinToString("\n") { (key, value) ->
            val title = assistantSettingsSchema()[key]?.jsonObject?.get("title")?.jsonPrimitive?.content ?: key
            "$title: ${before[key] ?: JsonNull} → $value"
        }
        return if (diff.isEmpty()) description else "$description\n$diff"
    }
    val preview = buildJsonObject {
        (args["settings"] as? JsonObject)?.let { put("settings", safeAssistantPatchPreview(it)) }
        args["clear"]?.let { put("clear", it) }
    }
    return if (preview.isEmpty()) description else "$description\n${preview}"
}

/** Called only by the runtime approval hook; model-provided snapshots are discarded. */
internal fun prepareAssistantManagementApproval(input: JsonElement, settings: Settings): JsonElement {
    val args = input as? JsonObject ?: return input
    val clean = JsonObject(args - setOf("_expected_before", "_approval_preview"))
    val action = clean.string("action")
    if (action !in setOf("create", "update", "duplicate", "switch", "delete")) return clean
    val assistant = settings.assistants.singleOrNull { it.id.toString() == clean.string("assistant_id") }
    val before = if (assistant != null) assistantApprovalBefore(assistant, clean) else JsonObject(emptyMap())
    val after = safeAssistantPatchPreview(JsonObject(
        ((clean["settings"] as? JsonObject).orEmpty()) +
            ((clean["clear"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty().associateWith { JsonNull })
    ))
    return JsonObject(clean + mapOf(
        "_expected_before" to before,
        "_approval_preview" to buildJsonObject {
            put("assistant_name", redactText(JsonPrimitive(assistant?.name ?: "Новый ассистент")))
            put("before", before)
            put("after", after)
        },
    ))
}

private fun assistantApprovalBefore(assistant: Assistant, args: JsonObject): JsonObject {
    val safe = safeAssistantPatchPreview(assistantSettingsJson(assistant))
    val fields = if (args.string("action") == "update") {
        (args["settings"] as? JsonObject)?.keys.orEmpty() +
            (args["clear"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    } else safe.keys
    return JsonObject(safe.filterKeys { it in fields })
}

fun manageAssistantsTool(
    settingsStore: SettingsStore,
    catalog: suspend () -> AssistantManagerCatalog,
    caller: AssistantManagerCaller,
    dependencies: (suspend (Uuid) -> AssistantManagerDependencies)? = null,
    ownershipGuard: AssistantOwnershipGuard? = null,
    beforeDelete: (suspend (Uuid) -> Unit)? = null,
): Tool {
    val engine = AssistantManagerEngine({ settingsStore.settingsFlow.value }, { settingsStore.update(it) }, catalog, caller, dependencies, ownershipGuard, beforeDelete)
    return Tool(
        name = "manage_assistants",
        description = "Управляет ассистентами приложения: list — краткий список, get — все настройки, list_options — реальные идентификаторы и схемы полей, create/update/duplicate/switch/delete — изменения с обязательным одобрением пользователя. Перед настройкой вызовите list_options и get. Изменения ассистента применяются к следующим сообщениям с учётом закреплённой ConversationConfig текущего чата. switch выбирает ассистента для следующих чатов и сохраняет текущий чат. Нельзя удалять владельца текущего чата, последнего ассистента или ассистента с чатами, памятью, неудалёнными заданиями либо ссылками из сценариев. Сначала перенесите или удалите связанные данные; история уже удалённых заданий очищается вместе с ассистентом. Секреты и правила безопасности доступны только вручную в настройках.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { assistantManagerActions.forEach { add(it) } }) })
            put("assistant_id", buildJsonObject { put("type", "string"); put("description", "UUID из list; обязателен для get/update/duplicate/switch/delete.") })
            put("settings", buildJsonObject { put("type", "object"); put("additionalProperties", false); put("properties", assistantSettingsSchema()) })
            put("clear", buildJsonObject { put("type", "array"); put("description", "Сброс перечисленных необязательных полей в null.")
                put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { clearableAssistantFields.forEach { add(it) } }) }) })
        }, required = listOf("action")) },
        needsApproval = ::manageAssistantsNeedsApproval,
        execute = { listOf(UIMessagePart.Text(engine.execute(it).toString())) },
    )
}

private val clearableAssistantFields = setOf("chatModelId", "temperature", "topP", "maxTokens", "tokenBudgetSoftCap", "tokenBudgetHardCap", "workspaceId", "background")

/** The transform runs inside SettingsStore.update's mutex, against its latest persisted Settings. */
internal class AssistantManagerEngine(
    private val readSettings: () -> Settings,
    private val updateSettings: suspend ((Settings) -> Settings) -> Settings,
    private val catalog: suspend () -> AssistantManagerCatalog,
    private val caller: AssistantManagerCaller,
    private val dependencies: (suspend (Uuid) -> AssistantManagerDependencies)? = null,
    private val ownershipGuard: AssistantOwnershipGuard? = null,
    private val beforeDelete: (suspend (Uuid) -> Unit)? = null,
) {
    suspend fun execute(input: JsonElement): JsonObject = try {
        val args = input as? JsonObject ?: fail("Аргументы должны быть объектом JSON.")
        val unknown = args.keys - setOf("action", "assistant_id", "settings", "clear", "_expected_before", "_approval_preview")
        if (unknown.any(::securitySetting)) fail(MANUAL_ONLY, "MANUAL_ONLY")
        if (unknown.isNotEmpty()) fail("Неизвестные аргументы: ${unknown.joinToString()}.")
        val action = args.string("action") ?: fail("Укажите action. Допустимые действия: ${assistantManagerActions.joinToString()}.")
        if (action !in assistantManagerActions) fail("Неизвестное действие. Допустимые действия: ${assistantManagerActions.joinToString()}.")
        if (action !in setOf("create", "update", "duplicate") && ("settings" in args || "clear" in args)) {
            if ((args["settings"] as? JsonObject)?.let(::containsProtectedAssistantInputs) == true ||
                (args["clear"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content?.let(::securitySetting) == true } == true) {
                fail(MANUAL_ONLY, "MANUAL_ONLY")
            }
            fail("Аргументы settings и clear доступны только для create, update и duplicate.")
        }
        if (action in setOf("create", "list", "list_options") && "assistant_id" in args) {
            fail("Для действия $action аргумент assistant_id не используется.")
        }
        when (action) {
            "list" -> success { put("assistants", buildJsonArray { readSettings().assistants.forEach { add(shortAssistant(it)) } }) }
            "get" -> {
                val settings = readSettings()
                val assistant = target(args, settings)
                val external = catalog()
                success { put("assistant", assistantSettingsJson(assistant)); put("fields", assistantFieldDescriptions()); put("references", references(assistant, settings, external)) }
            }
            "list_options" -> options(readSettings(), catalog())
            "switch" -> {
                val assistant = target(args, readSettings())
                try {
                    caller.switchAssistant(assistant.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A target removed between approval and the callback reports the fresh choices.
                    target(args, readSettings())
                    throw error
                }
                success { put("assistant", shortAssistant(assistant)); put("message", "Ассистент выбран для следующих чатов. Текущий чат и его конфигурация сохранены.") }
            }
            else -> mutate(action, args)
        }
    } catch (error: AssistantManagerFailure) {
        buildJsonObject { put("error", error.code); put("message", error.message) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IllegalArgumentException) {
        buildJsonObject { put("error", "INVALID_ARGUMENT"); put("message", "Некорректные данные. Проверьте типы и допустимые значения в list_options.") }
    } catch (_: Exception) {
        buildJsonObject { put("error", "OPERATION_FAILED"); put("message", "Не удалось сохранить изменение. Обновите список ассистентов и повторите действие.") }
    }

    private suspend fun mutate(action: String, args: JsonObject): JsonObject {
        return if (action == "delete" && ownershipGuard != null) {
            ownershipGuard.withDeletion { mutateWithOwnershipHeld(action, args) }
        } else mutateWithOwnershipHeld(action, args)
    }

    private suspend fun mutateWithOwnershipHeld(action: String, args: JsonObject): JsonObject {
        val external = catalog()
        val patch = args["settings"]?.let { it as? JsonObject ?: fail("settings должен быть объектом JSON.") } ?: JsonObject(emptyMap())
        val clear = args["clear"]?.let { it as? JsonArray ?: fail("clear должен быть массивом строк.") }?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail("clear должен быть массивом строк.")
        }.orEmpty()
        val invalidClear = clear - clearableAssistantFields
        if (invalidClear.any(::securitySetting)) fail(MANUAL_ONLY, "MANUAL_ONLY")
        if (invalidClear.isNotEmpty()) fail("Нельзя сбросить поля ${invalidClear.joinToString()}. Допустимые поля: ${clearableAssistantFields.joinToString()}.")
        if (clear.any { it in patch }) fail("Поле нельзя одновременно передать в settings и clear.")
        val changes = JsonObject(patch + clear.associateWith { JsonNull })
        if (action == "delete") {
            val current = readSettings()
            val base = target(args, current)
            verifyDeletable(base, current)
            val check = dependencies ?: fail("Не удалось проверить связанные данные ассистента. Удаление отменено; повторите после загрузки данных.", "DEPENDENCIES_UNAVAILABLE")
            val counts = check(base.id)
            val values = listOf(counts.conversations, counts.memories, counts.cronJobs, counts.workflows)
            if (values.any { it < 0 }) fail("Не удалось проверить связанные данные ассистента. Удаление отменено.", "DEPENDENCIES_UNAVAILABLE")
            if (values.any { it > 0 }) fail("Нельзя удалить ассистента со связанными данными: чаты — ${counts.conversations}, память — ${counts.memories}, задания — ${counts.cronJobs}, сценарии — ${counts.workflows}. Сначала перенесите или удалите их вручную.", "ASSISTANT_HAS_DEPENDENCIES")
            beforeDelete?.invoke(base.id)
        }
        var affected: Assistant? = null
        val committed = updateSettings { settings ->
            if (settings.init) fail("Настройки ещё загружаются. Повторите действие после загрузки.", "SETTINGS_UNAVAILABLE")
            val base = if (action == "create") Assistant() else target(args, settings)
            if (action == "delete") {
                verifyDeletable(base, settings)
                affected = base
                val remaining = settings.assistants.filterNot { it.id == base.id }
                settings.copy(assistants = remaining, assistantId = if (settings.assistantId == base.id) remaining.first().id else settings.assistantId)
            } else {
                if (action == "duplicate" && assistantHasSecrets(base)) fail(MANUAL_ONLY, "MANUAL_ONLY")
                val source = if (action == "duplicate") base.copy(id = Uuid.random(), name = "${base.name} (копия)") else base
                val updated = applyAssistantPatch(source, changes, settings, external)
                if (action == "update" || action == "duplicate") {
                    val expected = args["_expected_before"] as? JsonObject
                        ?: fail("Изменение не подготовлено. Запросите новое подтверждение пользователя.", "APPROVAL_REQUIRED")
                    if (expected != assistantApprovalBefore(base, args)) {
                        fail("Настройки ассистента изменились после подтверждения. Обновите данные и запросите подтверждение заново.", "STALE_APPROVAL")
                    }
                }
                if (action == "create" && updated.name.isBlank()) fail("Для создания ассистента укажите непустое name.")
                affected = updated
                settings.copy(assistants = if (action == "update") settings.assistants.map { if (it.id == updated.id) updated else it }
                    else settings.assistants + updated)
            }
        }
        val assistant = requireNotNull(affected)
        return success {
            if (action == "delete") put("deleted_id", assistant.id.toString())
            else { put("assistant", assistantSettingsJson(committed.assistants.single { it.id == assistant.id })); put("fields", assistantFieldDescriptions()) }
            put("message", when (action) { "create" -> "Ассистент создан."; "update" -> "Настройки ассистента сохранены."; "duplicate" -> "Копия ассистента создана."; else -> "Ассистент удалён. История удалённых заданий очищена." })
        }
    }

    private fun verifyDeletable(base: Assistant, settings: Settings) {
        val owner = caller.currentAssistantId()
            ?: fail("Не удалось определить ассистента текущего чата. Откройте чат заново.", "PROTECTED_ASSISTANT")
        if (base.id == owner) fail("Нельзя удалить ассистента текущего чата.", "PROTECTED_ASSISTANT")
        if (settings.assistants.size <= 1) fail("Нельзя удалить последнего ассистента.", "PROTECTED_ASSISTANT")
        if (base.id in DEFAULT_ASSISTANTS_IDS) fail("Встроенного ассистента нельзя удалить.", "PROTECTED_ASSISTANT")
        if (assistantHasSecrets(base)) fail(MANUAL_ONLY, "MANUAL_ONLY")
    }
}

private fun target(args: JsonObject, settings: Settings): Assistant {
    val id = args.string("assistant_id")
    return settings.assistants.singleOrNull { it.id.toString() == id }
        ?: fail("Ассистент не найден. Допустимые идентификаторы: ${settings.assistants.joinToString { "${it.id} (${redactText(JsonPrimitive(it.name)).jsonPrimitive.content})" }.ifEmpty { "нет" }}.", "NOT_FOUND")
}
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun success(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("success", true); block() }
private fun shortAssistant(assistant: Assistant): JsonObject = buildJsonObject {
    put("id", assistant.id.toString()); put("name", redactText(JsonPrimitive(assistant.name))); put("chatModelId", assistant.chatModelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
}

/** Model-facing transport only. Never use this redacted object as an update baseline. */
internal fun assistantSettingsJson(assistant: Assistant): JsonObject =
    JsonObject(assistantPatchBaseline(assistant).mapValues { (key, value) ->
        // Specialized wrappers have structural keys such as customBodies[].key; keep their restorers.
        if (key in specializedAssistantSecretFields) value else redactText(value)
    })

/** Private assembly preserves generic text; specialized credentials stay masked until restoration. */
private fun assistantPatchBaseline(assistant: Assistant): JsonObject {
    val safe = assistant.copy(
        customHeaders = assistant.customHeaders.map { if (secretHeaderName(it.name) || credentialValue(it.value)) it.copy(value = MASK) else it },
        customBodies = assistant.customBodies.map { if (secretName(it.key)) it.copy(value = JsonPrimitive(MASK)) else it.copy(value = redact(it.value)) },
        avatar = when (val avatar = assistant.avatar) {
            is Avatar.Image -> if (credentialValue(avatar.url)) Avatar.Image(MASK) else avatar
            else -> avatar
        },
        background = assistant.background?.let { if (credentialValue(it)) MASK else it },
    )
    return JsonObject(assistantJson.encodeToJsonElement(safe).jsonObject + mapOf(
        "avatar" to when (val avatar = safe.avatar) {
            Avatar.Dummy -> buildJsonObject { put("type", "dummy") }
            is Avatar.Emoji -> buildJsonObject { put("type", "emoji"); put("content", avatar.content) }
            is Avatar.Image -> buildJsonObject { put("type", "image"); put("url", avatar.url) }
        },
        "localTools" to buildJsonArray { assistant.localTools.forEach { add(localToolId(it)) } },
        "presetMessages" to buildJsonArray { assistant.presetMessages.forEach { message -> add(buildJsonObject {
            put("id", message.id.toString()); put("role", message.role.name); put("content", message.toText())
        }) } },
    ))
}

private val specializedAssistantSecretFields = setOf("customHeaders", "customBodies", "avatar", "background")

private fun protectedAssistantTextFields(assistant: Assistant, baseline: JsonObject): Set<String> = buildSet {
    baseline.forEach { (key, value) ->
        if (key !in specializedAssistantSecretFields && containsTextSecrets(value)) add(key)
    }
    // The tool's canonical text view omits native non-text parts. They must not be copied or lost.
    if (containsTextSecrets(assistantJson.encodeToJsonElement(assistant.presetMessages))) add("presetMessages")
}

private fun applyAssistantPatch(base: Assistant, inputPatch: JsonObject, settings: Settings, external: AssistantManagerCatalog): Assistant {
    val schemas = assistantSettingsSchema()
    val baseline = assistantPatchBaseline(base)
    val protectedTextFields = protectedAssistantTextFields(base, baseline)
    for ((key, value) in inputPatch) {
        val schema = schemas[key] as? JsonObject ?: if (securitySetting(key)) fail(MANUAL_ONLY, "MANUAL_ONLY") else fail("Неизвестное поле $key. Допустимые поля: ${schemas.keys.joinToString()}.")
        // Existing native values need not fit the tool's narrower creation schema (e.g. SYSTEM presets).
        if (key in protectedTextFields && value == redactText(baseline.getValue(key))) continue
        validateValue(value, schema, key)
    }
    val patch = JsonObject(inputPatch.mapValues { (key, value) ->
        when {
            key in specializedAssistantSecretFields -> value // Existing per-field restorers below.
            key in protectedTextFields -> {
                val original = baseline.getValue(key)
                if (value != redactText(original)) fail(MANUAL_ONLY, "MANUAL_ONLY")
                original
            }
            containsTextSecrets(value) -> fail(MANUAL_ONLY, "MANUAL_ONLY")
            else -> value
        }
    })
    fun checkId(field: String, choices: List<Pair<String, String>>, multiple: Boolean = true) {
        val value = patch[field] ?: return
        if (value == JsonNull) return
        val provided = if (multiple) value.jsonArray.map { it.jsonPrimitive.content } else listOf(value.jsonPrimitive.content)
        fun safeChoiceValue(value: String): String = redactCatalogValues(JsonPrimitive(value)).jsonPrimitive.content
        if (provided.any { id -> choices.none { it.first == id } }) fail("Неизвестный идентификатор в $field. Допустимые значения: ${choices.joinToString { "${safeChoiceValue(it.first)} (${safeChoiceValue(it.second)})" }.ifEmpty { "нет" }}.", "INVALID_ID")
    }
    checkId("chatModelId", settings.providers.flatMap { p -> p.models.map { it.id.toString() to "${p.name}: ${it.displayName}" } }, false)
    checkId("workspaceId", external.workspaces.map { it.id to it.name }, false)
    checkId("enabledSkills", external.skills.map { it.id to it.name })
    checkId("tags", settings.assistantTags.map { it.id.toString() to it.name })
    checkId("quickMessageIds", settings.quickMessages.map { it.id.toString() to it.title })
    checkId("mcpServers", settings.mcpServers.map { it.id.toString() to it.commonOptions.name })
    checkId("modeInjectionIds", settings.modeInjections.map { it.id.toString() to it.name })
    checkId("lorebookIds", settings.lorebooks.map { it.id.toString() to it.name })
    checkId("localTools", allAssistantLocalTools().map { localToolId(it).let { id -> id to localToolMetadata(id, external).name } })
    val merged = (baseline + patch).toMutableMap()
    patch["name"]?.jsonPrimitive?.content?.let { if (it.isBlank()) fail("Имя ассистента не может быть пустым.") }
    val avatar = merged.getValue("avatar").jsonObject
    val requestedAvatar: Avatar = when (avatar.string("type")) {
        "dummy" -> Avatar.Dummy
        "emoji" -> Avatar.Emoji(avatar.string("content") ?: fail("Для аватара emoji укажите content."))
        "image" -> Avatar.Image(avatar.string("url") ?: fail("Для аватара image укажите url."))
        else -> fail("Допустимые типы аватара: dummy, emoji, image.")
    }
    val protectedAvatar = (base.avatar as? Avatar.Image)?.let { credentialValue(it.url) } == true
    val nativeAvatar = if (protectedAvatar) {
        if (requestedAvatar != Avatar.Image(MASK)) fail(MANUAL_ONLY, "MANUAL_ONLY")
        base.avatar
    } else {
        if ((requestedAvatar as? Avatar.Image)?.let { credentialValue(it.url) } == true) fail(MANUAL_ONLY, "MANUAL_ONLY")
        requestedAvatar
    }
    // Encode the mask only; the original protected object is restored below.
    merged["avatar"] = assistantJson.encodeToJsonElement(Avatar.serializer(), if (protectedAvatar) Avatar.Image(MASK) else nativeAvatar)
    val protectedBackground = base.background?.let(::credentialValue) == true
    if (protectedBackground && merged["background"] != JsonPrimitive(MASK)) fail(MANUAL_ONLY, "MANUAL_ONLY")
    if (!protectedBackground && merged["background"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content?.let(::credentialValue) == true) fail(MANUAL_ONLY, "MANUAL_ONLY")
    merged["localTools"] = buildJsonArray { merged.getValue("localTools").jsonArray.forEach { value ->
        val id = value.jsonPrimitive.content
        val option = allAssistantLocalTools().singleOrNull { localToolId(it) == id } ?: fail("Неизвестный локальный инструмент: $id.")
        add(assistantJson.encodeToJsonElement(LocalToolOption.serializer(), option))
    } }
    val patchMessages = "presetMessages" in patch && "presetMessages" !in protectedTextFields
    val messages = if (patchMessages) patch.getValue("presetMessages").jsonArray.map { element ->
        val message = element.jsonObject
        val role = when (message.string("role")) { "USER" -> MessageRole.USER; "ASSISTANT" -> MessageRole.ASSISTANT; else -> fail("Роль предустановленного сообщения: USER или ASSISTANT.") }
        val content = message.string("content") ?: fail("Предустановленному сообщению нужен content.")
        UIMessage(id = message.string("id")?.let { parseId(it, "presetMessages.id") } ?: Uuid.random(), role = role, parts = listOf(UIMessagePart.Text(content)))
    } else base.presetMessages
    if (patchMessages && messages.map { it.id }.distinct().size != messages.size) {
        fail("Идентификаторы предустановленных сообщений должны быть уникальными в presetMessages.")
    }
    merged["presetMessages"] = assistantJson.encodeToJsonElement(messages)
    val patchRegexes = "regexes" in patch && "regexes" !in protectedTextFields
    merged["regexes"] = if (patchRegexes) buildJsonArray { patch.getValue("regexes").jsonArray.forEach { element ->
        val rule = element.jsonObject
        rule.string("findRegex")?.let { if (runCatching { Regex(it) }.isFailure) fail("Некорректное регулярное выражение в regexes.findRegex.") }
        rule.string("id")?.let { parseId(it, "regexes.id") }
        add(JsonObject(if ("id" in rule) rule else rule + ("id" to JsonPrimitive(Uuid.random().toString()))))
    } } else assistantJson.encodeToJsonElement(base.regexes)
    if (patchRegexes) {
        val ids = merged.getValue("regexes").jsonArray.map { parseId(it.jsonObject.getValue("id").jsonPrimitive.content, "regexes.id") }
        if (ids.distinct().size != ids.size) fail("Идентификаторы правил замены должны быть уникальными в regexes.")
    }
    val headers = patch["customHeaders"]?.jsonArray?.let { restoreHeaders(it, base.customHeaders) } ?: base.customHeaders
    val bodies = patch["customBodies"]?.jsonArray?.let { restoreBodies(it, base.customBodies) } ?: base.customBodies
    // Specialized credentials are restored after decoding; generic raw fields never leave this assembler.
    val decoded = assistantJson.decodeFromJsonElement(Assistant.serializer(), JsonObject(merged))
    return decoded.copy(customHeaders = headers, customBodies = bodies, avatar = nativeAvatar,
        background = if (protectedBackground) base.background else decoded.background,
        presetMessages = messages, regexes = if (patchRegexes) decoded.regexes else base.regexes)
}

private fun parseId(value: String, field: String): Uuid = runCatching { Uuid.parse(value) }.getOrElse { fail("$field должен быть UUID.") }

private fun validateValue(value: JsonElement, schema: JsonObject, path: String) {
    val typeSpec = schema["type"] ?: return
    val types = if (typeSpec is JsonArray) typeSpec.map { it.jsonPrimitive.content } else listOf(typeSpec.jsonPrimitive.content)
    val valid = when {
        value == JsonNull -> "null" in types
        value is JsonObject -> "object" in types
        value is JsonArray -> "array" in types
        value is JsonPrimitive && value.isString -> "string" in types
        value is JsonPrimitive && value.booleanOrNull != null -> "boolean" in types
        value is JsonPrimitive && value.doubleOrNull?.isFinite() == true -> "number" in types || ("integer" in types && value.intOrNull != null)
        else -> false
    }
    if (!valid) fail("Поле $path: требуется ${types.joinToString(" или ")}.")
    if (value == JsonNull) return
    (schema["enum"] as? JsonArray)?.let { if (value !in it) fail("Поле $path. Допустимые значения: ${it.joinToString()}.") }
    if (value is JsonPrimitive && !value.isString) {
        value.doubleOrNull?.let { number ->
            val min = schema["minimum"]?.jsonPrimitive?.doubleOrNull
            val max = schema["maximum"]?.jsonPrimitive?.doubleOrNull
            if ((min != null && number < min) || (max != null && number > max)) fail("Поле $path: допустимый диапазон ${min ?: "−∞"}…${max ?: "+∞"}.")
        }
    }
    if (value is JsonArray) (schema["items"] as? JsonObject)?.let { item -> value.forEachIndexed { index, element -> validateValue(element, item, "$path[$index]") } }
    if (value is JsonObject) {
        val properties = schema["properties"] as? JsonObject ?: return
        (schema["required"] as? JsonArray)?.forEach { if (it.jsonPrimitive.content !in value) fail("Отсутствует поле $path.${it.jsonPrimitive.content}.") }
        value.forEach { (key, item) ->
            val property = properties[key] as? JsonObject
            if (property != null) validateValue(item, property, "$path.$key")
            else if (securitySetting(key)) fail(MANUAL_ONLY, "MANUAL_ONLY")
            else if (schema["additionalProperties"] == JsonPrimitive(false)) fail("Неизвестное поле $path.$key.")
        }
    }
}

private fun secretName(name: String): Boolean {
    val key = name.lowercase().filter(Char::isLetterOrDigit)
    return key in setOf("auth", "pwd", "key", "sig", "signature", "accesskey") || key.endsWith("token") ||
        listOf("authorization", "authentication", "apikey", "secret", "password", "passwd", "credential", "cookie", "accesstoken", "refreshtoken", "authtoken", "privatekey", "bearer", "clientkey", "signature").any { it in key }
}
private fun secretHeaderName(name: String): Boolean = secretName(name) ||
    name.lowercase().filter(Char::isLetterOrDigit).let { "token" in it || "auth" in it || it.endsWith("key") }

private fun credentialValue(value: String): Boolean = credentialValue(value, ordinaryText = false)
private fun textCredentialValue(value: String): Boolean = credentialValue(value, ordinaryText = true)

private fun credentialValue(value: String, ordinaryText: Boolean): Boolean {
    val normalized = value.trim()
    if (SensitiveContentDetector.scan(normalized).any {
        it == SensitiveContentDetector.Category.API_KEY || it == SensitiveContentDetector.Category.JWT
    }) return true
    // Ordinary assistant prose is not an HTTP header: "Basic helper" is an editable name.
    // Keep the stricter scheme rule for credential-capable headers/bodies and image URLs.
    if (!ordinaryText && Regex("""^(Bearer|Basic)\s+\S+""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return true
    if (ordinaryText) {
        val bearer = Regex("""(?<![A-Za-z0-9_])Bearer\s+([A-Za-z0-9._~+/-]{12,}=*)""", RegexOption.IGNORE_CASE)
        if (bearer.findAll(normalized).any { match ->
                val token = match.groupValues[1]
                token.length >= 20 || token.any { !it.isLetter() }
            }) return true
    }
    // Ordinary text needs a parsed absolute hierarchical URI; file:/// has no host.
    val uri = runCatching { URI(normalized) }.getOrNull()
    if (ordinaryText) {
        if (uri == null || !uri.isAbsolute || uri.isOpaque) return false
    } else if (uri?.isAbsolute != true &&
        !Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""").containsMatchIn(normalized)) return false
    // Android/HTTP clients may accept spaces or repair malformed escapes rejected by URI.
    // Detect the credential-bearing components before relying on that strict parser.
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*@").containsMatchIn(normalized)) return true
    if (uri?.rawUserInfo != null) return true
    val components = listOfNotNull(uri?.rawQuery,
        normalized.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() },
        normalized.substringAfter('#', "").takeIf { it.isNotEmpty() })
    return components.any { component -> component.split('&').any { part ->
        val key = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrDefault(part.substringBefore('='))
        secretName(key)
    } }
}
private fun assistantHasSecrets(assistant: Assistant): Boolean =
    assistant.customHeaders.any { secretHeaderName(it.name) || credentialValue(it.value) } ||
        assistant.customBodies.any { secretName(it.key) || containsSecrets(it.value) } ||
        (assistant.avatar as? Avatar.Image)?.let { credentialValue(it.url) } == true ||
        assistant.background?.let(::credentialValue) == true ||
        protectedAssistantTextFields(assistant, assistantPatchBaseline(assistant)).isNotEmpty()

private fun safeAssistantPatchPreview(patch: JsonObject): JsonObject = JsonObject(patch.mapValues { (key, value) ->
    when (key) {
        "customHeaders", "customBodies" -> (value as? JsonArray)?.let { values -> JsonArray(values.map { row ->
            val obj = row as? JsonObject ?: return@map redact(row)
            val property = obj.string(if (key == "customHeaders") "name" else "key")
            if (property != null && (if (key == "customHeaders") secretHeaderName(property) else secretName(property))) JsonObject(obj + ("value" to JsonPrimitive(MASK))) else redact(obj)
        }) } ?: redact(value)
        else -> when {
            securitySetting(key) && key !in assistantSettingsSchema() -> JsonPrimitive(MASK)
            key in specializedAssistantSecretFields -> redact(value)
            else -> redactText(value)
        }
    }
})

private fun containsProtectedAssistantInputs(patch: JsonObject): Boolean = patch.any { (key, value) ->
    if (key !in assistantSettingsSchema() && securitySetting(key)) true else when (key) {
        "customHeaders", "customBodies" -> (value as? JsonArray)?.any { row ->
            val obj = row as? JsonObject
            val name = obj?.string(if (key == "customHeaders") "name" else "key")
            (name != null && if (key == "customHeaders") secretHeaderName(name) else secretName(name)) ||
                obj?.get("value")?.let(::containsSecrets) == true
        } == true
        else -> if (key in specializedAssistantSecretFields) containsSecrets(value) else containsTextSecrets(value)
    }
}
private fun securitySetting(name: String): Boolean {
    val key = name.lowercase().filter(Char::isLetterOrDigit)
    return secretName(name) || listOf("approval", "permission", "security", "hardline", "trusted", "allowlist", "denylist", "safemode", "rootaccess", "toolaccess").any { it in key }
}
private fun redact(value: JsonElement): JsonElement = redact(value, ordinaryText = false)
private fun redactText(value: JsonElement): JsonElement = redact(value, ordinaryText = true)
private fun redact(value: JsonElement, ordinaryText: Boolean): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, item) -> if (secretName(key)) JsonPrimitive(MASK) else redact(item, ordinaryText) })
    is JsonArray -> JsonArray(value.map { redact(it, ordinaryText) })
    is JsonPrimitive -> if (value.isString && credentialValue(value.content, ordinaryText)) JsonPrimitive(MASK) else value
    else -> value
}
/** Metadata is transport-only: structural keys and the private patch baseline stay intact. */
private fun redactCatalogValues(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, item) -> redactCatalogValues(item) })
    is JsonArray -> JsonArray(value.map(::redactCatalogValues))
    is JsonPrimitive -> if (value.isString && textCredentialValue(value.content)) JsonPrimitive(MASK) else value
    else -> value
}
private fun containsSecrets(value: JsonElement): Boolean = containsSecrets(value, ordinaryText = false)
private fun containsTextSecrets(value: JsonElement): Boolean = containsSecrets(value, ordinaryText = true)
private fun containsSecrets(value: JsonElement, ordinaryText: Boolean): Boolean = when (value) {
    is JsonObject -> value.any { (key, item) -> secretName(key) || containsSecrets(item, ordinaryText) }
    is JsonArray -> value.any { containsSecrets(it, ordinaryText) }
    is JsonPrimitive -> value.isString && credentialValue(value.content, ordinaryText)
    else -> false
}
private fun restoreHeaders(values: JsonArray, original: List<CustomHeader>): List<CustomHeader> {
    val incoming = values.map { it.jsonObject }
    if (original.any { (secretHeaderName(it.name) || credentialValue(it.value)) && incoming.none { row -> row.string("name").equals(it.name, true) } }) fail(MANUAL_ONLY, "MANUAL_ONLY")
    return incoming.map { row ->
        val name = row.string("name") ?: fail("У заголовка требуется name.")
        val value = row.string("value") ?: fail("У заголовка требуется строковый value.")
        val previous = original.singleOrNull { it.name.equals(name, true) }
        if (secretHeaderName(name) || previous?.let { credentialValue(it.value) } == true) {
            if (previous == null) fail(MANUAL_ONLY, "MANUAL_ONLY")
            if (value != MASK) fail(MANUAL_ONLY, "MANUAL_ONLY")
            previous
        } else {
            if (credentialValue(value)) fail(MANUAL_ONLY, "MANUAL_ONLY")
            CustomHeader(name, value)
        }
    }
}
private fun restoreBodies(values: JsonArray, original: List<CustomBody>): List<CustomBody> {
    val incoming = values.map { it.jsonObject }
    if (original.any { (secretName(it.key) || containsSecrets(it.value)) && incoming.none { row -> row.string("key") == it.key } }) fail(MANUAL_ONLY, "MANUAL_ONLY")
    return incoming.map { row ->
        val key = row.string("key") ?: fail("У параметра тела требуется key.")
        val value = row["value"] ?: fail("У параметра тела требуется value.")
        val previous = original.singleOrNull { it.key == key }
        if (secretName(key)) {
            if (previous == null || value != JsonPrimitive(MASK)) fail(MANUAL_ONLY, "MANUAL_ONLY")
            previous
        } else CustomBody(key, restoreNestedSecrets(value, previous?.value))
    }
}
private fun restoreNestedSecrets(value: JsonElement, previous: JsonElement?): JsonElement {
    if (previous is JsonPrimitive && previous.isString && credentialValue(previous.content)) {
        if (value != JsonPrimitive(MASK)) fail(MANUAL_ONLY, "MANUAL_ONLY")
        return previous
    }
    if (value is JsonPrimitive && value.isString && credentialValue(value.content)) fail(MANUAL_ONLY, "MANUAL_ONLY")
    if (previous is JsonObject) {
        val obj = value as? JsonObject
        if (previous.any { (key, child) -> (secretName(key) || containsSecrets(child)) && obj?.containsKey(key) != true }) fail(MANUAL_ONLY, "MANUAL_ONLY")
    }
    if (previous is JsonArray && value !is JsonArray && containsSecrets(previous)) fail(MANUAL_ONLY, "MANUAL_ONLY")
    return when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            val old = (previous as? JsonObject)?.get(key)
            if (secretName(key)) {
                if (old == null || child != JsonPrimitive(MASK)) fail(MANUAL_ONLY, "MANUAL_ONLY")
                old
            } else restoreNestedSecrets(child, old)
        })
        is JsonArray -> {
            val old = previous as? JsonArray
            if (old != null && old.drop(value.size).any(::containsSecrets)) fail(MANUAL_ONLY, "MANUAL_ONLY")
            JsonArray(value.mapIndexed { index, child -> restoreNestedSecrets(child, old?.getOrNull(index)) })
        }
        else -> value
    }
}

internal fun localToolId(option: LocalToolOption): String =
    assistantJson.encodeToJsonElement(LocalToolOption.serializer(), option).jsonObject.getValue("type").jsonPrimitive.content

private fun localToolMetadata(id: String, catalog: AssistantManagerCatalog): AssistantManagerOption =
    catalog.localTools.firstOrNull { it.id == id }?.let {
        if (it.name.isNotBlank()) it else it.copy(name = "Локальный инструмент $id")
    } ?: AssistantManagerOption(id, "Локальный инструмент $id")

/** Kotlin's sealed serializer is the source of truth, including options introduced later. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun allAssistantLocalTools(): List<LocalToolOption> {
    val choices = LocalToolOption.serializer().descriptor.getElementDescriptor(1)
    return (0 until choices.elementsCount).map { index ->
        assistantJson.decodeFromJsonElement(LocalToolOption.serializer(), buildJsonObject { put("type", choices.getElementName(index)) })
    }
}

private fun options(settings: Settings, external: AssistantManagerCatalog): JsonObject = success {
    put("providers", buildJsonArray { settings.providers.forEach { provider -> add(buildJsonObject {
        put("id", provider.id.toString()); put("name", provider.name); put("enabled", provider.enabled)
        put("models", buildJsonArray { provider.models.forEach { model -> add(buildJsonObject {
            put("id", model.id.toString()); put("name", model.displayName); put("modelId", model.modelId)
        }) } })
    }) } })
    put("models", buildJsonArray { settings.providers.forEach { provider -> provider.models.forEach { model -> add(buildJsonObject {
        put("id", model.id.toString()); put("name", model.displayName); put("modelId", model.modelId); put("providerId", provider.id.toString()); put("providerName", provider.name); put("enabled", provider.enabled)
    }) } } })
    fun optionArray(key: String, values: List<AssistantManagerOption>) { put(key, buildJsonArray { values.forEach { add(buildJsonObject {
        put("id", it.id); put("name", it.name); put("description", it.description); put("enabled", it.enabled)
    }) } }) }
    optionArray("skills", external.skills); optionArray("workspaces", external.workspaces)
    optionArray("localTools", allAssistantLocalTools().map { localToolMetadata(localToolId(it), external) })
    optionArray("mcpServers", settings.mcpServers.map { AssistantManagerOption(it.id.toString(), it.commonOptions.name, enabled = it.commonOptions.enable) })
    optionArray("tags", settings.assistantTags.map { AssistantManagerOption(it.id.toString(), it.name) })
    optionArray("quickMessageIds", settings.quickMessages.map { AssistantManagerOption(it.id.toString(), it.title, it.content) })
    optionArray("modeInjectionIds", settings.modeInjections.map { AssistantManagerOption(it.id.toString(), it.name, enabled = it.enabled) })
    optionArray("lorebookIds", settings.lorebooks.map { AssistantManagerOption(it.id.toString(), it.name, it.description, it.enabled) })
    put("fields", assistantSettingsSchema()); put("clearable", buildJsonArray { clearableAssistantFields.forEach { add(it) } })
    put("security", "Секреты скрыты как ••••. Это можно изменить только вручную в настройках. Правила подтверждений, разрешений, HARDLINE, root и прочие параметры безопасности инструмент недоступны.")
}.let(::redactCatalogValues).jsonObject

private fun references(assistant: Assistant, settings: Settings, external: AssistantManagerCatalog): JsonObject = buildJsonObject {
    assistant.chatModelId?.let { id -> put("chatModel", buildJsonObject {
        put("id", id.toString())
        val provider = settings.providers.firstOrNull { p -> p.models.any { it.id == id } }
        put("name", provider?.models?.firstOrNull { it.id == id }?.displayName ?: "Удалённая модель")
        put("provider", provider?.name.orEmpty())
    }) }
    put("workspace", external.workspaces.firstOrNull { it.id == assistant.workspaceId?.toString() }?.let { buildJsonObject { put("id", it.id); put("name", it.name) } } ?: JsonNull)
    fun names(key: String, ids: Collection<String>, options: List<Pair<String, String>>) { put(key, buildJsonArray { ids.forEach { id -> add(buildJsonObject {
        put("id", id); put("name", options.firstOrNull { it.first == id }?.second ?: "Недоступно"); put("available", options.any { it.first == id })
    }) } }) }
    names("tags", assistant.tags.map { it.toString() }, settings.assistantTags.map { it.id.toString() to it.name })
    names("quickMessageIds", assistant.quickMessageIds.map { it.toString() }, settings.quickMessages.map { it.id.toString() to it.title })
    names("mcpServers", assistant.mcpServers.map { it.toString() }, settings.mcpServers.map { it.id.toString() to it.commonOptions.name })
    names("modeInjectionIds", assistant.modeInjectionIds.map { it.toString() }, settings.modeInjections.map { it.id.toString() to it.name })
    names("lorebookIds", assistant.lorebookIds.map { it.toString() }, settings.lorebooks.map { it.id.toString() to it.name })
    names("enabledSkills", assistant.enabledSkills, external.skills.map { it.id to it.name })
}.let(::redactCatalogValues).jsonObject
