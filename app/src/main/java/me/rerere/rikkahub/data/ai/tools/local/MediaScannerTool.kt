// Adapted from ExTV/rikkahub-agent, local/MediaScannerTool.kt (AGPL v3).
// Modified 2026-10-08: granted shared-storage documents, guarded paths and cancellation cleanup.
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun mediaScannerTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "scan_media",
    description = "Ask Android to index media files and wait for results. Accepts guarded local files and user-granted " +
        "SAF documents from Android's external-storage provider on a known primary or removable storage volume. " +
        "Cloud/other document providers have no supported scanner path. Android may reject app-private media.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("paths", buildJsonObject {
                put("type", "array"); put("description", "1–64 allowed local paths or granted external-storage document URIs")
                put("items", buildJsonObject { put("type", "string") })
            })
        }, required = listOf("paths"))
    },
    needsApproval = { true },
    execute = { arguments -> deviceToolResult {
        val entries = arguments.jsonObject["paths"] as? JsonArray ?: error("paths is required")
        require(entries.size in 1..64) { "Укажите от 1 до 64 файлов." }
        val paths = entries.map { entry ->
            val reference = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Путь должен быть строкой.")
            when (val source = access.resolve(reference)) {
                is LocalFileSource.Local -> access.checkFile(source.file).also { require(it.isFile) { "Путь должен вести к существующему файлу." } }.absolutePath
                is LocalFileSource.Content -> {
                    if (source.uri.authority != "com.android.externalstorage.documents") {
                        return@deviceToolResult deviceToolError(
                            "У этого поставщика документов нет поддерживаемого локального пути для медиасканера. Выберите файл в общей памяти или на SD-карте через SAF.")
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val documentUri = if (DocumentsContract.isDocumentUri(context, source.uri)) source.uri
                            else DocumentsContract.buildDocumentUriUsingTree(source.uri, DocumentsContract.getTreeDocumentId(source.uri))
                            val mapped = externalStorageMediaScanPath(DocumentsContract.getDocumentId(documentUri), scannerVolumeRoots(context))
                            require(!access.isDirectory(source)) { "Для сканирования нужен файл, а не папка." }
                            // A URI grant can open the document while direct Linux path reads are denied.
                            // Do not require File.isFile/canRead or a broad READ_MEDIA permission.
                            requireNotNull(context.contentResolver.openFileDescriptor(documentUri, "r")) {
                                "Не удалось открыть выбранный документ."
                            }.use { }
                            mapped.absolutePath
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@deviceToolResult deviceToolError(
                            "Не удалось получить безопасный путь к выбранному медиафайлу. Проверьте SAF-доступ, подключение накопителя и отсутствие ссылок или '..' в пути.")
                    }
                }
            }
        }.distinct()
        val results = withTimeoutOrNull(15_000) { scanGuardedFiles(context, paths) }
            ?: return@deviceToolResult deviceToolError("Android не завершил сканирование за 15 секунд.")
        buildJsonObject {
            put("success", results.values.all { it != null })
            put("scanned", results.values.count { it != null })
            put("results", buildJsonArray {
                results.forEach { (path, uri) -> add(buildJsonObject {
                    put("path", path); put("indexed", uri != null)
                    uri?.let { put("uri", it.toString()) }
                }) }
            })
        }
    } }
)

private val removableScannerVolume = Regex("^[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$")
private val fullScannerVolume = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun validScannerVolume(volume: String): Boolean =
    volume == "primary" || removableScannerVolume.matches(volume) || fullScannerVolume.matches(volume)

/** Maps an already-granted, decoded Android document ID; never decodes path components twice. */
internal fun externalStorageMediaScanPath(documentId: String, volumeRoots: Map<String, File>): File {
    require(documentId.length in 1..4096 && documentId.none(Char::isISOControl)) { "Некорректный идентификатор документа." }
    val separator = documentId.indexOf(':')
    require(separator > 0) { "Неизвестный формат документа." }
    val volume = documentId.substring(0, separator).lowercase(Locale.ROOT)
    require(validScannerVolume(volume)) { "Неизвестный формат накопителя." }
    val relative = documentId.substring(separator + 1)
    val components = relative.split('/')
    require(relative.isNotEmpty() && relative.none { it == '\\' || it.isISOControl() } &&
        components.all { it.isNotEmpty() && it != "." && it != ".." }) { "Некорректный путь документа." }
    val trustedRoot = volumeRoots.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == volume }?.value
        ?: throw IllegalArgumentException("Накопитель недоступен.")
    // /sdcard may itself be a trusted system alias; descendants cannot introduce aliases.
    val root = trustedRoot.canonicalFile
    require(root.toPath().parent != null) { "Корень файловой системы не является накопителем." }
    var target = root
    components.forEach { component ->
        target = File(target, component)
        require(!Files.isSymbolicLink(target.toPath())) { "Ссылки внутри пути документа недоступны." }
    }
    val canonical = target.canonicalFile
    require(canonical != root && canonical.toPath().startsWith(root.toPath())) { "Документ за пределами накопителя." }
    return canonical
}

/** Uses actual Android volumes; API 26–29 fallback interpolates only the narrow FAT UUID shape. */
private fun scannerVolumeRoots(context: Context): Map<String, File> {
    val manager = context.getSystemService(StorageManager::class.java)
        ?: throw IllegalStateException("Список накопителей недоступен.")
    val roots = linkedMapOf<String, File>()
    if (Build.VERSION.SDK_INT < 30) {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        roots["primary"] = primary
    }
    manager.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }.forEach { volume ->
        val id = if (volume.isPrimary) "primary" else volume.uuid?.lowercase(Locale.ROOT) ?: return@forEach
        if (!validScannerVolume(id)) return@forEach
        if (Build.VERSION.SDK_INT >= 30) volume.directory?.let { roots[id] = it }
        else if (!volume.isPrimary && removableScannerVolume.matches(id)) {
            // Use the platform UUID's spelling for the actual Linux mount point.
            roots[id] = File("/storage", volume.uuid!!)
        }
    }
    return roots
}

private suspend fun scanGuardedFiles(context: Context, paths: List<String>): Map<String, Uri?> =
    suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val result = linkedMapOf<String, Uri?>()
        lateinit var connection: MediaScannerConnection
        fun close() { synchronized(result) { if (finished.compareAndSet(false, true)) connection.disconnect() } }
        connection = MediaScannerConnection(context.applicationContext, object : MediaScannerConnection.MediaScannerConnectionClient {
            override fun onMediaScannerConnected() {
                if (!continuation.isActive) { close(); return }
                try { paths.forEach { if (continuation.isActive) connection.scanFile(it, null) } }
                catch (error: Exception) {
                    close()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }

            override fun onScanCompleted(path: String?, uri: Uri?) {
                synchronized(result) {
                    if (!continuation.isActive || path !in paths) return
                    result[path!!] = uri
                    if (result.size == paths.size) {
                        close()
                        if (continuation.isActive) continuation.resume(result.toMap())
                    }
                }
            }
        })
        continuation.invokeOnCancellation { close() }
        try { synchronized(result) { if (continuation.isActive && !finished.get()) connection.connect() } }
        catch (error: Exception) { close(); if (continuation.isActive) continuation.resumeWithException(error) }
    }
