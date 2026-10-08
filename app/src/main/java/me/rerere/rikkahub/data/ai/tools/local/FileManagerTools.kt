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
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
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
    parameters = { schema }, needsApproval = { true }, execute = { input -> deviceToolResult {
        withContext(Dispatchers.IO) {
            try { execute(requireNotNull(input as? JsonObject) { "Параметры должны быть объектом." }) }
            catch (cleanup: FileCreationCleanupFailedException) { buildJsonObject {
                put("error", "creation_cleanup_failed"); put("created_path", cleanup.created.reference)
                put("detail", "Android не разрешил убрать созданный документ. Проверьте его в выбранной папке.")
            } }
            catch (_: FileMoveSourceDeleteException) { buildJsonObject {
                put("error", "source_delete_failed"); put("detail", "Копия сохранена, но источник не удалось полностью удалить. Назначение сохранено для восстановления.")
            } }
            catch (recovery: FileRestoreFailedException) { buildJsonObject {
                put("error", "restore_failed")
                put("detail", "Android не смог восстановить предыдущий файл. Резервная копия сохранена в scratch.")
                put("backup_path", "/scratch/${recovery.backup.name}")
            } }
        }
    } })

/** Every tool uses this immutable conversation-scoped resolver; no operation invokes a shell. */
fun fileManagerTools(access: LocalFileAccess, context: Context? = null, modelCanReadImages: Boolean = false): List<Tool> {
    val scope = " Paths: ${access.defaultPath}, /scratch, /upload, attachment:N for reads, and content:// under a persisted SAF tree. System paths and app settings are blocked."
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
    suspend fun listing(obj: JsonObject, find: Boolean): JsonObject {
        val root = access.resolve(fileArgument(obj, if (find) "root" else "path"))
        val query = if (find) fileArgument(obj, "query") else obj.textArgument("pattern")
        val regex = query?.takeIf { !find || '*' in it || '?' in it }?.let(::fileGlobRegex)
        val all = access.walk(root, fileBoolean(obj, "recursive", find))
        val limit = fileInteger(obj, "limit", 50, 1..500)
        val matches = all.filter { val name = access.name(it); if (regex != null) regex.matches(name) else query == null || name.contains(query, ignoreCase = true) }
        val selected = matches.take(limit).map { entry(it) }
        return buildJsonObject { put("files", JsonArray(selected)); put("truncated", matches.size > limit) }
    }
    suspend fun batch(obj: JsonObject, operation: String): JsonObject {
        val explicit = obj["paths"] as? JsonArray
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
                    var owned: LocalFileSource? = null
                    val directory = access.isDirectory(from)
                    val to = if (previous != null) previous else withContext(kotlinx.coroutines.NonCancellable) {
                        access.createNewChild(destination, name, directory).also { owned = it }
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (operation == "copy") access.copy(from, to, overwrite || previous == null)
                        else access.move(from, to, overwrite || previous == null)
                    } catch (error: Exception) {
                        if (owned != null && !isMoveCopyPreserved(error)) withContext(kotlinx.coroutines.NonCancellable) { runCatching { access.delete(to, recursive = true) } }
                        throw error
                    }
                }
                succeeded++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failures += buildJsonObject {
                put("path", path)
                put("error", if (isMoveCopyPreserved(error)) "Копия сохранена, но источник не удалось полностью удалить." else "Не удалось выполнить операцию. Проверьте путь, доступ и существующее назначение.")
                if (error is FileRestoreFailedException) put("backup_path", "/scratch/${error.backup.name}")
                if (error is FileCreationCleanupFailedException) put("created_path", error.created.reference)
            } }
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
        buildJsonObject {
            put("bytes_read", data.size); put("truncated", bytes.size > limit)
            if (binary && charset !in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) { put("binary", true); put("content_base64", Base64.getEncoder().encodeToString(data)) }
            else {
                val utf16Bom = data.size >= 2 && ((data[0] == 0xff.toByte() && data[1] == 0xfe.toByte()) || (data[0] == 0xfe.toByte() && data[1] == 0xff.toByte()))
                val offset = if (bom) 3 else if (utf16Bom) 2 else 0
                put("content", String(data, offset, data.size - offset, charset)); put("encoding", charset.name())
            }
        }
    }
    tools += fileJsonTool("write_binary_file", "Write base64_content, up to16MiB decoded. overwrite=false refuses an existing file. Local writes are staged before replacement." + scope,
        fileSchema(listOf("path", "base64_content"), "path" to "string", "base64_content" to "string", "overwrite" to "boolean")) { obj ->
        val encoded = fileArgument(obj, "base64_content")
        require(encoded.length <= (LocalFileAccess.MAX_WRITE_BYTES * 4L / 3L + 8)) { "Данные слишком велики." }
        val destination = source(obj, write = true)
        val bytes = access.write(destination, Base64.getDecoder().decode(encoded), fileBoolean(obj, "overwrite"))
        buildJsonObject { put("success", true); put("path", destination.reference); put("bytes_written", bytes) }
    }
    tools += fileJsonTool("delete_file", "Delete a file or directory; recursive=true required for nonempty folders. Roots cannot be deleted." + scope,
        fileSchema(listOf("path"), "path" to "string", "recursive" to "boolean")) { obj ->
        val from = source(obj, write = true)
        val count = if (access.isDirectory(from)) access.walk(from).size + 1 else 1
        val deleted = access.delete(from, fileBoolean(obj, "recursive"))
        buildJsonObject { put("success", deleted); put("path", from.reference); put("deleted_count", if (deleted) count else 0) }
    }
    for (operation in listOf("copy", "move")) tools += fileJsonTool("${operation}_file", "${operation.replaceFirstChar(Char::uppercase)} a file or directory from src to dst. overwrite=false. Copies are limited to512MiB." + scope,
        fileSchema(listOf("src", "dst"), "src" to "string", "dst" to "string", "overwrite" to "boolean")) { obj ->
        val from = source(obj, "src", operation == "move")
        val destination = source(obj, "dst", true)
        val bytes = if (operation == "copy") access.copy(from, destination, fileBoolean(obj, "overwrite")) else access.move(from, destination, fileBoolean(obj, "overwrite"))
        buildJsonObject { put("success", true); put("from", from.reference); put("to", destination.reference); put("bytes_copied", bytes) }
    }
    tools += fileJsonTool("create_directory", "Create a local directory with parents. For SAF, path is an existing parent tree/document and name is the new direct directory name." + scope,
        fileSchema(listOf("path"), "path" to "string", "name" to "string")) { obj ->
        val parent = source(obj)
        val existing = access.exists(parent)
        val destination = if (parent is LocalFileSource.Content) access.child(parent, fileArgument(obj, "name"), true) else parent
        val created = access.createDirectory(destination)
        buildJsonObject { put("success", created); put("path", destination.reference); put("created", !existing || destination != parent) }
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
    parameters = { fileSchema(listOf("path"), "path" to "string") }, needsApproval = { true }, execute = { input -> deviceToolParts { withContext(Dispatchers.IO) {
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
            val saved = access.resolve("/scratch/.shown-${UUID.randomUUID()}.img", true)
            access.write(saved, bytes)
            listOf(UIMessagePart.Image("file://${(saved as LocalFileSource.Local).file.absolutePath}"), UIMessagePart.Text(metadata.toString()))
        } else listOf(UIMessagePart.Text(metadata.toString()))
    } } })

private fun openFileTool(access: LocalFileAccess, suppliedContext: Context?): Tool = fileJsonTool(
    "open_file", "Open a guarded local file or granted SAF document in the Android viewer. Rikka-Root must be in the foreground.",
    fileSchema(listOf("path"), "path" to "string", "mime_type" to "string")) { obj ->
    val context = suppliedContext ?: access.androidContext
    val source = access.resolve(fileArgument(obj, "path"))
    require(access.exists(source) && !access.isDirectory(source)) { "Файл не найден." }
    val uri = when (source) {
        is LocalFileSource.Local -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", access.checkFile(source.file))
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
