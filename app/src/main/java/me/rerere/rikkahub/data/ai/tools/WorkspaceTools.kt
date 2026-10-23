package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.ai.tools.local.deviceToolParts
import me.rerere.rikkahub.data.ai.tools.local.commandExitFailureReason
import me.rerere.rikkahub.data.ai.tools.local.CommandEnvironment
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.BackgroundStatus
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val WORKSPACE_EXISTS_ERROR = "Файл уже существует. Укажите другое имя или overwrite=true."
private const val WORKSPACE_NOT_FILE_ERROR = "Это папка, а нужен файл. Укажите путь к файлу."

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "publish_mini_app" to false,
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_background_start" to true,
    "workspace_background_stop" to true,
    "workspace_background_list" to false,
    "workspace_background_output" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

/** Live workspace settings retain the argument-specific write/edit path gate. */
fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>, input: kotlinx.serialization.json.JsonElement): Boolean =
    resolveWorkspaceToolApproval(name, overrides) ||
        (name in setOf("workspace_write_file", "workspace_edit_file") && input.pathOutsideWritableRoots("path"))

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId)
    val approvalOverrides = workspace?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd

    return (listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    ) + (if (workspace?.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name) listOf(
        me.rerere.rikkahub.miniapps.publishMiniAppTool(workspaceId, workspaceRepository, cwd) { needsApproval("publish_mini_app") }
    ) else emptyList()) + createBackgroundTools(workspaceId, workspaceRepository, shellCwd)).map { tool ->
        tool.copy(execute = { input -> deviceToolParts { tool.execute(input) } })
    }
}

private fun createBackgroundTools(id: String, repository: WorkspaceRepository, defaultCwd: String?): List<Tool> = listOf(
    Tool(
        name = "workspace_background_start",
        description = "Запустить фоновую команду в рабочей папке и получить id процесса. Одновременно работают не более пяти процессов; при выходе приложения или удалении папки они останавливаются. Проверяйте состояние через workspace_background_list, вывод через workspace_background_output, остановку через workspace_background_stop.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("command", buildJsonObject { put("type", "string"); put("description", "Команда оболочки для фонового выполнения.") })
            put("cwd", buildJsonObject { put("type", "string"); put("description", "Рабочий каталог относительно корня /workspace; по умолчанию каталог текущего чата, затем корень рабочей папки.") })
        }, listOf("command")) },
        needsApproval = { true },
        execute = { args ->
            val params = args.jsonObject
            val cwd = (params.string("cwd") ?: defaultCwd.orEmpty()).workspaceRelativeCwd()
            listOf(UIMessagePart.Text(repository.startBackground(id, params.string("command") ?: error("Укажите команду в command."), cwd).toBackgroundJson(false).toString()))
        },
    ),
    Tool(
        name = "workspace_background_list", description = "Список фоновых процессов текущей рабочей папки, включая состояние и код завершения.",
        execute = { listOf(UIMessagePart.Text(buildJsonArray {
            repository.backgroundProcesses(id).forEach { add(it.toBackgroundJson(false)) }
        }.toString())) },
    ),
    Tool(
        name = "workspace_background_output", description = "Прочитать последние сохранённые части stdout/stderr и код завершения процесса текущей рабочей папки. droppedStdout/droppedStderr показывают потерянные символы; при truncated полный старый вывод недоступен.",
        parameters = { backgroundIdSchema() },
        execute = { args -> listOf(UIMessagePart.Text(repository.backgroundOutput(id,
            args.jsonObject.string("process_id") ?: error("Укажите process_id из списка фоновых процессов.")).toBackgroundJson(true).toString())) },
    ),
    Tool(
        name = "workspace_background_stop", description = "Остановить процесс и его дочерние процессы в текущей рабочей папке; сохранить запись и оставшийся вывод.",
        parameters = { backgroundIdSchema() }, needsApproval = { true },
        execute = { args ->
            val processId = args.jsonObject.string("process_id") ?: error("Укажите process_id из списка фоновых процессов.")
            check(repository.stopBackground(id, processId)) { "Процесс не найден в этой рабочей папке. Проверьте process_id через workspace_background_list." }
            listOf(UIMessagePart.Text(repository.backgroundOutput(id, processId).toBackgroundJson(false).toString()))
        },
    ),
)

