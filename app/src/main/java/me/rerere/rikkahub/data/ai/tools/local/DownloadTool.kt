// Adapted from ExTV/rikkahub-agent, local/DownloadTool.kt (AGPL v3).
// Modified 2026-10-08: bounded, cancellable HTTP and scoped/explicit-grant destinations.
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024
// Same folder the assistant is told to prefer (ASSISTANT_FILE_DIRECTORY_HINT).
private const val PUBLIC_DOWNLOAD_FOLDER = "Rikka"

internal fun validatedDownloadUrl(value: String): String {
    require(value.length in 1..8192) { "Недопустимый адрес загрузки." }
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Недопустимый адрес загрузки.") }
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Разрешены только HTTP(S) адреса без встроенного логина и пароля."
    }
    return value
}

internal fun validatedDownloadFilename(value: String): String {
    require(value.isNotBlank() && value.length <= 127 && !value.startsWith('.') &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }) { "Недопустимое имя файла." }
    return value
}

/** A URL path such as `/releases/latest` may be long, percent-encoded or lack an extension. */
internal fun downloadNameFromUrl(url: String): String? {
    val last = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/')
        ?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        ?.filterNot { it == '/' || it == '\\' || it.isISOControl() }
        ?.takeIf { it.isNotBlank() && !it.startsWith('.') } ?: return null
    return shortenDownloadName(last)
}

private fun shortenDownloadName(name: String): String {
    if (name.length <= 100) return name
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..10 }?.let { ".$it" }.orEmpty()
    return name.take(100 - extension.length) + extension
}

/** Adds the extension the server declared (`latest` + application/json → `latest.json`). */
internal fun downloadNameWithExtension(name: String, mime: String?): String {
    if (name.contains('.') || mime == null) return name
    val extension = when (mime) {
        "application/json" -> "json"
        "text/plain" -> "txt"
        else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    } ?: return name
    return "$name.$extension"
}

/** `name (2).ext`, `name (3).ext`…: a repeated download never fails or replaces a file. */
internal fun numberedDownloadName(name: String, attempt: Int): String {
    if (attempt <= 1) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return name.substring(0, dot) + " ($attempt)" + name.substring(dot)
}

internal fun validatedDownloadLimit(value: Long): Long {
    require(value in 1..MAX_DOWNLOAD_BYTES) { "Размер должен быть от 1 до $MAX_DOWNLOAD_BYTES байт." }
    return value
}

/** Checks unknown-length and chunked bodies before writing a chunk past the limit. */
internal fun copyDownloadBounded(input: InputStream, output: OutputStream, maxBytes: Long, checkCancelled: () -> Unit): Long {
    validatedDownloadLimit(maxBytes)
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count == -1) return total
        if (count == 0) continue
        checkCancelled()
        if (count > maxBytes - total) throw IOException("Загрузка превышает ограничение размера.")
        output.write(buffer, 0, count)
        total += count
    }
}

private val downloadHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).followSslRedirects(false).build()
}

/** Cancellation closes the network socket; the callback owns and closes its file/body streams. */
private suspend fun downloadToFile(url: String, staging: File, maxBytes: Long): Pair<Long, String?> =
    suspendCancellableCoroutine { continuation ->
        val call = downloadHttpClient.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    val reason = when (e) {
                        is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                            "Сервер не ответил вовремя. Проверьте подключение к интернету и повторите загрузку."
                        is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException ->
                            deviceToolFailureReason(e)
                        else -> "Не удалось загрузить файл. Проверьте подключение к интернету и адрес, затем повторите попытку."
                    }
                    continuation.resumeWithException(IOException(reason, e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var handedOff = false
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!response.isSuccessful) throw IOException(when (response.code) {
                            401 -> "Сервер требует авторизацию (HTTP 401). Проверьте адрес и доступ к файлу; этот инструмент не передаёт учётные данные."
                            403 -> "Сервер запретил загрузку (HTTP 403). Проверьте доступ и ограничение частоты запросов GitHub API; попробуйте позже."
                            404 -> "Файл не найден (HTTP 404). Проверьте адрес: у репозитория может не быть опубликованного релиза."
                            429 -> "Слишком много запросов (HTTP 429). Подождите и повторите загрузку."
                            in 500..599 -> "Сервер временно недоступен (HTTP ${response.code}). Повторите загрузку позже."
                            in 300..399 -> "Сервер перенаправляет запрос (HTTP ${response.code}). Проверьте конечный адрес HTTPS и загрузите файл по нему."
                            else -> "Сервер отклонил загрузку (HTTP ${response.code}). Проверьте адрес и доступ к файлу."
                        })
                        val body = response.body
                        val expected = body.contentLength()
                        if (expected > maxBytes) throw IOException("Загрузка превышает ограничение размера.")
                        val count = body.byteStream().use { input ->
                            staging.outputStream().use { output ->
                                copyDownloadBounded(input, output, maxBytes) {
                                    if (!continuation.isActive) throw CancellationException()
                                }
                            }
                        }
                        if (expected >= 0 && count != expected) throw IOException("Сервер вернул неполный файл.")
                        if (continuation.isActive) {
                            handedOff = true
                            continuation.resume(count to body.contentType()?.let { "${it.type}/${it.subtype}" })
                        }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    // The network callback may open a stream concurrently with cancellation.
                    if (!handedOff) staging.delete()
                }
            }
        })
    }

