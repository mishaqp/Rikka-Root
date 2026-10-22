package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant
import java.net.URI

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        // The archive holds settings with API keys; never leave it in cache after a failed upload.
        try {
            val client = getClient(config)

            // Ensure the backup directory exists
            client.ensureCollectionExists().getOrThrow()

            // Upload the backup file
            client.put(
                path = file.name,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { resource ->
                !resource.isCollection && runCatching { backupHrefPath(config, resource.href).substringAfterLast('/') }
                    .getOrNull()?.let { it.startsWith("backup_") && it.endsWith(".zip") } == true
            }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val path = backupHrefPath(config, item.href)
        val client = getClient(config)
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(path, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(backupHrefPath(config, item.href)).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) {
        restoreFromBackupFile(file, config)
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = backupManager.createBackup(
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
    )

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
    )

}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

/** A DAV label is never an authenticated resource address or a local cache filename. */
private fun backupHrefPath(config: WebDavConfig, href: String): String {
    val reason = "Адрес резервной копии WebDAV выходит за настроенную папку или сервер. Обновите список архивов."
    val rootText = config.url.trimEnd('/') + config.path.takeIf { it.isNotBlank() }?.let { "/${it.trim('/')}" }.orEmpty()
    val root = try { URI(rootText) } catch (_: Exception) { throw IllegalArgumentException(reason) }
    require(root.scheme?.lowercase() in setOf("http", "https") && root.host != null && root.rawUserInfo == null &&
        root.rawQuery == null && root.rawFragment == null) { reason }
    val collection = URI(root.toString().trimEnd('/') + "/")
    val selected = try { URI(href) } catch (_: Exception) { throw IllegalArgumentException(reason) }
    fun safePath(uri: URI): Boolean {
        val path = uri.path.orEmpty()
        return '\\' !in path && path.none(Char::isISOControl) && path.split('/').none { it == "." || it == ".." }
    }
    require(safePath(selected) && selected.rawQuery == null && selected.rawFragment == null) { reason }
    val resolved = collection.resolve(selected)
    fun port(uri: URI) = uri.port.takeIf { it != -1 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    require(resolved.scheme.equals(root.scheme, true) && resolved.host?.equals(root.host, true) == true &&
        port(resolved) == port(root) && resolved.rawUserInfo == null && safePath(resolved)) { reason }
    val prefix = collection.rawPath.orEmpty()
    val rawPath = resolved.rawPath.orEmpty()
    require(rawPath.startsWith(prefix) && rawPath.length > prefix.length) { reason }
    return rawPath.removePrefix(prefix)
}
