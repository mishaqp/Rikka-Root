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
                if (continuation.isActive) continuation.resumeWithException(IOException("Не удалось загрузить файл."))
            }

            override fun onResponse(call: Call, response: Response) {
                var handedOff = false
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!response.isSuccessful) throw IOException("Сервер отклонил загрузку (HTTP ${response.code}).")
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
        "By default save to public Downloads. Optional directory must be an allowed workspace/scratch directory " +
        "or a user-granted SAF directory. Existing files are never overwritten. Returns destination and bytes; cancelled/failed downloads are removed.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject { put("type", "string"); put("description", "HTTP(S) file URL") })
            put("filename", buildJsonObject { put("type", "string"); put("description", "Optional plain filename") })
            put("directory", buildJsonObject { put("type", "string"); put("description", "Optional allowed directory; omitted means public Downloads") })
            put("max_bytes", buildJsonObject { put("type", "integer"); put("description", "Optional byte limit, at most 134217728") })
        }, required = listOf("url"))
    },
    needsApproval = { true },
    execute = { arguments -> deviceToolResult {
        withContext(Dispatchers.IO) {
            val params = arguments.jsonObject
            val url = validatedDownloadUrl(params.textArgument("url") ?: error("url is required"))
            val name = validatedDownloadFilename(params.textArgument("filename") ?: run {
                URI(url).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() && !it.startsWith('.') }
                    ?: "download_${System.currentTimeMillis()}"
            })
            val maxBytes = validatedDownloadLimit(if (params.containsKey("max_bytes"))
                params["max_bytes"]?.jsonPrimitive?.longOrNull ?: error("max_bytes must be an integer") else MAX_DOWNLOAD_BYTES)
            val directory = params.textArgument("directory")?.let { access.resolve(it) }
            if (directory != null) {
                require(access.isDirectory(directory)) { "Назначение должно быть папкой." }
                require(access.findChild(directory, name) == null) { "Файл с таким именем уже существует." }
            } else if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return@withContext deviceToolError("На Android 8–9 для общей папки «Загрузки» нужно разрешение записи либо выбранная SAF-папка.", Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            val staging = File.createTempFile("tool-download-", ".partial", context.cacheDir)
            try {
                val (bytes, mime) = downloadToFile(url, staging, maxBytes)
                coroutineContext.ensureActive()
                val destination = if (directory != null) {
                    // Recheck after the network wait, so a newly created user file is not overwritten.
                    require(access.findChild(directory, name) == null) { "Файл с таким именем уже существует." }
                    // Keep ownership of the newly created document even if cancellation occurs
                    // during provider IPC, before entering the cleanup-protected copy.
                    val target = withContext(NonCancellable) { access.createFile(directory, name) }
                    var completed = false
                    try {
                        val jobContext = coroutineContext
                        access.openOutput(target).use { output ->
                            staging.inputStream().use { input -> copyDownloadBounded(input, output, maxBytes) { jobContext.ensureActive() } }
                        }
                        coroutineContext.ensureActive()
                        completed = true
                        target.reference
                    } finally {
                        if (!completed) withContext(NonCancellable) { access.delete(target) }
                    }
                } else publishPublicDownload(context, staging, name, mime, maxBytes)
                buildJsonObject { put("success", true); put("filename", name); put("destination", destination); put("bytes", bytes) }
            } finally {
                staging.delete()
            }
        }
    } }
)

private suspend fun publishPublicDownload(context: Context, staging: File, name: String, mime: String?, maxBytes: Long): String {
    val jobContext = coroutineContext
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rikka-Root")
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
            return uri.toString()
        } finally {
            if (!completed) resolver.delete(uri, null, null)
        }
    }
    @Suppress("DEPRECATION")
    val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
    val directory = File(downloadsRoot, "Rikka-Root")
    require(directory.canonicalFile.parentFile == downloadsRoot) { "Папка загрузок ведёт за пределы Downloads." }
    require(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку загрузок." }
    val target = File(directory, name)
    // createNewFile reserves our name; cleanup can never remove an existing user file.
    require(target.createNewFile()) { "Файл с таким именем уже существует." }
    var completed = false
    try {
        target.outputStream().use { output ->
            staging.inputStream().use { input -> copyDownloadBounded(input, output, maxBytes) { jobContext.ensureActive() } }
        }
        jobContext.ensureActive()
        completed = true
        return target.absolutePath
    } finally {
        if (!completed) target.delete()
    }
}