fun downloadTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "download_file",
    description = "Download an HTTP(S) file and wait for completion (90-second timeout; at most 128 MiB). " +
        "By default save to public Downloads/Rikka. Optional directory must be an allowed workspace/scratch directory " +
        "or a user-granted SAF directory or explicitly enabled shared storage. Existing files are never overwritten: a taken name gets a number, " +
        "e.g. `file (2).json`, and the result's filename/destination give the actual name. overwrite: true replaces a file only in /scratch or the workspace. " +
        "Returns destination and bytes; cancelled/failed downloads are removed where safe. Interrupted direct shared-storage writes may leave a partial file, reported in created_path." + ASSISTANT_FILE_DIRECTORY_HINT +
        " Для проверки обновлений root-модулей используйте URL из updateJson в module.prop; " +
        "при необходимости — https://api.github.com/repos/<владелец>/<репо>/releases/latest вместо HTML GitHub. " +
        "Сохраните JSON в /scratch (directory: /scratch, overwrite: true для повторной проверки) и прочитайте через read_file по имени из ответа; загрузка не устанавливает обновление.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject { put("type", "string"); put("description", "HTTP(S) file URL") })
            put("filename", buildJsonObject { put("type", "string"); put("description", "Optional plain filename") })
            put("directory", buildJsonObject { put("type", "string"); put("description", "Optional allowed directory; omitted means public Downloads") })
            put("max_bytes", buildJsonObject { put("type", "integer"); put("description", "Optional byte limit, at most 134217728") })
            put("overwrite", buildJsonObject { put("type", "boolean"); put("description", "Replace an existing file with the same name; only in /scratch or the workspace (default false)") })
        }, required = listOf("url"))
    },
    needsApproval = { true },
    execute = { arguments -> deviceToolResult {
        withContext(Dispatchers.IO) {
            val params = arguments.jsonObject
            val url = validatedDownloadUrl(params.textArgument("url") ?: error("Укажите адрес файла в url."))
            val explicitName = params.textArgument("filename")?.let(::validatedDownloadFilename)
            val baseName = explicitName ?: validatedDownloadFilename(downloadNameFromUrl(url) ?: "download_${System.currentTimeMillis()}")
            val maxBytes = validatedDownloadLimit(if (params.containsKey("max_bytes"))
                params["max_bytes"]?.jsonPrimitive?.longOrNull ?: error("Укажите max_bytes целым числом.") else MAX_DOWNLOAD_BYTES)
            val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull == true
            val directory = params.textArgument("directory")?.let { access.resolve(it) }
            if (directory != null) {
                require(access.isDirectory(directory)) { "Назначение должно быть папкой." }
                require(!overwrite || directory is LocalFileSource.Local && access.permitsAutomaticRollback(directory)) {
                    "overwrite разрешён только в /scratch и workspace. Для общей памяти файл получит новое имя автоматически."
                }
            } else if (overwrite) {
                error("overwrite разрешён только в /scratch и workspace. Без directory файл сохраняется в «Загрузки» под свободным именем.")
            } else if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return@withContext deviceToolError("На Android 8–9 для общей папки «Загрузки» нужно разрешение записи либо выбранная SAF-папка.", Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            val staging = File.createTempFile("tool-download-", ".partial", context.cacheDir)
            try {
                val (bytes, mime) = downloadToFile(url, staging, maxBytes)
                coroutineContext.ensureActive()
                val wantedName = if (explicitName != null) baseName else validatedDownloadFilename(downloadNameWithExtension(baseName, mime))
                var name = wantedName
                val destination = if (directory != null) {
                    // Checked after the network wait, so a newly created user file is not overwritten.
                    val existing = access.findChild(directory, wantedName)
                    if (existing != null && overwrite) {
                        require(!access.isDirectory(existing)) { "С таким именем уже есть папка. Укажите другое filename." }
                        access.delete(existing)
                    } else if (existing != null) {
                        name = (2..100).asSequence().map { numberedDownloadName(wantedName, it) }
                            .firstOrNull { access.findChild(directory, it) == null }
                            ?: error("Слишком много файлов с таким именем. Укажите другое filename.")
                    }
                    // Keep ownership of the newly created document even if cancellation occurs
                    // during provider IPC, before entering the cleanup-protected copy.
                    val unpublishedShared = !access.permitsAutomaticRollback(directory)
                    val target = if (unpublishedShared) access.child(directory, name)
                        else withContext(NonCancellable) { access.createFile(directory, name) }
                    try {
                        val jobContext = coroutineContext
                        if (unpublishedShared) {
                            val local = target as? LocalFileSource.Local ?: error("Не удалось определить путь назначения.")
                            installNewSharedStorageFile(staging, local.file, verifyAccess = { access.checkFile(local.file, true) })
                        } else access.openOutput(target).use { output ->
                            staging.inputStream().use { input -> copyDownloadBounded(input, output, maxBytes) { jobContext.ensureActive() } }
                        }
                        coroutineContext.ensureActive()
                        target.reference
                    } catch (failure: Exception) {
                        if (unpublishedShared) {
                            if (failure is FileCreationCleanupFailedException) return@withContext buildJsonObject {
                                put("error", "creation_cleanup_failed")
                                put("created_path", failure.created.reference)
                                put("detail", failure.message.orEmpty())
                            }
                            throw failure
                        }
                        try { access.cleanupOwnedTarget(target, failure) }
                        catch (partial: FileCreationCleanupFailedException) {
                            return@withContext buildJsonObject {
                                put("error", "creation_cleanup_failed")
                                put("created_path", partial.created.reference)
                                put("detail", partial.message.orEmpty())
                            }
                        }
                        throw failure
                    }
                } else publishPublicDownload(context, staging, wantedName, mime, maxBytes).also { name = it.second }.first
                buildJsonObject {
                    put("success", true); put("filename", name); put("destination", destination); put("bytes", bytes)
                    if (name != wantedName) put("message", "Файл $wantedName уже был, сохранено как $name.")
                }
            } finally {
                staging.delete()
            }
        }
    } }
)

