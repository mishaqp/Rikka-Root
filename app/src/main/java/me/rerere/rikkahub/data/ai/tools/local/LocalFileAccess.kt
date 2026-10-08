// Scoped file access adapted from ExTV/rikkahub-agent file/SAF tools (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface LocalFileSource {
    val reference: String
    data class Local(val file: File) : LocalFileSource { override val reference: String get() = file.absolutePath }
    data class Content(val uri: Uri) : LocalFileSource { override val reference: String get() = uri.toString() }
}

/** Only scratch, uploads, the selected workspace and persisted SAF trees are reachable. */
class LocalFileAccess internal constructor(
    private val scratchDir: File,
    private val uploadDir: File,
    private val workspaceCwd: String? = null,
    private val chatImages: List<String> = emptyList(),
    private val resolveWorkspacePath: (suspend (String) -> File)? = null,
    private val context: Context? = null,
) {
    constructor(context: Context, workspaceCwd: String? = null, chatImages: List<String> = emptyList(),
        resolveWorkspacePath: (suspend (String) -> File)? = null) : this(
        File(context.filesDir, "tool_scratch"), File(context.filesDir, "upload"), workspaceCwd,
        chatImages, resolveWorkspacePath, context.applicationContext)

    val defaultPath: String get() = if (resolveWorkspacePath == null) "/scratch" else "/workspace"
    private val scratchRoot = scratchDir.canonicalFile
    private val uploadRoot = uploadDir.canonicalFile
    @Volatile private var workspaceRoot: File? = null
    init { require(scratchRoot.mkdirs() || scratchRoot.isDirectory) { "Не удалось создать scratch." } }
    val androidContext: Context get() = requireNotNull(context) { "Нужен Android Context." }

    suspend fun resolve(reference: String, write: Boolean = false): LocalFileSource = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(reference.isNotBlank() && reference.length <= 8192 && reference.none(Char::isISOControl)) { "Некорректный путь." }
        val raw = if (reference.startsWith("attachment:")) {
            require(!write) { "Вложение доступно только для чтения." }
            val index = reference.removePrefix("attachment:").toIntOrNull()
            require(index != null && index in 1..chatImages.size) { "Вложение не найдено." }
            chatImages[index - 1]
        } else reference
        require(raw.length <= 8192 && raw.none(Char::isISOControl)) { "Некорректный путь." }
        if (raw.startsWith("content:")) {
            val parsed = URI(raw)
            require(parsed.scheme == "content" && !parsed.authority.isNullOrBlank() && parsed.query == null && parsed.fragment == null) { "Некорректный content URI." }
            val source = LocalFileSource.Content(Uri.parse(raw))
            checkGrant(source, write)
            return@withContext source
        }
        val path = if (raw.startsWith("file:")) {
            val uri = URI(raw)
            require(uri.scheme == "file" && uri.authority == null && uri.query == null && uri.fragment == null) { "Некорректный file URI." }
            File(uri).path
        } else {
            require(!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) { "Этот URI не поддерживается." }
            raw
        }
        require(path.replace('\\', '/').split('/').none { it == ".." }) { "Выход через '..' запрещён." }
        val file = when {
            path == "~" || path == "/scratch" -> scratchRoot
            path.startsWith("~/") -> File(scratchRoot, path.removePrefix("~/"))
            path.startsWith("/scratch/") -> File(scratchRoot, path.removePrefix("/scratch/"))
            path == "/upload" -> uploadRoot
            path.startsWith("/upload/") -> File(uploadRoot, path.removePrefix("/upload/"))
            resolveWorkspacePath != null && (path == "/workspace" || path.startsWith("/workspace/") || !File(path).isAbsolute) -> {
                val root = resolveWorkspacePath.invoke("/workspace").canonicalFile
                workspaceRoot = root
                val cwd = workspaceCwd?.takeIf { it.isNotBlank() } ?: "/workspace"
                val absoluteCwd = if (cwd.startsWith('/')) cwd else "/workspace/$cwd"
                require(absoluteCwd == "/workspace" || absoluteCwd.startsWith("/workspace/")) { "Текущая папка вне /workspace." }
                resolveWorkspacePath.invoke(if (path.startsWith('/')) path else "$absoluteCwd/$path")
            }
            !File(path).isAbsolute -> File(scratchRoot, path)
            else -> {
                if (resolveWorkspacePath != null && workspaceRoot == null) workspaceRoot = resolveWorkspacePath.invoke("/workspace").canonicalFile
                File(path)
            }
        }
        LocalFileSource.Local(checkFile(file, write))
    }

    fun checkFile(file: File, write: Boolean = false): File {
        val absolute = file.absoluteFile.toPath().normalize()
        var parent = absolute
        while (parent.parent != null) {
            require(!Files.isSymbolicLink(parent)) { "Символические ссылки недоступны файловым инструментам." }
            parent = parent.parent
        }
        val canonical = file.canonicalFile
        val roots = listOfNotNull(scratchRoot, uploadRoot, workspaceRoot)
        val root = roots.firstOrNull { canonical.toPath().startsWith(it.toPath()) }
        require(root != null) { "Путь вне scratch, upload или выбранного workspace. Для внешних файлов выберите папку через SAF." }
        if (write) require(canonical != root) { "Изменение корня разрешённой области запрещено." }
        return canonical
    }

    private fun checkGrant(source: LocalFileSource.Content, write: Boolean) {
        require(androidContext.contentResolver.persistedUriPermissions.any {
            (if (write) it.isWritePermission else it.isReadPermission) && (
                contentTreeGrantCovers(it.uri.toString(), source.reference) ||
                contentTreeComponentsMatch(it.uri.toString(), source.reference) && runCatching {
                    // Cloud providers can use opaque IDs without a path prefix. Their own
                    // DocumentsProvider relation plus the exact held tree is authoritative.
                    DocumentsContract.isChildDocument(androidContext.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(it.uri, DocumentsContract.getTreeDocumentId(it.uri)),
                        documentUri(source.uri))
                }.getOrDefault(false))
        }) { "Нет ${if (write) "записи" else "чтения"} в выбранной папке. Сначала вызовите grant_directory_access." }
    }

    private fun documentUri(uri: Uri): Uri {
        val rawParts = URI(uri.toString()).rawPath.split('/').filter(String::isNotEmpty)
        return if ("document" in rawParts) uri else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }

    private fun document(source: LocalFileSource.Content, write: Boolean = false): DocumentFile {
        checkGrant(source, write)
        return requireNotNull(DocumentFile.fromSingleUri(androidContext, documentUri(source.uri))) { "Документ недоступен." }
    }

    suspend fun openInput(source: LocalFileSource): InputStream = run {
        currentCoroutineContext().ensureActive()
        when (source) {
            is LocalFileSource.Local -> checkFile(source.file).inputStream()
            is LocalFileSource.Content -> { checkGrant(source, false); requireNotNull(androidContext.contentResolver.openInputStream(source.uri)) { "Не удалось открыть файл." } }
        }
    }

    suspend fun openOutput(source: LocalFileSource, append: Boolean = false): OutputStream = run {
        currentCoroutineContext().ensureActive()
        when (source) {
            is LocalFileSource.Local -> java.io.FileOutputStream(checkFile(source.file, true).also { it.parentFile?.mkdirs() }, append)
            is LocalFileSource.Content -> { checkGrant(source, true); require(!append) { "Добавление к SAF-файлу не поддерживается." }; requireNotNull(androidContext.contentResolver.openOutputStream(source.uri, "wt")) { "Не удалось открыть файл для записи." } }
        }
    }

    suspend fun exists(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).exists()
        is LocalFileSource.Content -> document(source).exists()
    } }
    suspend fun isDirectory(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).isDirectory
        is LocalFileSource.Content -> document(source).isDirectory
    } }
    suspend fun name(source: LocalFileSource): String = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).name
        is LocalFileSource.Content -> document(source).name ?: "file"
    } }
    suspend fun size(source: LocalFileSource): Long = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).length()
        is LocalFileSource.Content -> document(source).length()
    } }
    suspend fun modified(source: LocalFileSource): Long = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).lastModified()
        is LocalFileSource.Content -> document(source).lastModified()
    } }

    suspend fun list(source: LocalFileSource): List<LocalFileSource> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(isDirectory(source)) { "Путь не является папкой." }
        val children = when (source) {
            is LocalFileSource.Local -> {
                val entries = mutableListOf<LocalFileSource>()
                Files.newDirectoryStream(checkFile(source.file).toPath()).use { directory ->
                    for (path in directory) {
                        currentCoroutineContext().ensureActive()
                        require(entries.size < MAX_ENTRIES) { "В папке слишком много файлов." }
                        entries += LocalFileSource.Local(checkFile(path.toFile()))
                    }
                }
                entries
            }
            is LocalFileSource.Content -> {
                val tree = DocumentFile.fromTreeUri(androidContext, source.uri)
                requireNotNull(tree) { "Нужна папка SAF." }
                // fromTreeUri always points at the tree root: find the actual requested directory.
                val id = if (DocumentsContract.isDocumentUri(androidContext, source.uri)) DocumentsContract.getDocumentId(source.uri) else DocumentsContract.getTreeDocumentId(source.uri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id)
                val found = mutableListOf<LocalFileSource>()
                androidContext.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        require(found.size < MAX_ENTRIES) { "В папке слишком много файлов." }
                        val child = LocalFileSource.Content(DocumentsContract.buildDocumentUriUsingTree(source.uri, cursor.getString(0)))
                        checkGrant(child, false); found += child
                    }
                } ?: error("Не удалось прочитать папку SAF.")
                found
            }
        }
        require(children.size <= MAX_ENTRIES) { "В папке слишком много файлов." }
        children.map { name(it) to it }.sortedBy { it.first }.map { it.second }
    }

    private fun validateChildName(name: String) {
        require(name.isNotBlank() && name.length <= 255 && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Некорректное имя файла." }
    }

    /** Resolves or creates a direct child. Callers record newly created targets before checking cancellation. */
    suspend fun child(parent: LocalFileSource, name: String, createDirectory: Boolean = false): LocalFileSource {
        validateChildName(name)
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            when (parent) {
                is LocalFileSource.Local -> {
                    val directory = checkFile(parent.file)
                    require(directory.isDirectory || createDirectory) { "Папка назначения не найдена." }
                    val file = checkFile(File(directory, name), true)
                    if (createDirectory) require(file.mkdirs() || file.isDirectory) { "Не удалось создать папку." }
                    LocalFileSource.Local(file)
                }
                is LocalFileSource.Content -> {
                    checkGrant(parent, true)
                    val existing = findChild(parent, name)
                    if (existing != null) {
                        require(isDirectory(existing) == createDirectory) { "Назначение имеет другой тип." }
                        existing
                    } else createContentChild(parent, name, createDirectory)
                }
            }
        }
    }

    /** Exclusive ownership: never returns a preexisting child, even after a name collision. */
    suspend fun createFile(parent: LocalFileSource, name: String): LocalFileSource = createNewChild(parent, name, false)

    suspend fun createNewChild(parent: LocalFileSource, name: String, isDirectory: Boolean = false): LocalFileSource {
        validateChildName(name)
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            when (parent) {
                is LocalFileSource.Local -> {
                    val directory = checkFile(parent.file)
                    require(directory.isDirectory) { "Папка назначения не найдена." }
                    val file = checkFile(File(directory, name), true)
                    require(if (isDirectory) file.mkdir() else file.createNewFile()) { "Назначение уже существует." }
                    try { LocalFileSource.Local(checkFile(file, true)) }
                    catch (failure: Exception) { if (!file.delete()) throw FileCreationCleanupFailedException(LocalFileSource.Local(file), failure); throw failure }
                }
                is LocalFileSource.Content -> createContentChild(parent, name, isDirectory)
            }
        }
    }

    private suspend fun createContentChild(parent: LocalFileSource.Content, name: String, directory: Boolean): LocalFileSource.Content {
        checkGrant(parent, true)
        val uri = requireNotNull(DocumentsContract.createDocument(androidContext.contentResolver,
            documentUri(parent.uri), if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)) { "Не удалось создать документ." }
        val created = LocalFileSource.Content(uri)
        try {
            require(uri.authority == parent.uri.authority) { "Провайдер вернул документ другого хранилища." }
            checkGrant(created, true)
            return created
        } catch (failure: Exception) {
            // The document is ours but has not reached the caller's ownership list yet.
            // Its direct returned URI is the only target cleaned up here.
            val cleaned = uri.authority == parent.uri.authority && runCatching { DocumentsContract.deleteDocument(androidContext.contentResolver, uri) }.getOrDefault(false)
            if (!cleaned) throw FileCreationCleanupFailedException(created, failure)
            throw failure
        }
    }

    suspend fun findChild(parent: LocalFileSource, name: String): LocalFileSource? = list(parent).firstOrNull { this.name(it) == name }

    suspend fun createDirectory(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) {
        when (source) {
            is LocalFileSource.Local -> checkFile(source.file, true).let { it.mkdirs() || it.isDirectory }
            is LocalFileSource.Content -> isDirectory(source)
        }
    }

    suspend fun walk(source: LocalFileSource, recursive: Boolean = true): List<LocalFileSource> {
        val result = mutableListOf<LocalFileSource>()
        suspend fun visit(current: LocalFileSource, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= MAX_DEPTH) { "Слишком глубокая структура папок." }
            for (entry in list(current)) {
                require(result.size < MAX_ENTRIES) { "Слишком много файлов." }
                result += entry
                if (recursive && isDirectory(entry)) visit(entry, depth + 1)
            }
        }
        visit(source, 0)
        return result
    }

    suspend fun delete(source: LocalFileSource, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!exists(source)) return@withContext false
        val children = if (isDirectory(source)) walk(source, recursive = true) else emptyList()
        require(recursive || children.isEmpty()) { "Папка не пуста. Укажите recursive=true." }
        // All descendants must clear both read and write policy before deleting any item.
        (children + source).forEach { entry -> when (entry) {
            is LocalFileSource.Local -> checkFile(entry.file, true)
            is LocalFileSource.Content -> { checkGrant(entry, true); require(!isTreeRoot(entry)) { "Удаление корня SAF запрещено." } }
        } }
        for (entry in children.asReversed() + source) {
            currentCoroutineContext().ensureActive()
            val deleted = when (entry) {
                is LocalFileSource.Local -> checkFile(entry.file, true).delete()
                is LocalFileSource.Content -> DocumentsContract.deleteDocument(androidContext.contentResolver, document(entry, true).uri)
            }
            require(deleted) { "Не удалось удалить файл." }
        }
        true
    }

    private fun isTreeRoot(source: LocalFileSource.Content): Boolean = runCatching {
        DocumentsContract.getTreeDocumentId(source.uri) == DocumentsContract.getDocumentId(document(source).uri)
    }.getOrDefault(true)

    suspend fun copy(from: LocalFileSource, to: LocalFileSource, overwrite: Boolean = false): Long = withContext(Dispatchers.IO) {
        val same = when {
            from is LocalFileSource.Local && to is LocalFileSource.Local -> checkFile(from.file) == checkFile(to.file)
            from is LocalFileSource.Content && to is LocalFileSource.Content -> sameDocumentIdentity(from.reference, to.reference)
            else -> false
        }
        require(!same) { "Источник совпадает с назначением." }
        require(exists(from)) { "Источник не найден." }
        if (isDirectory(from)) {
            require(to !is LocalFileSource.Local || from !is LocalFileSource.Local || !to.file.toPath().startsWith(from.file.toPath())) { "Нельзя копировать папку внутрь себя." }
            if (from is LocalFileSource.Content && to is LocalFileSource.Content) {
                val sourceId = DocumentsContract.getDocumentId(documentUri(from.uri))
                val targetId = DocumentsContract.getDocumentId(documentUri(to.uri))
                val nested = from.uri.authority == to.uri.authority && (sourceId == targetId || targetId.startsWith("$sourceId/") || runCatching {
                    DocumentsContract.isChildDocument(androidContext.contentResolver, documentUri(from.uri), documentUri(to.uri))
                }.getOrDefault(false))
                require(!nested) { "Нельзя копировать папку внутрь себя." }
            }
            walk(from) // Validate the complete source before creating destination entries.
            require(!exists(to) || overwrite) { "Назначение уже существует. Укажите overwrite=true." }
            require(if (to is LocalFileSource.Local) createDirectory(to) else isDirectory(to)) { "Нужна папка назначения." }
            var total = 0L
            suspend fun copyDirectory(source: LocalFileSource, destination: LocalFileSource, depth: Int) {
                require(depth <= MAX_DEPTH) { "Слишком глубокая структура папок." }
                for (entry in list(source)) {
                    currentCoroutineContext().ensureActive()
                    val directory = isDirectory(entry)
                    val entryName = name(entry)
                    val previous = findChild(destination, entryName)
                    require(previous == null || overwrite) { "Назначение уже существует. Укажите overwrite=true." }
                    var owned: LocalFileSource? = null
                    val target = if (previous != null) previous else withContext(NonCancellable) {
                        createNewChild(destination, entryName, directory).also { owned = it }
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (directory) copyDirectory(entry, target, depth + 1)
                        else total += copy(entry, target, overwrite || owned != null)
                    } catch (failure: Exception) {
                        owned?.let { created -> withContext(NonCancellable) { runCatching { delete(created, recursive = true) } } }
                        throw failure
                    }
                    require(total <= MAX_COPY_BYTES) { "Копирование превышает 512 МиБ." }
                }
            }
            copyDirectory(from, to, 0)
            return@withContext total
        }
        // Source use surrounds destination validation/open: early failures always close it (0ff79698).
        openInput(from).use { input ->
            require(!exists(to) || overwrite) { "Назначение уже существует. Укажите overwrite=true." }
            stagedWrite(to, overwrite, false) { output -> copyBounded(input, output) }
        }
    }

    suspend fun move(from: LocalFileSource, to: LocalFileSource, overwrite: Boolean = false): Long {
        when (from) {
            is LocalFileSource.Local -> checkFile(from.file, true)
            is LocalFileSource.Content -> { checkGrant(from, true); require(!isTreeRoot(from)) { "Перемещение корня SAF запрещено." } }
        }
        val bytes = copy(from, to, overwrite)
        try {
            require(delete(from, recursive = true)) { "Источник не удалён." }
        } catch (failure: Exception) {
            val preserved = FileMoveSourceDeleteException(failure)
            if (failure is CancellationException) { failure.addSuppressed(preserved); throw failure }
            throw preserved
        }
        return bytes
    }

    suspend fun write(source: LocalFileSource, bytes: ByteArray, overwrite: Boolean = false, append: Boolean = false): Long = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_WRITE_BYTES) { "Запись превышает 16 МиБ." }
        require(!exists(source) || overwrite || append) { "Файл уже существует. Укажите overwrite=true или append=true." }
        stagedWrite(source, overwrite || append, append) { output -> output.write(bytes); bytes.size.toLong() }
    }

    private suspend fun stagedWrite(source: LocalFileSource, overwrite: Boolean, append: Boolean, write: suspend (OutputStream) -> Long): Long {
        require(!append || source is LocalFileSource.Local) { "Добавление к SAF-файлу не поддерживается." }
        val parent = if (source is LocalFileSource.Local) checkFile(source.file, true).parentFile else scratchRoot
        requireNotNull(parent).mkdirs()
        val staging = File.createTempFile(".rk-write-", ".tmp", parent)
        try {
            val count = staging.outputStream().use { output ->
                if (append && exists(source)) openInput(source).use { copyBounded(it, output) }
                write(output)
            }
            currentCoroutineContext().ensureActive()
            when (source) {
                is LocalFileSource.Local -> {
                    val target = checkFile(source.file, true)
                    require(overwrite || !target.exists()) { "Файл уже существует." }
                    // ATOMIC_MOVE can replace a raced target without REPLACE_EXISTING.
                    // link(2) installs a complete same-filesystem inode exclusively;
                    // an existing target is preserved even under a creation race.
                    try { if (overwrite) Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        else Files.createLink(target.toPath(), staging.toPath()) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        if (overwrite) Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        else throw IllegalStateException("Хранилище не поддерживает безопасное создание файла.")
                    }
                }
                is LocalFileSource.Content -> {
                    // Preserve the original document before any provider truncation. A
                    // cancelled or failed commit restores it under NonCancellable; if that
                    // fails the complete backup remains visible in scratch for recovery.
                    checkGrant(source, true)
                    require(!exists(source) || overwrite) { "Файл уже существует." }
                    val backup = File.createTempFile(".rk-restore-", ".bak", scratchRoot)
                    commitStreamWithRollback(staging, backup, { openInput(source) }, { openOutput(source) })
                }
            }
            return count
        } finally { staging.delete() }
    }

    private suspend fun copyBounded(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(8192)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes += count
            require(bytes <= MAX_COPY_BYTES) { "Копирование превышает 512 МиБ." }
            output.write(buffer, 0, count)
        }
        return bytes
    }

    companion object {
        const val MAX_ENTRIES = 5000
        const val MAX_DEPTH = 64
        const val MAX_WRITE_BYTES = 16 * 1024 * 1024
        const val MAX_COPY_BYTES = 512L * 1024 * 1024
    }
}

