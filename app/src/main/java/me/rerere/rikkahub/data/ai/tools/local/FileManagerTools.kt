// Agent-compatible file/batch/open/image surface adapted from ExTV/rikkahub-agent (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal fun fileSchema(required: List<String> = emptyList(), vararg properties: Pair<String, String>): InputSchema.Obj = InputSchema.Obj(
    buildJsonObject { properties.forEach { (name, type) -> put(name, buildJsonObject {
        put("type", type)
        if (type == "array") put("items", buildJsonObject { put("type", "string") })
        if (name == "limit") { put("minimum", 1); put("maximum", 500) }
        if (name == "max_bytes") { put("minimum", 1); put("maximum", 1048576) }
    }) } }, required.takeIf { it.isNotEmpty() })

internal fun fileArgument(obj: JsonObject, name: String, default: String? = null): String =
    (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default ?: error("Укажите $name строкой.")
internal fun fileBoolean(obj: JsonObject, name: String, default: Boolean = false): Boolean {
    if (name !in obj) return default
    return (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error("$name должен быть логическим значением.")
}
internal fun fileInteger(obj: JsonObject, name: String, default: Int, range: IntRange): Int {
    if (name !in obj) return default
    return requireNotNull((obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull) { "$name должен быть целым числом." }
        .also { require(it in range) { "$name вне допустимого диапазона." } }
}

internal fun fileJsonTool(name: String, description: String, schema: InputSchema.Obj,
    execute: suspend (JsonObject) -> JsonObject): Tool = Tool(name, description,
    parameters = { schema }, needsApproval = { true }, execute = { input -> deviceToolResult(fileToolOperation(name)) {
        withContext(Dispatchers.IO) {
            try { execute(requireNotNull(input as? JsonObject) { "Параметры должны быть объектом." }) }
            catch (cleanup: FileCreationCleanupFailedException) {
                fileToolOperation(name)?.let { FileOperationDiagnostics.record(it, cleanup) }
                buildJsonObject {
                    put("error", "creation_cleanup_failed"); put("created_path", cleanup.created.reference)
                    put("detail", if (cleanup.sharedPathMayBePartial)
                        "Запись прервана. Файл мог сохраниться частично. Проверьте созданный файл перед повтором."
                    else "Android не разрешил убрать созданный документ. Проверьте его в выбранной папке.")
                }
            }
            catch (failure: FileMoveSourceDeleteException) {
                fileToolOperation(name)?.let { FileOperationDiagnostics.record(it, failure) }
                buildJsonObject {
                    put("error", "source_delete_failed"); put("detail", "Копия сохранена, но источник не удалось полностью удалить. Назначение сохранено для восстановления.")
                }
            }
            catch (recovery: FileRestoreFailedException) {
                fileToolOperation(name)?.let { FileOperationDiagnostics.record(it, recovery) }
                buildJsonObject {
                    put("error", "restore_failed")
                    put("detail", "Android не смог восстановить предыдущий файл. Резервная копия сохранена в scratch.")
                    put("backup_path", "/scratch/${recovery.backup.name}")
                }
            }
        }
    } })

/** Every tool uses this immutable conversation-scoped resolver; no operation invokes a shell. */
fun fileManagerTools(access: LocalFileAccess, context: Context? = null, modelCanReadImages: Boolean = false): List<Tool> {
    val scope = " Paths: ${access.defaultPath}, /scratch, /upload, attachment:N for reads, and content:// under a persisted SAF tree. Absolute shared-storage paths inside that granted tree also use SAF. With the explicit all-files setting and Android grant, mounted shared storage can be used directly. System paths and app settings are blocked." + ASSISTANT_FILE_DIRECTORY_HINT
    suspend fun source(obj: JsonObject, key: String = "path", write: Boolean = false) = access.resolve(fileArgument(obj, key), write)
    suspend fun entry(source: LocalFileSource): JsonObject {
        val name = access.name(source)
        val directory = access.isDirectory(source)
        return buildJsonObject {
            put("path", source.reference); put("name", name); put("is_directory", directory)
            put("size_bytes", if (directory) 0L else access.size(source)); put("modified_at_ms", access.modified(source))
            if (source is LocalFileSource.Content) put("is_content_uri", true)
        }
    }
    suspend fun listing(obj: JsonObject, find: Boolean): JsonObject = access.withReadOperation {
        val root = access.resolve(fileArgument(obj, if (find) "root" else "path"))
        val query = if (find) fileArgument(obj, "query") else obj.textArgument("pattern")
        val regex = query?.takeIf { !find || '*' in it || '?' in it }?.let(::fileGlobRegex)
        var skippedPrivatePaths = 0
        var traversalTruncated = false
        val all = access.walk(root, fileBoolean(obj, "recursive", find),
            onRestrictedPathSkipped = { skippedPrivatePaths++ }, onTruncated = { traversalTruncated = true })
        val limit = fileInteger(obj, "limit", 50, 1..500)
        val matches = all.filter { val name = access.name(it); if (regex != null) regex.matches(name) else query == null || name.contains(query, ignoreCase = true) }
        val selected = matches.take(limit).map { entry(it) }
        buildJsonObject {
            put("files", JsonArray(selected)); put("truncated", traversalTruncated || matches.size > limit)
            if (traversalTruncated) {
                // Not just "more matches than limit": entries past the walk cap were never examined.
                put("scan_incomplete", true)
                put("message", "Папка слишком большая: просмотрена только часть файлов. Укажите более узкую папку в ${if (find) "root" else "path"}.")
            }
            if (skippedPrivatePaths > 0) {
                put("skipped_restricted_count", skippedPrivatePaths)
                put("warnings", buildJsonArray { add("Папки Android/data и Android/obb пропущены: доступ к личным данным других приложений запрещён.") })
            }
        }
    }
    suspend fun batch(obj: JsonObject, operation: String): JsonObject {
        val explicit = if ("paths" in obj) requireNotNull(obj["paths"] as? JsonArray) {
            "paths должен быть массивом путей. Укажите массив либо уберите paths для выбора по root и pattern."
        } else null
        val paths = if (explicit != null) {
            require(explicit.isNotEmpty() && explicit.size <= 500) { "paths должен содержать от 1 до 500 путей." }
            explicit.map { requireNotNull((it as? JsonPrimitive)?.takeIf { it.isString }?.content) { "paths должен содержать строки." } }
        } else {
            val root = access.resolve(fileArgument(obj, "root"))
            val pattern = fileGlobRegex(fileArgument(obj, "pattern"))
            access.list(root).filter { pattern.matches(access.name(it)) }.map { it.reference }.also { require(it.size <= 500) { "Найдено более 500 файлов." } }
        }
        val destination = if (operation != "delete") access.resolve(fileArgument(obj, "dst_dir")) else null
        if (destination != null) require(access.isDirectory(destination)) { "dst_dir должен быть существующей папкой." }
        val failures = mutableListOf<JsonObject>()
        var succeeded = 0
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            try {
                val from = access.resolve(path, operation != "copy")
                require(access.exists(from)) { "Источник не найден." }
                if (operation == "delete") {
                    require(access.delete(from, fileBoolean(obj, "recursive"))) { "Файл не удалён." }
                } else {
                    val name = access.name(from)
                    val previous = access.findChild(destination!!, name)
                    val overwrite = fileBoolean(obj, "overwrite")
                    require(previous == null || overwrite) { "Назначение уже существует." }
                    if (operation == "move" && previous == null &&
                        from is LocalFileSource.Local && destination is LocalFileSource.Local) {
                        val target = access.child(destination, name)
                        if (access.tryLocalMove(from, target, overwrite = false) != null) {
                            succeeded++
                            continue
                        }
                    }
                    var owned: LocalFileSource? = null
                    val directory = access.isDirectory(from)
                    val unpublishedShared = previous == null && !access.permitsAutomaticRollback(destination)
                    // Failed rename attempts retain the original owned destination
                    // transaction, including cleanup of a partial directory copy.
                    val to = if (previous != null) previous else if (unpublishedShared) access.child(destination, name)
                    else withContext(kotlinx.coroutines.NonCancellable) { access.createNewChild(destination, name, directory).also { owned = it } }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (owned != null) require(access.name(to) == name) { "Провайдер SAF изменил имя создаваемого файла." }
                        val replace = overwrite || previous == null && !unpublishedShared
                        if (operation == "copy") access.copy(from, to, replace, ownedDestination = owned != null)
                        else access.move(from, to, replace, ownedDestination = owned != null)
                    } catch (error: Exception) {
                        if (owned != null && !isMoveCopyPreserved(error)) access.cleanupOwnedTarget(to, error, recursive = true)
                        throw error
                    }
                }
                succeeded++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                (safFileCause(error) as? CancellationException)?.let { throw it }
                val fileOperation = fileToolOperation("batch_$operation")
                fileOperation?.let { FileOperationDiagnostics.record(it, error) }
                failures += buildJsonObject {
                    put("path", path)
                    put("error", if (isMoveCopyPreserved(error)) "Копия сохранена, но источник не удалось полностью удалить."
                        else if (error is FileCreationCleanupFailedException && error.sharedPathMayBePartial) error.message.orEmpty()
                        else if (safFileCause(error) != null) deviceToolFailureReason(error, fileOperation)
                        else "Не удалось выполнить операцию. Проверьте путь, доступ и существующее назначение.")
                    if (error is FileRestoreFailedException) put("backup_path", "/scratch/${error.backup.name}")
                    if (error is FileCreationCleanupFailedException) put("created_path", error.created.reference)
                }
            }
        }
        return buildJsonObject { put("success", succeeded); put("failed", JsonArray(failures)) }
    }
    val tools = mutableListOf<Tool>()
    tools += fileJsonTool("list_files", "List files by optional filename glob; recursive=false, limit=50 (maximum500). At most5000 guarded entries." + scope,
        fileSchema(listOf("path"), "path" to "string", "pattern" to "string", "recursive" to "boolean", "limit" to "integer")) { listing(it, false) }
    tools += fileJsonTool("find_files", "Find filenames by query substring or glob; recursive=true, limit=50 (maximum500)." + scope,
        fileSchema(listOf("root", "query"), "root" to "string", "query" to "string", "recursive" to "boolean", "limit" to "integer")) { listing(it, true) }
    tools += fileJsonTool("read_file", "Read at most max_bytes (default65536, maximum1048576). Text includes BOM detection; binary data returns base64." + scope,
        fileSchema(listOf("path"), "path" to "string", "max_bytes" to "integer", "encoding" to "string")) { obj ->
        val source = source(obj)
        require(!access.isDirectory(source)) { "Это папка, используйте list_files" }
        val limit = fileInteger(obj, "max_bytes", 65536, 1..1048576)
        val bytes = access.openInput(source).use { readFileBytes(it, limit + 1) }
        val data = bytes.take(limit).toByteArray()
        val binary = data.take(512).count { val value = it.toInt() and 255; value < 9 || value in 14..31 } > minOf(data.size, 512) * 0.15
        val bom = data.size >= 3 && data[0] == 0xef.toByte() && data[1] == 0xbb.toByte() && data[2] == 0xbf.toByte()
        val charset = obj.textArgument("encoding")?.let(Charset::forName) ?: when {
            data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        var bytesRead = data.size
        buildJsonObject {
            put("truncated", bytes.size > limit)
            if (binary && charset !in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) { put("binary", true); put("content_base64", Base64.getEncoder().encodeToString(data)) }
            else {
                val utf16Bom = data.size >= 2 && ((data[0] == 0xff.toByte() && data[1] == 0xfe.toByte()) || (data[0] == 0xfe.toByte() && data[1] == 0xff.toByte()))
                val offset = if (bom) 3 else if (utf16Bom) 2 else 0
                val input = ByteBuffer.wrap(data, offset, data.size - offset)
                val output = CharBuffer.allocate(data.size + 1)
                val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                // A truncated stream is not at EOF: leave a partial final code point
                // unconsumed, instead of manufacturing a replacement character.
                val decoded = decoder.decode(input, output, bytes.size <= limit)
                if (decoded.isError) decoded.throwException()
                put("content", output.flip().toString()); put("encoding", charset.name())
                bytesRead = input.position()
            }
            put("bytes_read", bytesRead)
        }
    }
    tools += fileJsonTool("write_binary_file", "Write base64_content, up to16MiB decoded. overwrite=false refuses an existing file. Local writes are staged before replacement." + scope,
        fileSchema(listOf("path", "base64_content"), "path" to "string", "base64_content" to "string", "overwrite" to "boolean")) { obj ->
        val encoded = decodeFileBase64(fileArgument(obj, "base64_content"))
        val (destination, bytes) = access.writePath(fileArgument(obj, "path"), encoded, fileBoolean(obj, "overwrite"))
        buildJsonObject { put("success", true); put("path", destination.reference); put("bytes_written", bytes) }
    }
    tools += fileJsonTool("delete_file", "Delete a file or directory; recursive=true required for nonempty folders. Roots cannot be deleted." + scope,
        fileSchema(listOf("path"), "path" to "string", "recursive" to "boolean")) { obj ->
        val from = source(obj, write = true)
        val count = if (access.isDirectory(from)) access.walk(from).size + 1 else 1
        val deleted = access.delete(from, fileBoolean(obj, "recursive"))
        buildJsonObject {
            put("success", deleted); put("path", from.reference); put("deleted_count", if (deleted) count else 0)
            if (!deleted) put("message", "Файл или папка не удалены. Проверьте, существует ли путь, и доступ к папке.")
        }
    }
    for (operation in listOf("copy", "move")) tools += fileJsonTool("${operation}_file", "${operation.replaceFirstChar(Char::uppercase)} a file or directory from src to dst. overwrite=false. Copies are limited to512MiB." + scope,
        fileSchema(listOf("src", "dst"), "src" to "string", "dst" to "string", "overwrite" to "boolean")) { obj ->
        val from = source(obj, "src", operation == "move")
        val (destination, bytes) = if (operation == "copy") access.copyPath(from, fileArgument(obj, "dst"), fileBoolean(obj, "overwrite"))
        else access.movePath(from, fileArgument(obj, "dst"), fileBoolean(obj, "overwrite"))
        buildJsonObject { put("success", true); put("from", from.reference); put("to", destination.reference); put("bytes_copied", bytes) }
    }
    tools += fileJsonTool("create_directory", "Create a directory by path, including an absolute path inside a granted SAF tree. Alternatively supply an existing parent path and a direct child name." + scope,
        fileSchema(listOf("path"), "path" to "string", "name" to "string")) { obj ->
        val path = fileArgument(obj, "path")
        val name = if ("name" in obj) fileArgument(obj, "name") else null
        val plan = if (name != null) {
            require(name.isNotBlank() && name != "." && name != ".." && name.length <= 255 &&
                name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Нужно прямое имя папки без разделителей." }
            val parent = access.resolve(path)
            require(access.isDirectory(parent)) { "Папка назначения не найдена." }
            if (parent is LocalFileSource.Content) FileWritePlan(access.findChild(parent, name), parent, name)
            else access.planWritePath(java.io.File((parent as LocalFileSource.Local).file, name).path)
        } else access.planWritePath(path)
        val existing = plan.existing?.let { access.exists(it) } == true
        var owned: LocalFileSource? = null
        val destination = plan.existing ?: withContext(kotlinx.coroutines.NonCancellable) {
            access.createNewChild(requireNotNull(plan.parent), plan.name, isDirectory = true).also { owned = it }
        }
        val created = try {
            currentCoroutineContext().ensureActive()
            if (owned != null) {
                require(access.name(destination) == plan.name) { "Провайдер SAF изменил имя создаваемой папки." }
                require(access.isDirectory(destination)) { "Провайдер SAF не создал папку." }
            }
            access.createDirectory(destination)
        } catch (failure: Exception) {
            owned?.let { access.cleanupOwnedTarget(it, failure) }
            throw failure
        }
        buildJsonObject {
            put("success", created); put("path", destination.reference); put("created", created && !existing)
            if (!created) put("message", "Не удалось создать папку. Проверьте, не занят ли путь файлом; укажите другое имя или проверьте доступ к родительской папке.")
        }
    }
    tools += fileJsonTool("file_info", "Get file metadata. include_hash=true computes SHA256 up to512MiB." + scope,
        fileSchema(listOf("path"), "path" to "string", "include_hash" to "boolean")) { obj ->
        val from = source(obj)
        if (!access.exists(from)) buildJsonObject { put("path", from.reference); put("exists", false) }
        else {
            val result = entry(from).toMutableMap().also { it["exists"] = JsonPrimitive(true) }
            if (fileBoolean(obj, "include_hash") && !access.isDirectory(from)) {
                require(access.size(from) <= LocalFileAccess.MAX_COPY_BYTES) { "Файл слишком велик для хеширования." }
                val digest = MessageDigest.getInstance("SHA-256")
                access.openInput(from).use { input -> val buffer = ByteArray(8192); var count: Int; var total=0L
                    while (input.read(buffer).also { count = it } >= 0) { currentCoroutineContext().ensureActive(); total+=count; require(total<=LocalFileAccess.MAX_COPY_BYTES); digest.update(buffer, 0, count) } }
                result["sha256"] = JsonPrimitive(digest.digest().joinToString("") { "%02x".format(it) })
            }
            JsonObject(result)
        }
    }
    val batchProperties = arrayOf("paths" to "array", "root" to "string", "pattern" to "string")
    for (operation in listOf("copy", "move", "delete")) tools += fileJsonTool("batch_$operation", "${operation.replaceFirstChar(Char::uppercase)} up to500 explicit paths or direct children matching root+pattern; report each failed item and keep independent successes." + scope,
        fileSchema(emptyList(), *batchProperties, *(if (operation == "delete") arrayOf("recursive" to "boolean") else arrayOf("dst_dir" to "string", "overwrite" to "boolean")))) { batch(it, operation) }
    tools += showImageFileTool(access, modelCanReadImages)
    tools += openFileTool(access, context)
    return tools
}

internal suspend fun readFileBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private suspend fun decodeFileBase64(value: String): ByteArray {
    val start = if (value.startsWith("data:", ignoreCase = true)) {
        val comma = value.indexOf(',')
        require(comma >= 0 && value.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
            "Нужен data URI с кодировкой base64."
        }
        comma + 1
    } else 0
    val maximum = ((LocalFileAccess.MAX_WRITE_BYTES.toLong() + 2) / 3 * 4).toInt()
    val encoded = StringBuilder(minOf(value.length - start, maximum))
    for (index in start until value.length) {
        if (index % 8192 == 0) currentCoroutineContext().ensureActive()
        // Line breaks and spaces are formatting; base64url `-`/`_` is the same data. Any
        // other character is corrupt input: skipping it would silently write wrong bytes.
        val character = when (val raw = value[index]) { '-' -> '+'; '_' -> '/'; else -> raw }
        if (character.isWhitespace()) continue
        require(character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '+' || character == '/' || character == '=') {
            "Некорректные данные base64: недопустимый символ «${value[index]}». Передайте base64 без посторонних символов."
        }
        require(encoded.length < maximum) { "Данные слишком велики." }
        encoded.append(character)
    }
    require(encoded.isNotEmpty()) { "Данные base64 пусты." }
    return Base64.getMimeDecoder().decode(encoded.toString()).also {
        require(it.size <= LocalFileAccess.MAX_WRITE_BYTES) { "Данные слишком велики." }
    }
}

internal fun fileGlobRegex(pattern: String): Regex {
    require(pattern.length <= 512) { "Шаблон слишком длинный." }
    return Regex(buildString {
        append('^')
        pattern.forEach { when (it) { '*' -> append(".*"); '?' -> append('.'); else -> append(Regex.escape(it.toString())) } }
        append('$')
    }, RegexOption.IGNORE_CASE)
}

private fun showImageFileTool(access: LocalFileAccess, modelCanReadImages: Boolean): Tool = Tool(
    "show_image", "Show an image with bounded16MiB input. A text-only model receives metadata with visible_to_you=false; use open_file for the Android viewer.",
    parameters = { fileSchema(listOf("path"), "path" to "string") }, needsApproval = { true }, execute = { input -> deviceToolParts(FileToolOperation.SHOW_IMAGE) { withContext(Dispatchers.IO) {
        val obj = requireNotNull(input as? JsonObject)
        val from = access.resolve(fileArgument(obj, "path"))
        val bytes = access.openInput(from).use { readFileBytes(it, LocalFileAccess.MAX_WRITE_BYTES + 1) }
        require(bytes.size <= LocalFileAccess.MAX_WRITE_BYTES) { "Изображение превышает16МиБ." }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "Файл не является изображением." }
        val metadata = buildJsonObject { put("success", true); put("path", from.reference); put("size_bytes", bytes.size)
            put("width", dimensions.outWidth); put("height", dimensions.outHeight); put("visible_to_you", modelCanReadImages) }
        if (modelCanReadImages) {
            val saved = access.createNewChild(access.resolve("/scratch"), ".shown-${UUID.randomUUID()}.img")
            try {
                access.write(saved, bytes, overwrite = true)
                // Normalize and bound the image exactly as file-backed provider inputs
                // do, while the owned scratch file is still available to the encoder.
                val encoded = UIMessagePart.Image("file://${(saved as LocalFileSource.Local).file.absolutePath}")
                    .encodeBase64().getOrThrow()
                currentCoroutineContext().ensureActive()
                listOf(UIMessagePart.Image(encoded.base64), UIMessagePart.Text(metadata.toString()))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    // Cleanup is best effort: a leftover temp copy must not discard the shown image
                    // or replace the real error.
                    runCatching { access.delete(saved) }.onFailure {
                        android.util.Log.w("FileManagerTools", "Temporary shown image was not removed", it)
                    }
                }
            }
        } else listOf(UIMessagePart.Text(metadata.toString()))
    } } })

private fun openFileTool(access: LocalFileAccess, suppliedContext: Context?): Tool = fileJsonTool(
    "open_file", "Open a guarded local file or granted SAF document in the Android viewer. Rikka-Root must be in the foreground.",
    fileSchema(listOf("path"), "path" to "string", "mime_type" to "string")) { obj ->
    val context = suppliedContext ?: access.androidContext
    val source = access.resolve(fileArgument(obj, "path"))
    require(access.exists(source) && !access.isDirectory(source)) { "Файл не найден." }
    val uri = when (source) {
        is LocalFileSource.Local -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFileForAndroidViewer(context, access, source))
        is LocalFileSource.Content -> source.uri
    }
    val name = access.name(source)
    val mime = obj.textArgument("mime_type") ?: context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    withContext(Dispatchers.Main.immediate) {
        require(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "Откройте Rikka-Root для запуска просмотра файла." }
        context.startActivity(intent)
    }
    buildJsonObject { put("success", true); put("path", source.reference); put("mime", mime) }
}
