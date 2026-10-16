package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

internal const val MAX_SHARED_VIEWER_BYTES = 128L * 1024 * 1024
private const val VIEWER_CACHE_LIFETIME_MS = 24L * 60 * 60 * 1000

/** FileProvider exposes app cache only; no broad external/root provider path is needed. */
internal suspend fun localFileForAndroidViewer(context: Context, access: LocalFileAccess, source: LocalFileSource.Local): File {
    val file = access.checkFile(source.file)
    if (!access.isDirectSharedStorage(file)) return file
    require(file.length() <= MAX_SHARED_VIEWER_BYTES) { "Файл слишком велик для просмотра: максимум 128 МиБ. Откройте его в файловом менеджере Android." }
    val directory = File(context.cacheDir, "tool_file_viewer")
    require(!Files.isSymbolicLink(directory.toPath()) && (directory.mkdirs() || directory.isDirectory)) { "Не удалось подготовить файл для просмотра. Освободите место и повторите." }
    val cutoff = System.currentTimeMillis() - VIEWER_CACHE_LIFETIME_MS
    directory.listFiles()?.forEach { old ->
        if (!Files.isSymbolicLink(old.toPath()) && old.isFile && old.lastModified() < cutoff) old.delete()
    }
    val extension = file.extension.take(16).takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }?.let { ".$it" } ?: ".bin"
    val snapshot = File.createTempFile("view-", extension, directory)
    try {
        access.openInput(source).use { input -> snapshot.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                access.checkFile(source.file)
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= MAX_SHARED_VIEWER_BYTES) { "Файл слишком велик для просмотра: максимум 128 МиБ. Откройте его в файловом менеджере Android." }
                output.write(buffer, 0, count)
            }
        } }
        currentCoroutineContext().ensureActive()
        access.checkFile(source.file)
        return snapshot
    } catch (failure: Exception) {
        snapshot.delete()
        throw failure
    }
}