/** Compare tree/document IDs after component decoding; prefix collisions never grant access. */
internal fun contentTreeGrantCovers(grant: String, target: String): Boolean = try {
    fun components(uri: URI): List<String> = uri.rawPath.split('/').filter(String::isNotEmpty).map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    val held = URI(grant)
    val requested = URI(target)
    val a = components(held)
    val b = components(requested)
    if (held.scheme != "content" || requested.scheme != "content" || held.authority != requested.authority ||
        held.query != null || requested.query != null || held.fragment != null || requested.fragment != null || a.firstOrNull() != "tree" || a.size != 2) false
    else {
        val treeId = a[1]
        val documentId = when {
            b.size == 2 && b[0] == "tree" && b[1] == treeId -> treeId
            b.size == 4 && b[0] == "tree" && b[1] == treeId && b[2] == "document" -> b[3]
            else -> null
        }
        documentId != null && (documentId == treeId || documentId.startsWith("$treeId/")) && documentId.split('/').none { it in listOf(".", "..") }
    }
} catch (_: Exception) { false }


internal fun contentTreeComponentsMatch(grant: String, target: String): Boolean = try {
    val held = URI(grant)
    val requested = URI(target)
    val a = decodedUriPath(held)
    val b = decodedUriPath(requested)
    held.scheme == "content" && requested.scheme == "content" && held.authority == requested.authority &&
        held.query == null && requested.query == null && held.fragment == null && requested.fragment == null &&
        a.size == 2 && a[0] == "tree" && b.size == 4 && b[0] == "tree" && b[1] == a[1] && b[2] == "document"
} catch (_: Exception) { false }

