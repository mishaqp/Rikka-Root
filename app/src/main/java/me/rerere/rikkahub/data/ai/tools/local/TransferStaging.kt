package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

private const val TRANSFER_CACHE_DIRECTORY = "rikka-transfer-staging"
private const val TRANSFER_PREFIX = "transfer-"
private const val TRANSFER_SUFFIX = ".part"
private const val MAX_STARTUP_CACHE_ENTRIES = 256

/** Internal capability for one app-owned transfer inode; never grants file-tool access to cache. */
internal class TransferStaging private constructor(
    private val directory: TransferCacheDirectory,
    val file: File,
    private val fileKey: Any,
) {
    fun checkedFile(): File {
        directory.check()
        require(file.parentFile == directory.file && file.canonicalFile == file) { "Путь временного файла изменился." }
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && attributes.fileKey() == fileKey) { "Временный файл изменился." }
        return file
    }

    fun openOutput(): OutputStream = Files.newOutputStream(checkedFile().toPath(),
        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)

    /** No target grant is required to remove our cache inode. Replaced paths remain untouched. */
    fun cleanup(): Boolean = try {
        directory.check()
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(checkedFile().toPath())
        true
    } catch (_: Exception) { false }

    companion object {
        /** SFTP must stage on the target volume; never substitute an unbounded cross-volume copy. */
        fun create(context: Context, targetParent: File? = null): TransferStaging {
            val targetStore = targetParent?.let { Files.getFileStore(it.toPath()) }
            val roots = sequence {
                yield(context.cacheDir)
                if (targetStore != null) context.externalCacheDirs.filterNotNull().forEach { yield(it) }
            }
            for (root in roots) {
                // Canonicalization must not grant cache ownership to an outside symlink target.
                if (Files.isSymbolicLink(root.toPath())) continue
                val canonical = root.canonicalFile
                if (!canonical.isDirectory && !canonical.mkdirs()) continue
                if (targetStore != null && Files.getFileStore(canonical.toPath()) != targetStore) continue
                val directory = TransferCacheDirectory.open(canonical, create = true)
                directory.check()
                val file = File.createTempFile(TRANSFER_PREFIX, TRANSFER_SUFFIX, directory.file)
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val stage = TransferStaging(directory, file, requireNotNull(attributes.fileKey()) { "Хранилище не подтвердило временный файл." })
                stage.checkedFile()
                return stage
            }
            throw IOException("На томе назначения нет доступного кэша приложения. Выберите другой путь; старый файл сохранён.")
        }
    }
}

private class TransferCacheDirectory private constructor(
    private val cacheRoot: File,
    private val rootKey: Any,
    val file: File,
    private val directoryKey: Any,
) {
    fun check() {
        require(cacheRoot.canonicalFile == cacheRoot && file.canonicalFile == file && file.parentFile == cacheRoot) {
            "Папка временных файлов изменилась."
        }
        for ((path, key) in listOf(cacheRoot to rootKey, file to directoryKey)) {
            val attributes = Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory && attributes.fileKey() == key) { "Папка временных файлов изменилась." }
        }
    }

    companion object {
        fun open(cacheRoot: File, create: Boolean): TransferCacheDirectory {
            val rootAttributes = Files.readAttributes(cacheRoot.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(rootAttributes.isDirectory) { "Кэш приложения недоступен." }
            val file = File(cacheRoot, TRANSFER_CACHE_DIRECTORY)
            if (create && !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(file.toPath()) }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* Another transfer created it. Validate below. */ }
            }
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory && file.canonicalFile == file) { "Папка временных файлов недоступна." }
            return TransferCacheDirectory(cacheRoot, requireNotNull(rootAttributes.fileKey()), file,
                requireNotNull(attributes.fileKey())).also { it.check() }
        }
    }
}

/** Called only at process startup, before any tool/worker can own an active transfer. */
internal fun cleanupInterruptedTransferStaging(context: Context) {
    val roots = listOf(context.cacheDir) + runCatching { context.externalCacheDirs.filterNotNull() }.getOrDefault(emptyList())
    for (root in roots.distinctBy { it.absolutePath }) {
        try {
            // Check the supplied root before canonicalization can hide its replacement.
            if (Files.isSymbolicLink(root.toPath())) continue
            val canonical = root.canonicalFile
            val dedicated = File(canonical, TRANSFER_CACHE_DIRECTORY)
            if (!Files.exists(dedicated.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            val directory = TransferCacheDirectory.open(canonical, create = false)
            Files.newDirectoryStream(directory.file.toPath()).use { entries ->
                var visited = 0
                for (path in entries) {
                    if (++visited > MAX_STARTUP_CACHE_ENTRIES) break
                    directory.check()
                    val name = path.fileName.toString()
                    if (!name.startsWith(TRANSFER_PREFIX) || !name.endsWith(TRANSFER_SUFFIX)) continue
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (!attributes.isRegularFile || attributes.fileKey() == null) continue
                    directory.check()
                    val fresh = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (fresh.isRegularFile && fresh.fileKey() == attributes.fileKey()) Files.delete(path)
                }
            }
        } catch (_: Exception) {
            // An unavailable/replaced cache is not a reason to delete anything through an untrusted path.
        }
    }
}
