package me.rerere.rikkahub.ui.components.message

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

internal data class ProducedFileExportRequest(
    val source: ProducedFileSource,
    val workspaceId: String?,
)

internal suspend fun Context.exportProducedFile(
    request: ProducedFileExportRequest,
    destination: Uri,
    repository: WorkspaceRepository,
): Unit = withContext(Dispatchers.IO) {
    when (val source = request.source) {
        is ProducedFileSource.Workspace -> {
            val id = requireNotNull(request.workspaceId) { "Рабочая папка не выбрана." }
            val output = contentResolver.openOutputStream(destination)
                ?: throw FileNotFoundException("Хранилище не открыло файл для записи.")
            output.use { repository.exportRootfsFile(id, source.path, it) }
        }
        is ProducedFileSource.Document -> {
            val input = contentResolver.openInputStream(Uri.parse(source.uri))
                ?: throw FileNotFoundException("Хранилище не открыло исходный файл.")
            input.use {
                val output = contentResolver.openOutputStream(destination)
                    ?: throw FileNotFoundException("Хранилище не открыло файл для записи.")
                output.use { input.copyTo(it) }
            }
        }
    }
}

/** MIME metadata alone does not establish that a content URI still has a readable backing file. */
internal suspend fun Context.requireReadableProducedDocument(source: ProducedFileSource.Document) =
    withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(Uri.parse(source.uri))
            ?: throw FileNotFoundException("Хранилище не открыло исходный файл.")
        input.use { }
    }

/** A fresh snapshot keeps an already granted URI intact when another file has the same name. */
internal suspend fun Context.createProducedFileShareIntent(
    entry: ProducedFilePresentation,
    workspaceId: String?,
    repository: WorkspaceRepository,
): Intent = withContext(Dispatchers.IO) {
    val uri = when (val source = entry.source) {
        is ProducedFileSource.Document -> {
            requireReadableProducedDocument(source)
            val parsed = Uri.parse(source.uri)
            when (parsed.scheme) {
                "content" -> parsed
                "file" -> {
                    val file = parsed.toFile()
                    if (!file.isFile || !file.canRead()) throw FileNotFoundException("Исходный файл недоступен.")
                    FileProvider.getUriForFile(this@createProducedFileShareIntent, "$packageName.fileprovider", file)
                }
                else -> throw IllegalArgumentException("Неподдерживаемый адрес файла.")
            }
        }
        is ProducedFileSource.Workspace -> {
            val id = requireNotNull(workspaceId) { "Рабочая папка не выбрана." }
            val directory = File(cacheDir, "workspace_share/${Uuid.random()}")
            check(directory.mkdirs()) { "Не удалось подготовить файл для отправки." }
            try {
                val file = File(directory, entry.displayName)
                file.outputStream().use { repository.exportRootfsFile(id, source.path, it) }
                FileProvider.getUriForFile(this@createProducedFileShareIntent, "$packageName.fileprovider", file)
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }
    val declaredType = (entry.source as? ProducedFileSource.Document)?.mime?.takeIf(String::isNotBlank)
    val type = declaredType?.takeUnless { it.endsWith("/*") || it == "application/octet-stream" }
        ?: contentResolver.getType(uri) ?: declaredType ?: "application/octet-stream"
    Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(entry.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