/** Returns the destination and the actual name: MediaStore renames a taken name itself. */
private suspend fun publishPublicDownload(context: Context, staging: File, name: String, mime: String?, maxBytes: Long): Pair<String, String> {
    val jobContext = coroutineContext
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$PUBLIC_DOWNLOAD_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать файл в «Загрузках».")
        var completed = false
        try {
            (resolver.openOutputStream(uri, "w") ?: error("Не удалось открыть файл.")).use { output ->
                staging.inputStream().use { input -> copyDownloadBounded(input, output, maxBytes) { jobContext.ensureActive() } }
            }
            jobContext.ensureActive()
            require(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            completed = true
            val actual = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: name
            return uri.toString() to actual
        } finally {
            if (!completed) resolver.delete(uri, null, null)
        }
    }
    @Suppress("DEPRECATION")
    val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
    val directory = File(downloadsRoot, PUBLIC_DOWNLOAD_FOLDER)
    require(directory.canonicalFile.parentFile == downloadsRoot) { "Папка загрузок ведёт за пределы Downloads." }
    require(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку загрузок." }
    // createNewFile reserves our name; cleanup can never remove an existing user file.
    val target = (1..100).asSequence().map { File(directory, numberedDownloadName(name, it)) }
        .firstOrNull { it.createNewFile() } ?: error("Слишком много файлов с таким именем. Укажите другое filename.")
    var completed = false
    try {
        target.outputStream().use { output ->
            staging.inputStream().use { input -> copyDownloadBounded(input, output, maxBytes) { jobContext.ensureActive() } }
        }
        jobContext.ensureActive()
        completed = true
        return target.absolutePath to target.name
    } finally {
        if (!completed) target.delete()
    }
}
