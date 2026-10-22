package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore? = null,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> =
        importChatFiles(uris).map { it.localUri }

    fun importChatFiles(uris: List<Uri>): List<ImportedChatFile> = importChatFilesInternal(uris)

    /** Copies under the caller's lifetime; no managed records are published for cancelled imports. */
    suspend fun importChatFilesAsync(uris: List<Uri>): List<ImportedChatFile> {
        val created = mutableListOf<File>()
        return try {
            val imported = withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                importChatFilesInternal(uris, { coroutine.ensureActive() }, created, track = false)
            }
            // There is no suspension between metadata registration and handing the files to UI.
            imported.forEach { file ->
                trackManagedFile(FileFolders.UPLOAD, file.localUri.toFile(), file.displayName, file.mimeType)
            }
            imported
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } }
            throw cancelled
        }
    }

    private fun importChatFilesInternal(
        uris: List<Uri>,
        checkActive: () -> Unit = {},
        created: MutableList<File>? = null,
        track: Boolean = true,
    ): List<ImportedChatFile> {
        val importedFiles = mutableListOf<ImportedChatFile>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) dir.mkdirs()
        uris.forEach { uri ->
            var target: File? = null
            try {
                checkActive()
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                checkActive()
                val file = dir.resolve(buildUuidFileName(displayName = sourceName, mimeType = sourceMime))
                target = file
                if (!file.exists()) file.createNewFile()
                created?.add(file)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Не удалось открыть поток вложения.")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            checkActive()
                            output.write(buffer, 0, size)
                        }
                    }
                }
                checkActive()
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                if (track) trackManagedFile(FileFolders.UPLOAD, file, sourceName, guessedMime)
                importedFiles.add(ImportedChatFile(uri, file.toUri(), sourceName, guessedMime))
            } catch (error: Exception) {
                target?.delete()
                if (error is CancellationException) throw error
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", error)
                Logging.log(TAG, "createChatFilesByContents: Failed to save file from $uri ${error.message} | ${error.stackTraceToString()}")
            }
        }
        return importedFiles
    }

    fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        displayName: String = "image.png",
        mimeType: String = "image/png",
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = displayName, mimeType = mimeType)
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = displayName,
                mimeType = mimeType
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val url = saveBase64Image(part.url)
                                if (url == null) {
                                    Log.w(TAG, "convertBase64ImagePartToLocalFile: undecodable base64 image kept inline")
                                    part
                                } else {
                                    Log.i(TAG, "convertBase64ImagePartToLocalFile: convert base64 img to $url")
                                    part.copy(url = url.toString())
                                }
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    /**
     * Keeps original encoded bytes and metadata, including formats recognized by Android
     * through a bounds-only inspection. Never allocates a full-resolution bitmap.
     */
    private fun saveBase64Image(dataUrl: String): Uri? {
        val bytes = runCatching { Base64.Mime.decode(dataUrl.substringAfter("base64,")) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val sniffed = FileUtils.sniffImageMimeType(bytes)
        if (sniffed != null) {
            val extension = sniffed.substringAfter('/')
            return createChatFilesByByteArrays(listOf(bytes), displayName = "image.$extension", mimeType = sniffed).first()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") } ?: return null
        return createChatFilesByByteArrays(listOf(bytes), displayName = "image.${mime.substringAfter('/')}", mimeType = mime).first()
    }

    fun deleteChatFiles(uris: List<Uri>) {
        val retained = settingsFileReferences() ?: return
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") && it.toString() !in retained }.forEach { uri ->
            val file = uri.toFile()
            getRelativePathInFilesDir(file)?.let { relativePaths.add(it) }
            if (file.exists()) {
                file.delete()
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                removed += repository.deleteByPath(entity.relativePath)
            }
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk) {
            runCatching { getFile(entity).delete() }
        }
        repository.deleteById(id) > 0
    }

    /** [isReferenced] keeps files a chat still shows; a failing check keeps the file. */
    suspend fun deleteAll(
        folder: String = FileFolders.UPLOAD,
        isReferenced: suspend (String) -> Boolean = { false },
    ): Boolean = withContext(Dispatchers.IO) {
        val retained = settingsFileReferences() ?: return@withContext false
        val dir = File(context.filesDir, folder)
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }

        var allDeletedFromDisk = true
        val protected = entries.orEmpty().filter { entry ->
            val url = entry.toUri().toString()
            retained.any { it == url || it.startsWith(url.trimEnd('/') + '/') } || referencedByChat(url, isReferenced)
        }.toSet()
        entries.orEmpty().filterNot { it in protected }.forEach { entry ->
            if (!runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                allDeletedFromDisk = false
            }
        }

        if (allDeletedFromDisk && protected.isEmpty()) {
            repository.deleteByFolder(folder)
            return@withContext true
        }

        repository.listByFolder(folder).first().forEach { entity ->
            if (!getFile(entity).exists()) {
                repository.deleteById(entity.id)
            }
        }
        allDeletedFromDisk
    }

    suspend fun deleteOlderThan(
        folder: String = FileFolders.UPLOAD,
        cutoffMillis: Long,
        isReferenced: suspend (String) -> Boolean = { false },
    ): Boolean = withContext(Dispatchers.IO) {
        val retained = settingsFileReferences() ?: return@withContext false
        var allDeleted = true
        repository.listByFolder(folder).first()
            .filter { it.createdAt < cutoffMillis && getFile(it).toUri().toString() !in retained }
            .filterNot { referencedByChat(getFile(it).toUri().toString(), isReferenced) }
            .forEach { entity ->
                val file = getFile(entity)
                val deletedFromDisk = !file.exists() || runCatching {
                    file.deleteRecursively()
                }.getOrDefault(false)

                if (deletedFromDisk) {
                    if (repository.deleteById(entity.id) == 0) {
                        allDeleted = false
                    }
                } else {
                    allDeleted = false
                }
            }
        allDeleted
    }

    private suspend fun referencedByChat(url: String, isReferenced: suspend (String) -> Boolean): Boolean = try {
        isReferenced(url)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    private fun settingsFileReferences(): Set<String>? {
        val settings = settingsStore?.settingsFlow?.value ?: return emptySet()
        return if (settings.init) null else settings.localFileUrls(context.filesDir)
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

data class ImportedChatFile(
    val sourceUri: Uri,
    val localUri: Uri,
    val displayName: String,
    val mimeType: String,
)

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    const val BUILTIN_SKILLS = "builtin_skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
    const val MEDIA_CREATION = "media_creation"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