private fun decodedUriPath(uri: URI): List<String> = uri.rawPath.split('/').filter(String::isNotEmpty)
    .map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }

private fun documentIdentity(raw: String): Pair<String, String>? = try {
    val uri = URI(raw)
    if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.query != null || uri.fragment != null) null
    else {
        val parts = decodedUriPath(uri)
        val id = when {
            parts.size == 2 && parts[0] in listOf("tree", "document") -> parts[1]
            parts.size == 4 && parts[0] == "tree" && parts[2] == "document" -> parts[3]
            else -> null
        }
        id?.let { uri.authority to it }
    }
} catch (_: Exception) { null }

internal fun sameDocumentIdentity(first: String, second: String): Boolean =
    documentIdentity(first)?.let { it == documentIdentity(second) } == true

internal fun contentDocumentIsWithin(parent: String, child: String): Boolean {
    val a = documentIdentity(parent) ?: return false
    val b = documentIdentity(child) ?: return false
    return a.first == b.first && (a.second == b.second || b.second.startsWith("${a.second}/"))
}

internal class FileCreationCleanupFailedException(val created: LocalFileSource, cause: Exception) :
    java.io.IOException("Не удалось убрать созданный документ после ошибки доступа.", cause)

internal class FileMoveSourceDeleteException(cause: Exception) :
    java.io.IOException("Копия сохранена, но источник не удалось полностью удалить.", cause)