private fun backgroundIdSchema() = InputSchema.Obj(buildJsonObject {
    put("process_id", buildJsonObject { put("type", "string"); put("description", "id процесса из workspace_background_start или workspace_background_list; область — текущая рабочая папка.") })
}, listOf("process_id"))

private fun BackgroundStatus.toBackgroundJson(includeOutput: Boolean) = buildJsonObject {
    put("id", id); put("command", command); put("cwd", cwd); put("running", running)
    put("exitCode", exitCode?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    put("startedAtMillis", startedAtMillis)
    if (!running) commandExitFailureReason(exitCode, stderr, CommandEnvironment.WORKSPACE)?.let { put("message", it) }
    if (includeOutput) {
        put("stdout", stdout); put("stderr", stderr)
        put("droppedStdout", droppedStdout); put("droppedStderr", droppedStderr)
        val truncated = droppedStdout > 0 || droppedStderr > 0
        put("truncated", truncated)
        if (truncated) put("message", "Сохранён только конец вывода; прежние символы потеряны. Для полного журнала настройте перенаправление в файл и прочитайте его через workspace_read_file или workspace_shell. Не повторяйте изменяющую данные команду только ради журнала.")
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Прочитать файл в Rootfs рабочей папки текущего ассистента. Укажите абсолютный путь внутри Rootfs.
        Файлы рабочей папки доступны в /workspace.
        Поддерживаются текст UTF-8 и изображения png/jpg/jpeg/gif/webp/bmp/svg/heic/heif/avif/ico. Максимум 8 МБ; большие текстовые файлы читайте частями через workspace_shell (head, tail, grep).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Записать текст UTF-8 в файл Rootfs рабочей папки текущего ассистента. Укажите абсолютный путь внутри Rootfs.
        Файлы рабочей папки доступны в /workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст UTF-8 для записи; обязательная строка.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean"); put("default", true)
                    put("description", "Перезаписывать существующий файл: true или false без кавычек. По умолчанию true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("Укажите текст в text.")
        val overwrite = params.workspaceBoolean("overwrite", true)
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Изменить текст UTF-8 в файле Rootfs рабочей папки текущего ассистента. Укажите абсолютный путь внутри Rootfs.
        Файлы рабочей папки доступны в /workspace.
        Укажите old_text и new_text. Обычно old_text должен встречаться ровно один раз; replace_all=true заменяет все совпадения.
        Если точное совпадение отсутствует, применяется сопоставление строк с учётом различий пробелов.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Непустой исходный фрагмент для замены.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Новый текст; пустая строка удаляет найденный фрагмент.")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean"); put("default", false)
                    put("description", "Заменять все совпадения: true или false без кавычек. По умолчанию false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("Укажите исходный фрагмент в old_text.")
        val newText = params.string("new_text") ?: error("Укажите новый фрагмент в new_text.")
        val replaceAll = params.workspaceBoolean("replace_all", false)
        require(oldText.isNotEmpty()) { "old_text не должен быть пустым. Укажите текст для замены." }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = replaceText(original, oldText, newText, replaceAll)
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Выполнить команду оболочки в Rootfs рабочей папки текущего ассистента. Файлы доступны в /workspace. ")
        append("cwd задаёт каталог относительно рабочей папки либо путь внутри /workspace. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("По умолчанию '$defaultCwd'. ")
        }
        append("Требуется установленный и готовый Rootfs. stdout/stderr могут быть ограничены; truncated и warning объясняют неполноту вывода.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Команда оболочки для выполнения; обязательная строка.")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Каталог относительно рабочей папки или внутри /workspace. По умолчанию '$defaultCwd'."
                        } else {
                            "Каталог относительно рабочей папки или внутри /workspace. По умолчанию корень рабочей папки."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer"); put("default", 30)
                    put(
                        "description",
                        "Время команды в секундах, целое число без кавычек. По умолчанию 30; значение ограничивается диапазоном 1–$SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("Укажите команду в command.")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty()).workspaceRelativeCwd()
        val timeoutMillis = if ("timeout" !in params) WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS else {
            val seconds = (params["timeout"] as? JsonPrimitive)?.takeUnless { value -> value.isString }?.longOrNull
                ?: error("Укажите timeout как целое число секунд без кавычек.")
            seconds.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS) * 1_000L
        }
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.timedOut) put("message", "Время команды истекло. Проверьте, что успело выполниться, перед новым вызовом; команда автоматически не повторяется.")
                    else commandExitFailureReason(result.exitCode, result.stderr, CommandEnvironment.WORKSPACE)?.let { put("message", it) }
                    if (result.truncated) {
                        put("truncated", true)
                        put("warning", "Вывод команды получен не полностью. Для проверки прочитайте нужную часть вывода отдельно.")
                    }
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? {
    if (name !in this) return null
    return (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: error("Укажите $name как строку.")
}

private fun kotlinx.serialization.json.JsonObject.workspaceBoolean(name: String, default: Boolean): Boolean {
    if (name !in this) return default
    return (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: error("Укажите $name: true или false без кавычек.")
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "Файл слишком большой (${size / 1024 / 1024} МБ, максимум ${MAX_READ_FILE_BYTES / 1024 / 1024} МБ). Прочитайте нужную часть через workspace_shell: head, tail или grep."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Изображение прочитано.")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Запись файла",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${WORKSPACE_EXISTS_ERROR.shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${WORKSPACE_NOT_FILE_ERROR.shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action не завершена вовремя. Проверьте файл перед повторной попыткой.")
    }
    if (result.exitCode != 0) {
        val authored = result.stderr.trim().takeIf { it == WORKSPACE_EXISTS_ERROR || it == WORKSPACE_NOT_FILE_ERROR }
        if (authored != null) error(authored)
        error("$action не выполнена (код ${result.exitCode}). Проверьте путь, доступ и свободное место.")
    }
    if (result.truncated) {
        error("$action вернула слишком большой ответ. Проверьте файл через workspace_shell.")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Не удалось прочитать сведения о файле. Проверьте файл через workspace_shell.")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Не удалось прочитать сведения о файле. Проверьте файл через workspace_shell." }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Не удалось определить размер файла. Проверьте файл через workspace_shell.")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Не удалось определить время изменения файла. Проверьте файл через workspace_shell.")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("Укажите путь в $name.")
    require(path.isNotBlank()) { "Укажите путь в $name." }
    require(path.startsWith("/")) { "Укажите в $name полный путь внутри Rootfs, начиная с /." }
    require(!path.contains('\u0000')) { "Путь в $name содержит недопустимый символ. Укажите другой путь." }
    path.requireNoDotSegments(name)
    return path
}

/** Reject traversal before selecting a mount or checking writable path prefixes. */
private fun String.requireNoDotSegments(name: String) {
    require(split('/').none { it == "." || it == ".." }) {
        "Путь в $name содержит сегменты «.» или «..». Укажите путь без этих сегментов."
    }
}

private fun String.workspaceRelativeCwd(): String {
    val path = replace('\\', '/').trim()
    require(!path.contains('\u0000')) { "Путь в cwd содержит недопустимый символ. Укажите другой путь." }
    path.requireNoDotSegments("cwd")
    return when {
        path == "/workspace" -> ""
        path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
        else -> {
            require(!path.startsWith('/')) { "Укажите в cwd путь относительно рабочей папки или внутри /workspace." }
            path
        }
    }
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录和技能目录
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Абсолютный путь внутри Rootfs. Файлы рабочей папки доступны в /workspace."
            } else {
                "Необязательный абсолютный путь внутри Rootfs. Файлы рабочей папки доступны в /workspace."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
