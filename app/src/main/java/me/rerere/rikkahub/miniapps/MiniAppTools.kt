package me.rerere.rikkahub.miniapps

// Moru 2101b5e457031d8adb1098dac880bdbb3019623f: mini_app_data_tool.dart and workspace_tools_service.dart.
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.java.KoinJavaComponent.getKoin

class MiniAppDataTool(private val store: MiniAppStore) {
    suspend fun execute(args: JsonObject): JsonObject = try {
        store.load()
        val action = args.text("action").orEmpty().trim().lowercase()
        val result = when (action) {
            "list" -> buildJsonObject {
                put("apps", buildJsonArray {
                    store.apps.value.forEach { app -> add(buildJsonObject {
                        put("id", app.id); put("name", app.name)
                        if (app.description.isNotEmpty()) put("description", app.description)
                        if (app.dataHelp.isNotEmpty()) put("data", app.dataHelp)
                        put("keys", JsonArray(store.storageKeys(app.id).map(::JsonPrimitive)))
                    }) }
                })
            }
            "read", "write", "remove" -> {
                val id = args.text("app_id").orEmpty().trim()
                val app = store.byId(id) ?: throw MiniAppException("not_found", "Мини-приложение не найдено. Вызовите list для получения id.")
                val key = args.text("key")?.takeIf { it.isNotEmpty() }
                when (action) {
                    "read" -> buildJsonObject {
                        if (key != null) { put("key", key); put("value", store.storageGet(app.id, key)) }
                        else {
                            val all = store.storageAll(app.id)
                            if (all.toString().length > MAX_READ_CHARS) {
                                put("keys", JsonArray(all.keys.map(::JsonPrimitive)))
                                put("note", "Данных много; читайте по одному ключу.")
                            } else put("data", all)
                        }
                    }
                    else -> {
                        if (key == null) throw MiniAppException("missing_key", "Укажите key.")
                        if (action == "write") {
                            if (!args.containsKey("value")) throw MiniAppException("missing_value", "Укажите value.")
                            store.storageSet(app.id, key, args.getValue("value"))
                        } else store.storageRemove(app.id, key)
                        buildJsonObject { put(if (action == "write") "written" else "removed", key) }
                    }
                }
            }
            else -> throw MiniAppException("invalid_action", "Укажите action: list, read, write или remove.")
        }
        JsonObject(mapOf("ok" to JsonPrimitive(true)) + result)
    } catch (e: MiniAppException) { miniAppFailure(e.code, e.message.orEmpty()) }

    companion object { const val MAX_READ_CHARS = 20000 }
}

fun miniAppsTool(store: () -> MiniAppStore = { getKoin().get() }): Tool = Tool(
    name = "mini_apps",
    description = "Читать и изменять данные мини-приложений пользователя без открытия. Сначала вызовите list: он показывает id, назначение, формат данных и ключи. Сохраняйте формат приложения; прочитайте ключ перед записью. Открытое приложение обновляется при изменении данных.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "read", "write", "remove").map(::JsonPrimitive))); put("description", "list: приложения; read: ключ или все данные app_id; write: записать value в key; remove: удалить key.") })
        put("app_id", buildJsonObject { put("type", "string"); put("description", "id приложения из list.") })
        put("key", buildJsonObject { put("type", "string"); put("description", "Ключ хранилища приложения из list или keys; обязателен для write и remove, необязателен для read.") })
        put("value", buildJsonObject {
            put("description", "JSON-значение для записи: строка, число, boolean, null, массив или объект. Сохраняйте исходный тип и формат данных приложения.")
            put("anyOf", buildJsonArray {
                for (type in listOf("string", "number", "boolean", "null")) add(buildJsonObject { put("type", type) })
                add(buildJsonObject { put("type", "array"); put("items", buildJsonObject {}) })
                add(buildJsonObject { put("type", "object"); put("additionalProperties", true) })
            })
        })
    }, listOf("action")) },
    execute = { args -> listOf(UIMessagePart.Text(MiniAppDataTool(store()).execute(args.jsonObject).toString())) },
)