internal fun isMoveCopyPreserved(error: Exception): Boolean = error is FileMoveSourceDeleteException ||
    error.suppressed.any { it is FileMoveSourceDeleteException }

internal class FileRestoreFailedException(val backup: File, cause: Exception) :
    java.io.IOException("Не удалось восстановить файл. Резервная копия сохранена: /scratch/${backup.name}", cause)

/** Backup must finish before truncation; unsuccessful restoration keeps the only original copy. */
internal suspend fun commitStreamWithRollback(
    replacement: File,
    backup: File,
    openCurrent: suspend () -> InputStream,
    openDestination: suspend () -> OutputStream,
    maxBytes: Long = LocalFileAccess.MAX_COPY_BYTES,
): Long {
    var backupComplete = false
    var preserveBackup = false
    try {
        openCurrent().use { original -> backup.outputStream().use { copyRollbackStream(original, it, maxBytes) } }
        backupComplete = true
        currentCoroutineContext().ensureActive()
        try {
            // Treat opener failures as possibly destructive: a provider may truncate
            // before reporting its error, so restoration still runs.
            val copied = replacement.inputStream().use { input -> openDestination().use { copyRollbackStream(input, it, maxBytes) } }
            // A provider may block in close(); cancellation still requires restoration.
            currentCoroutineContext().ensureActive()
            return copied
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    withTimeout(30_000L) {
                        backup.inputStream().use { original -> openDestination().use { copyRollbackStream(original, it, maxBytes) } }
                    }
                }
            } catch (restoration: Exception) {
                preserveBackup = true
                val recovery = FileRestoreFailedException(backup, restoration)
                if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
                recovery.addSuppressed(failure)
                throw recovery
            }
            throw failure
        }
    } finally {
        if (!preserveBackup || !backupComplete) backup.delete()
    }
}

private suspend fun copyRollbackStream(input: InputStream, output: OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(8192)
    var bytes = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return bytes
        if (count == 0) continue
        bytes += count
        require(bytes <= maxBytes) { "Файл превышает лимит безопасной записи." }
        output.write(buffer, 0, count)
    }
}