internal fun miniAppWorkspacePath(path: String?, cwd: String?): String {
    fun absolute(value: String) = if (value.startsWith('/')) value else "/workspace/$value"
    val base = absolute(cwd?.trim()?.takeIf { it.isNotEmpty() } ?: "/workspace")
    val input = path?.trim()?.takeIf { it.isNotEmpty() }
    val joined = if (input == null) base else if (input.startsWith('/')) input else "$base/$input"
    require('\u0000' !in joined) { "Проверьте путь внутри выбранной рабочей папки." }
    val segments = mutableListOf<String>()
    joined.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> {
                require(segments.isNotEmpty()) { "Путь выходит за пределы выбранной рабочей среды." }
                segments.removeAt(segments.lastIndex)
            }
            else -> segments.add(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

fun publishMiniAppTool(
    workspaceId: String,
    repository: WorkspaceRepository,
    cwd: String?,
    store: () -> MiniAppStore = { getKoin().get() },
    check: suspend (MiniApp) -> JsonObject = { getKoin().get<MiniAppChecker>().run(it).toJson() },
    needsApproval: () -> Boolean,
): Tool = Tool(
    name = "publish_mini_app",
    description = """
        Опубликовать созданное в рабочей среде веб-приложение как мини-приложение Rikka: пользователь открывает его в приложении и может закрепить на главном экране.
        В папке нужен moru-app.json: {"id":"water-tracker" (строчные латинские буквы, цифры, дефисы), "name":"Вода", "description":"...", "entry":"index.html" (по умолчанию), "icon":"icon.svg" (необязательно, SVG)}.
        Создавайте статическую страницу для телефона (HTML/CSS/JS, без сервера, удобную в светлой и тёмной теме). Используйте обычные относительные <script src> и <link>; type="module" и fetch() локальных файлов блокируются, поэтому сначала собирайте модули в обычный скрипт.
        Храните данные только через window.moru.storage: асинхронные get(key), set(key,jsonValue), remove(key), keys(). Опишите ключи и их формат в поле "data" манифеста, чтобы чат мог изменять данные. Перерисовывайте страницу по событию окна "moru:storage", которое приходит при изменении данных чатом.
        Другие возможности: await moru.ai.ask(prompt,{system}) возвращает текст модели по умолчанию; moru.notify(title,body); moru.reminders.set(id,{time:"HH:mm",days?:[1-7],title,body}), remove(id), list().
        Интернет: перечислите хосты в поле "network":["api.example.com","*.example.org"] и вызывайте await moru.fetch(url,{method,headers,body:string}) -> {status,ok,headers,body,json()}; этот вызов не ограничен CORS страницы.
        Календарь: "permissions":["calendar"], затем moru.calendar.list({range:"today"|"week"|"month",begin,end,query}) и moru.calendar.add({title,start,end,description,location,all_day,reminders}).
        Rikka автоматически добавляет moru.js. Публикация того же id обновляет код и сохраняет данные. После публикации дайте пользователю ссылку из результата в Markdown: [Открыть <name>](rikkahub://app/<id>).
        Результат содержит "check": приложение открыто скрыто с копией данных. Исправьте page_errors, failed_moru_calls, ошибки console или blank_page и опубликуйте снова перед сообщением о готовности и отправкой ссылки. Если loaded=false или проверка пропущена (skipped), попросите пользователя открыть приложение для проверки.
        path по умолчанию — текущая папка чата; разрешение пути выполняется внутри Rootfs выбранной рабочей среды с её подключёнными папками.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(buildJsonObject {
        put("path", buildJsonObject { put("type", "string"); put("description", "Папка приложения внутри Rootfs выбранной рабочей среды; абсолютный путь или относительно текущей папки чата.") })
    }) },
    needsApproval = { needsApproval() },
    execute = { args ->
        val result = try {
            kotlin.check(repository.getById(workspaceId)?.shellStatus == WorkspaceShellStatus.READY.name) { "Выберите готовую рабочую папку." }
            val path = miniAppWorkspacePath(args.jsonObject.text("path"), cwd)
            // Never interpret the model path as an Android host path; resolve selected workspace mounts first.
            val sourceLease = repository.acquireRootfsFileLease(workspaceId)
            val installed = try {
                val source = repository.resolveRootfsFile(workspaceId, path)
                withContext(Dispatchers.IO) {
                    if (!source.isDirectory) throw MiniAppException("not_a_folder", "Укажите папку приложения в рабочей среде.")
                }
                store().install(source)
            } finally {
                sourceLease.close()
            }
            val check = try { withTimeoutOrNull(30_000) { check(installed.app) }
                ?: buildJsonObject { put("ok", false); put("skipped", "Время проверки истекло.") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { buildJsonObject { put("ok", false); put("skipped", "Проверка приложения недоступна.") } }
            buildJsonObject {
                put("ok", true); put("id", installed.app.id); put("name", installed.app.name)
                put("link", installed.app.link); put("updated", installed.updated)
                put("files", installed.files); put("bytes", installed.bytes); put("check", check)
            }
        } catch (e: MiniAppException) { miniAppFailure(e.code, e.message.orEmpty()) }
        catch (_: IllegalArgumentException) { miniAppFailure("path_error", "Проверьте путь папки внутри выбранной рабочей среды.") }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun miniAppFailure(code: String, message: String) = buildJsonObject {
    put("ok", false); put("error", code); put("message", message)
}
