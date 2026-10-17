// Scoped file access adapted from ExTV/rikkahub-agent file/SAF tools (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

sealed interface LocalFileSource {
    val reference: String
    data class Local(val file: File) : LocalFileSource { override val reference: String get() = file.absolutePath }
    data class Content(val uri: Uri) : LocalFileSource { override val reference: String get() = uri.toString() }
}

/** Local scopes and SAF stay unchanged; direct shared storage requires two live, explicit switches. */
class LocalFileAccess internal constructor(
    private val scratchDir: File,
    private val uploadDir: File,
    private val workspaceCwd: String? = null,
    private val chatImages: List<String> = emptyList(),
    private val resolveWorkspacePath: (suspend (String) -> File)? = null,
    private val context: Context? = null,
    private val primaryStorageAliases: List<File> = listOf(File("/sdcard"), File("/mnt/sdcard"), File("/storage/self/primary")),
    private val sharedStorageAccess: SharedStorageAccess? = context?.let { SharedStorageAccess.forContext(it) },
    private val qrScreenshotCacheRoot: File? = null,
) {
    constructor(context: Context, workspaceCwd: String? = null, chatImages: List<String> = emptyList(),
        resolveWorkspacePath: (suspend (String) -> File)? = null) : this(
        File(context.filesDir, "tool_scratch"), File(context.filesDir, "upload"), workspaceCwd,
        chatImages, resolveWorkspacePath, context.applicationContext)

    private data class SafReadMetadata(val name: String, val directory: Boolean, val bytes: Long, val modified: Long)

    private class ReadOperationState {
        // One traversal budget plus its root. Prefetched/unused rows can be evicted.
        val metadata = java.util.LinkedHashMap<String, SafReadMetadata>(16, 0.75f, true)
        val safParents = linkedSetOf<LocalFileSource.Content>()
        var sharedReadAnchor: File? = null

        fun remember(source: LocalFileSource.Content, row: SafReadMetadata) {
            metadata[source.reference] = row
            if (metadata.size > MAX_ENTRIES + 1) metadata.entries.iterator().run { next(); remove() }
        }

        fun parent(source: LocalFileSource.Content) {
            require(safParents.size <= MAX_ENTRIES || source in safParents) { "Слишком много папок SAF." }
            safParents += source
        }
    }

    private val readOperation = ThreadLocal<ReadOperationState>()

    /** Only a side-effect-free listing query may run inside this call-scoped snapshot. */
    internal suspend fun <T> withReadOperation(action: suspend () -> T): T = withContext(Dispatchers.IO) { withStorageTopology {
        if (readOperation.get() != null) action()
        else {
            val state = ReadOperationState()
            withContext(readOperation.asContextElement(state)) {
                try {
                    val result = action()
                    currentCoroutineContext().ensureActive()
                    // Cached rows cannot outlive either their provider tree or live direct access.
                    for (parent in state.safParents) {
                        currentCoroutineContext().ensureActive()
                        checkGrant(parent, false)
                    }
                    state.sharedReadAnchor?.let { checkFile(it) }
                    currentCoroutineContext().ensureActive()
                    result
                } finally {
                    state.metadata.clear()
                    state.safParents.clear()
                }
            }
        }
    } }

    private fun cachedMetadata(source: LocalFileSource.Content): SafReadMetadata? =
        readOperation.get()?.metadata?.get(source.reference)

    val defaultPath: String get() = if (resolveWorkspacePath == null) "/scratch" else "/workspace"
    private val scratchRoot = scratchDir.canonicalFile
    private val uploadRoot = uploadDir.canonicalFile
    @Volatile private var workspaceRoot: File? = null
    init { require(scratchRoot.mkdirs() || scratchRoot.isDirectory) { "Не удалось создать scratch." } }
    val androidContext: Context get() = requireNotNull(context) { "Нужен Android Context." }

    /** QR alone may read the two existing screenshot outputs; regular file tools keep their scopes. */
    internal fun withQrScreenshotReads(android: Context): LocalFileAccess = LocalFileAccess(
        scratchDir, uploadDir, workspaceCwd, chatImages, resolveWorkspacePath,
        context ?: android.applicationContext, primaryStorageAliases, sharedStorageAccess,
        qrScreenshotCacheRoot = android.cacheDir.absoluteFile,
    ).also { it.workspaceRoot = workspaceRoot }

    internal fun imageSources(): LocalImageSources = LocalImageSources(
        uploadDir, workspaceCwd, chatImages, resolveWorkspacePath, fileAccess = this,
    )

    internal fun isDirectSharedStorage(file: File): Boolean = sharedStorageAccess?.resolve(file.absolutePath) != null

    /** Classification survives revocation: a shared pathname can be replaced by another app. */
    internal fun permitsAutomaticRollback(source: LocalFileSource): Boolean = when (source) {
        is LocalFileSource.Content -> true // Preserve the existing SAF provider transaction.
        is LocalFileSource.Local -> {
            val path = source.file.absoluteFile.toPath().normalize()
            listOfNotNull(scratchRoot, uploadRoot, workspaceRoot).any { path.startsWith(it.toPath()) }
        }
    }

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
        // Virtual paths and files already owned by the local scopes retain their original
        // resolver and symlink checks without any dependency on Android SAF services.
        val physical = file.absoluteFile.toPath().normalize()
        val localScope = listOfNotNull(scratchRoot, uploadRoot, workspaceRoot).any { physical.startsWith(it.toPath()) }
        val virtual = path == "/scratch" || path.startsWith("/scratch/") ||
            path == "/upload" || path.startsWith("/upload/") ||
            path == "/workspace" || path.startsWith("/workspace/")
        if (File(path).isAbsolute && !virtual && !localScope) {
            sharedStorageAccess?.resolve(path, write)?.let { return@withContext LocalFileSource.Local(it) }
            @Suppress("DEPRECATION")
            val primary = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            if (sharedStoragePathCandidate(path, primary, primaryStorageAliases)) {
                resolveGrantedStoragePath(path, write)?.let { return@withContext it }
                // With direct access disabled/denied, retain the original SAF-only path.
                throw IllegalArgumentException(NO_STORAGE_GRANT_MESSAGE)
            }
        }
        LocalFileSource.Local(checkFile(file, write))
    }

    /** A pathname is only an address for an already-held SAF tree; it never enables raw external File I/O. */
    private suspend fun resolveGrantedStoragePath(path: String, write: Boolean): LocalFileSource.Content? {
        val android = context ?: return null
        val roots = try { scannerVolumeRoots(android) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        if (!knownSharedStorageAddress(path, roots, primaryStorageAliases)) return null
        val grants = android.contentResolver.persistedUriPermissions.filter {
            (if (write) it.isWritePermission else it.isReadPermission) && DocumentsContract.isTreeUri(it.uri)
        }
        if (grants.isEmpty()) return null
        for (grant in grants) {
            currentCoroutineContext().ensureActive()
            val plan = grantedStoragePathPlan(path, grant.uri.toString(), roots, primaryStorageAliases) ?: continue
            var source = LocalFileSource.Content(grant.uri)
            checkGrant(source, write)
            if (plan.documentId != null) {
                source = LocalFileSource.Content(DocumentsContract.buildDocumentUriUsingTree(grant.uri, plan.documentId))
            } else {
                // Downloads exposes opaque child IDs: only the provider may resolve the actual names.
                checkGrant(source, false)
                for (name in plan.relativeNames) {
                    currentCoroutineContext().ensureActive()
                    source = findChild(source, name) as? LocalFileSource.Content
                        ?: throw SafStoragePathNotFoundException()
                }
            }
            checkGrant(source, write) // Includes permission revocation during provider traversal.
            return source
        }
        return null
    }

    /** Missing absolute SAF files are planned without creating provider documents. */
    internal suspend fun planWritePath(reference: String): FileWritePlan {
        require(reference.isNotBlank() && reference.length <= 8192 && reference.none(Char::isISOControl)) { "Некорректный путь." }
        // Existing targets (including a held tree root) need only their own grant.
        // Only provider-confirmed absence can fall through to planning a new child.
        val existing = try { resolve(reference, write = true) }
        catch (_: SafStoragePathNotFoundException) { null }
        if (existing != null && (existing !is LocalFileSource.Content || exists(existing))) return FileWritePlan(existing)
        val path = if (reference.startsWith("file:")) File(URI(reference)).path else reference
        @Suppress("DEPRECATION")
        val primary = if (File(path).isAbsolute) runCatching { Environment.getExternalStorageDirectory() }.getOrNull() else null
        if (File(path).isAbsolute && sharedStoragePathCandidate(path, primary, primaryStorageAliases)) {
            val file = File(path)
            val parentPath = file.parent ?: error("Не удалось определить папку назначения.")
            val parent = resolve(parentPath, write = true)
            if (parent is LocalFileSource.Content) {
                // Validate the complete pathname with the same held-tree plan as resolve,
                // including the final component's symlink and depth checks.
                val heldTree = DocumentsContract.buildTreeDocumentUri(parent.uri.authority, DocumentsContract.getTreeDocumentId(parent.uri))
                require(grantedStoragePathPlan(path, heldTree.toString(), scannerVolumeRoots(androidContext), primaryStorageAliases) != null) { "Путь вне выбранной папки SAF." }
                validateChildName(file.name)
                require(isDirectory(parent)) { "Папка назначения не найдена." }
                val previous = findChild(parent, file.name)
                if (previous is LocalFileSource.Content) checkGrant(previous, true)
                return FileWritePlan(previous, parent, file.name)
            }
        }
        return FileWritePlan(resolve(reference, write = true))
    }

    /** Ownership is recorded before cancellation can leave a new provider document behind. */
    internal suspend fun <T> withWriteTarget(plan: FileWritePlan, action: suspend (LocalFileSource, Boolean) -> T): Pair<LocalFileSource, T> {
        currentCoroutineContext().ensureActive()
        var owned: LocalFileSource? = null
        try {
            val target = plan.existing ?: withContext(NonCancellable) {
                val parent = requireNotNull(plan.parent)
                require(findChild(parent, plan.name) == null) { "Назначение уже существует." }
                createFile(parent, plan.name).also { owned = it }
            }
            currentCoroutineContext().ensureActive()
            if (owned != null) require(name(target) == plan.name) { "Провайдер SAF изменил имя создаваемого файла." }
            val result = action(target, owned != null)
            currentCoroutineContext().ensureActive()
            return target to result
        } catch (failure: Exception) {
            owned?.let { cleanupOwnedTarget(it, failure) }
            throw failure
        }
    }

    /** Failed provider cleanup must remain visible, including when the operation was cancelled. */
    internal suspend fun cleanupOwnedTarget(created: LocalFileSource, failure: Exception, recursive: Boolean = false) {
        if (!permitsAutomaticRollback(created)) {
            val partial = FileCreationCleanupFailedException(created, failure, sharedPathMayBePartial = true)
            if (failure is CancellationException) { failure.addSuppressed(partial); throw failure }
            throw partial
        }
        val cleaned = withContext(NonCancellable) {
            runCatching { !exists(created) || delete(created, recursive) }.getOrDefault(false)
        }
        if (!cleaned) {
            val cleanup = FileCreationCleanupFailedException(created, failure)
            if (failure is CancellationException) { failure.addSuppressed(cleanup); throw failure }
            throw cleanup
        }
    }

    internal suspend fun writePath(reference: String, bytes: ByteArray, overwrite: Boolean = false, append: Boolean = false): Pair<LocalFileSource, Long> {
        require(bytes.size <= MAX_WRITE_BYTES) { "Запись превышает 16 МиБ." }
        val plan = planWritePath(reference)
        require(!append || plan.existing is LocalFileSource.Local) { "Добавление к SAF-файлу не поддерживается." }
        return withWriteTarget(plan) { target, created ->
            if (created) stagedWrite(target, true, false, ownedDestination = true) { output -> output.write(bytes); bytes.size.toLong() }
            else write(target, bytes, overwrite, append)
        }
    }

    internal suspend fun copyPath(from: LocalFileSource, reference: String, overwrite: Boolean = false): Pair<LocalFileSource, Long> {
        require(exists(from)) { "Источник не найден." }
        val plan = planWritePath(reference)
        require(plan.existing != null || !isDirectory(from)) { "Для копирования папки сначала создайте папку назначения." }
        return withWriteTarget(plan) { target, created -> copy(from, target, overwrite || created, ownedDestination = created) }
    }

    internal suspend fun movePath(from: LocalFileSource, reference: String, overwrite: Boolean = false): Pair<LocalFileSource, Long> {
        validateMoveSource(from)
        val target = planWritePath(reference).existing
        if (target != null) tryLocalMove(from, target, overwrite)?.let { return target to it }
        val copied = copyPath(from, reference, overwrite)
        // Finish ownership rollback before deletion: a committed copy survives a
        // source-delete failure, including cancellation after partial deletion.
        deleteMovedSource(from)
        return copied
    }

    fun checkFile(file: File, write: Boolean = false): File {
        val absolute = file.absoluteFile.toPath().normalize()
        val localScope = listOfNotNull(scratchRoot, uploadRoot, workspaceRoot).any { absolute.startsWith(it.toPath()) }
        if (!localScope) sharedStorageAccess?.resolve(file.absolutePath, write)?.let {
            readOperation.get()?.let { state -> if (state.sharedReadAnchor == null) state.sharedReadAnchor = it }
            return it
        }
        var parent = absolute
        while (parent.parent != null) {
            require(!Files.isSymbolicLink(parent)) { "Символические ссылки недоступны файловым инструментам." }
            parent = parent.parent
        }
        val canonical = file.canonicalFile
        // No directories, arbitrary cache files, links or writes enter this opt-in capability.
        // The requested-path symlink walk above applies equally to screenshot reads.
        if (!write && qrScreenshotCacheRoot != null && canonical.isFile) {
            // The app's own cacheDir sits under the system /data/user/0 -> /data/data link, so its
            // ancestors are not walked; canonical parent equality rejects a linked screenshots folder.
            val cache = qrScreenshotCacheRoot.canonicalFile
            val parent = canonical.parentFile
            val screenshot = parent == File(cache, "screenshots") && canonical.name.matches(Regex("screen-[0-9]+\\.png"))
            val browser = parent == File(cache, "browser-shots") && canonical.name.matches(Regex("screenshot-[0-9]+\\.png"))
            if (screenshot || browser) return canonical
        }
        val roots = listOfNotNull(scratchRoot, uploadRoot, workspaceRoot)
        val root = roots.firstOrNull { canonical.toPath().startsWith(it.toPath()) }
        require(root != null) { "Путь вне scratch, upload или выбранного workspace. $NO_STORAGE_GRANT_MESSAGE" }
        if (write) require(canonical != root) { "Изменение корня разрешённой области запрещено." }
        return canonical
    }

    private suspend fun checkGrant(source: LocalFileSource.Content, write: Boolean) {
        require(androidContext.contentResolver.persistedUriPermissions.any {
            (if (write) it.isWritePermission else it.isReadPermission) && (
                contentTreeGrantCovers(it.uri.toString(), source.reference) ||
                contentTreeComponentsMatch(it.uri.toString(), source.reference) && try {
                    // Cloud providers can use opaque IDs without a path prefix. Their own
                    // DocumentsProvider relation plus the exact held tree is authoritative.
                    providerContains(it.uri, source.uri)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false })
        }) { "Нет ${if (write) "записи" else "чтения"} в выбранной папке. Сначала вызовите grant_directory_access." }
    }

    /** API 26–28 providers prove opaque ancestry through bounded child enumeration. */
    private suspend fun providerContains(parent: Uri, target: Uri): Boolean {
        currentCoroutineContext().ensureActive()
        if (parent.authority != target.authority) return false
        val parentDocument = documentUri(parent)
        val targetDocument = documentUri(target)
        val rootId = DocumentsContract.getDocumentId(parentDocument)
        val targetId = DocumentsContract.getDocumentId(targetDocument)
        if (rootId == targetId) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contained = safProviderCall { DocumentsContract.isChildDocument(androidContext.contentResolver, parentDocument, targetDocument) }
                currentCoroutineContext().ensureActive()
                return contained
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unsupported provider: enumerate rather than guess. */ }
        }
        return boundedDocumentReachable(rootId, targetId) { id, remaining ->
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, id)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val found = mutableListOf<DocumentChild>()
            safProviderCall { androidContext.contentResolver.query(childrenUri, projection, null, null, null) }?.let(::SafCursor)?.use { cursor ->
                val idColumn = safProviderCall { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) }
                val mimeColumn = safProviderCall { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) }
                while (safProviderCall { cursor.moveToNext() }) {
                    currentCoroutineContext().ensureActive()
                    check(found.size < remaining) { "Слишком много документов для подтверждения границ папки SAF." }
                    val childId = safProviderCall { cursor.getString(idColumn) }
                    check(!childId.isNullOrBlank() && childId.length <= 8192) { "Провайдер SAF вернул некорректный идентификатор." }
                    val mime = safProviderCall { cursor.getString(mimeColumn) }
                    check(!mime.isNullOrBlank()) { "Провайдер SAF не подтвердил тип дочернего документа." }
                    found += DocumentChild(childId, mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            } ?: error("Провайдер SAF не разрешил подтвердить границы папки.")
            currentCoroutineContext().ensureActive()
            found
        }
    }

    private fun documentUri(uri: Uri): Uri {
        val rawParts = URI(uri.toString()).rawPath.split('/').filter(String::isNotEmpty)
        return if ("document" in rawParts) uri else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }

    private suspend fun document(source: LocalFileSource.Content, write: Boolean = false): DocumentFile {
        checkGrant(source, write)
        return requireNotNull(DocumentFile.fromSingleUri(androidContext, documentUri(source.uri))) { "Документ недоступен." }
    }

    suspend fun openInput(source: LocalFileSource): InputStream = run {
        currentCoroutineContext().ensureActive()
        when (source) {
            is LocalFileSource.Local -> {
                val file = checkFile(source.file)
                if (permitsAutomaticRollback(source)) file.inputStream()
                else GuardedSharedInputStream(Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { checkFile(file) }
            }
            is LocalFileSource.Content -> {
                checkGrant(source, false)
                val input = try { safProviderCall { androidContext.contentResolver.openInputStream(source.uri) } }
                catch (failure: SafFileOperationException) {
                    // Providers report a missing document with assorted exception types; name the real cause.
                    val missing = try { !exists(source) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                    if (missing) throw SafFileOperationException(java.nio.file.NoSuchFileException("document"))
                    throw failure
                }
                SafInputStream(requireNotNull(input) { "Не удалось открыть файл." })
            }
        }
    }

    suspend fun openOutput(source: LocalFileSource, append: Boolean = false): OutputStream = run {
        currentCoroutineContext().ensureActive()
        when (source) {
            is LocalFileSource.Local -> {
                val file = checkFile(source.file, true).also { it.parentFile?.mkdirs() }
                if (permitsAutomaticRollback(source)) java.io.FileOutputStream(file, append)
                else {
                    val options: Array<java.nio.file.OpenOption> = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
                    else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                    GuardedSharedOutputStream(Files.newOutputStream(file.toPath(), *options)) { checkFile(file, true) }
                }
            }
            is LocalFileSource.Content -> {
                checkGrant(source, true)
                require(!append) { "Добавление к SAF-файлу не поддерживается." }
                SafOutputStream(requireNotNull(safProviderCall { androidContext.contentResolver.openOutputStream(source.uri, "wt") }) { "Не удалось открыть файл для записи." })
            }
        }
    }

    suspend fun exists(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).exists()
        is LocalFileSource.Content -> {
            val metadata = document(source)
            safProviderCall { metadata.exists() }
        }
    } }
    suspend fun isDirectory(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).isDirectory
        is LocalFileSource.Content -> cachedMetadata(source)?.directory ?: run {
            val metadata = document(source)
            safProviderCall { metadata.isDirectory }
        }
    } }
    suspend fun name(source: LocalFileSource): String = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).name
        is LocalFileSource.Content -> cachedMetadata(source)?.name ?: run {
            val metadata = document(source)
            safProviderCall { metadata.name } ?: "file"
        }
    } }
    suspend fun size(source: LocalFileSource): Long = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).length()
        is LocalFileSource.Content -> cachedMetadata(source)?.bytes ?: run {
            val metadata = document(source)
            safProviderCall { metadata.length() }
        }
    } }
    suspend fun modified(source: LocalFileSource): Long = withContext(Dispatchers.IO) { when (source) {
        is LocalFileSource.Local -> checkFile(source.file).lastModified()
        is LocalFileSource.Content -> cachedMetadata(source)?.modified ?: run {
            val metadata = document(source)
            safProviderCall { metadata.lastModified() }
        }
    } }

    private suspend fun <T> withStorageTopology(action: suspend () -> T): T =
        if (sharedStorageAccess == null) action() else sharedStorageAccess.withTopologySnapshot(action)

    /** A bounded prefix is opt-in; transaction preflights remain strict by default. */
    suspend fun list(
        source: LocalFileSource,
        onRestrictedPathSkipped: ((String) -> Unit)? = null,
        limit: Int = MAX_ENTRIES,
        onTruncated: (() -> Unit)? = null,
    ): List<LocalFileSource> = withContext(Dispatchers.IO) { withStorageTopology {
        require(limit in 0..MAX_ENTRIES) { "Некорректный лимит файлов." }
        currentCoroutineContext().ensureActive()
        require(isDirectory(source)) { "Путь не является папкой." }
        val children = mutableListOf<Pair<String, LocalFileSource>>()
        fun truncated() {
            requireNotNull(onTruncated) { "В папке слишком много файлов." }.invoke()
        }
        when (source) {
            is LocalFileSource.Local -> {
                Files.newDirectoryStream(checkFile(source.file).toPath()).use { directory ->
                    for (path in directory) {
                        currentCoroutineContext().ensureActive()
                        if (children.size >= limit) { truncated(); break }
                        try {
                            val file = checkFile(path.toFile())
                            children += file.name to LocalFileSource.Local(file)
                        } catch (denied: SharedStoragePrivatePathException) {
                            // Mutating operations and archives omit no private descendants.
                            if (onRestrictedPathSkipped == null) throw denied
                            onRestrictedPathSkipped(path.toString())
                        }
                    }
                }
                // A revocation during enumeration must reject the entire result.
                checkFile(source.file)
            }
            is LocalFileSource.Content -> {
                val id = if (DocumentsContract.isDocumentUri(androidContext, source.uri)) DocumentsContract.getDocumentId(source.uri) else DocumentsContract.getTreeDocumentId(source.uri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id)
                val scope = readOperation.get()
                val projection = if (scope == null) arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                else arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                // The granted parent and provider-enumerated edges establish child scope.
                // Names travel in the same cursor, avoiding metadata/grant IPC per row.
                checkGrant(source, false)
                scope?.parent(source)
                safProviderCall { androidContext.contentResolver.query(childrenUri, projection, null, null, null) }?.let(::SafCursor)?.use { cursor ->
                    while (safProviderCall { cursor.moveToNext() }) {
                        currentCoroutineContext().ensureActive()
                        if (children.size >= limit) { truncated(); break }
                        val childId = safProviderCall { cursor.getString(0) }
                        require(!childId.isNullOrBlank() && childId.length <= 8192) { "Провайдер SAF вернул некорректный идентификатор." }
                        val child = LocalFileSource.Content(DocumentsContract.buildDocumentUriUsingTree(source.uri, childId))
                        val childName = safProviderCall { cursor.getString(1) } ?: "file"
                        if (scope != null) {
                            val mime = safProviderCall { cursor.getString(2) }
                            require(!mime.isNullOrBlank()) { "Провайдер SAF не подтвердил тип дочернего документа." }
                            val bytes = safProviderCall { cursor.getLong(3) }
                            val modified = safProviderCall { cursor.getLong(4) }
                            scope.remember(child, SafReadMetadata(childName, mime == DocumentsContract.Document.MIME_TYPE_DIR, bytes, modified))
                        }
                        children += childName to child
                    }
                } ?: error("Не удалось прочитать папку SAF.")
                checkGrant(source, false)
            }
        }
        currentCoroutineContext().ensureActive()
        children.sortedBy { it.first }.map { it.second }
    } }

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
                    catch (failure: Exception) { cleanupOwnedTarget(LocalFileSource.Local(file), failure); throw failure }
                }
                is LocalFileSource.Content -> createContentChild(parent, name, isDirectory)
            }
        }
    }

    private suspend fun createContentChild(parent: LocalFileSource.Content, name: String, directory: Boolean): LocalFileSource.Content {
        checkGrant(parent, true)
        val uri = requireNotNull(safProviderCall { DocumentsContract.createDocument(androidContext.contentResolver,
            documentUri(parent.uri), if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name) }) { "Не удалось создать документ." }
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

    suspend fun findChild(parent: LocalFileSource, name: String): LocalFileSource? = withContext(Dispatchers.IO) { withStorageTopology {
        validateChildName(name)
        currentCoroutineContext().ensureActive()
        when (parent) {
            is LocalFileSource.Local -> {
                val directory = checkFile(parent.file)
                require(directory.isDirectory) { "Папка назначения не найдена." }
                val child = checkFile(File(directory, name))
                if (child.exists()) LocalFileSource.Local(child) else null
            }
            is LocalFileSource.Content -> {
                // A lookup must not inherit the UI listing's 5000-row/materialization
                // budget. Stream the provider cursor until the exact child or EOF;
                // only EOF proves absence, while cancellation still aborts the lookup.
                checkGrant(parent, false)
                require(isDirectory(parent)) { "Папка назначения не найдена." }
                val id = DocumentsContract.getDocumentId(documentUri(parent.uri))
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, id)
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var found: LocalFileSource.Content? = null
                safProviderCall { androidContext.contentResolver.query(childrenUri, projection, null, null, null) }
                    ?.let(::SafCursor)?.use { cursor ->
                        while (safProviderCall { cursor.moveToNext() }) {
                            currentCoroutineContext().ensureActive()
                            if (safProviderCall { cursor.getString(1) } != name) continue
                            val childId = safProviderCall { cursor.getString(0) }
                            require(!childId.isNullOrBlank() && childId.length <= 8192) {
                                "Провайдер SAF вернул некорректный идентификатор."
                            }
                            found = LocalFileSource.Content(DocumentsContract.buildDocumentUriUsingTree(parent.uri, childId))
                            break
                        }
                    } ?: error("Не удалось прочитать папку SAF.")
                currentCoroutineContext().ensureActive()
                checkGrant(parent, false)
                found?.also { checkGrant(it, false) }
            }
        }
    } }

    suspend fun createDirectory(source: LocalFileSource): Boolean = withContext(Dispatchers.IO) {
        when (source) {
            is LocalFileSource.Local -> checkFile(source.file, true).let { it.mkdirs() || it.isDirectory }
            is LocalFileSource.Content -> isDirectory(source)
        }
    }

    suspend fun walk(
        source: LocalFileSource,
        recursive: Boolean = true,
        onRestrictedPathSkipped: ((String) -> Unit)? = null,
        limit: Int = MAX_ENTRIES,
        onTruncated: (() -> Unit)? = null,
    ): List<LocalFileSource> = withStorageTopology {
        require(limit in 0..MAX_ENTRIES) { "Некорректный лимит файлов." }
        val result = mutableListOf<LocalFileSource>()
        var wasTruncated = false
        fun truncated() {
            requireNotNull(onTruncated) { "Слишком много файлов." }
            if (!wasTruncated) { wasTruncated = true; onTruncated() }
        }
        suspend fun visit(current: LocalFileSource, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= MAX_DEPTH) { "Слишком глубокая структура папок." }
            for (entry in list(current, onRestrictedPathSkipped, limit - result.size, ::truncated)) {
                currentCoroutineContext().ensureActive()
                if (result.size >= limit) { truncated(); return }
                result += entry
                if (recursive && isDirectory(entry)) visit(entry, depth + 1)
            }
        }
        visit(source, 0)
        result
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
                is LocalFileSource.Content -> {
                    val uri = document(entry, true).uri
                    safProviderCall { DocumentsContract.deleteDocument(androidContext.contentResolver, uri) }
                }
            }
            require(deleted) { "Не удалось удалить файл." }
        }
        true
    }

    private suspend fun isTreeRoot(source: LocalFileSource.Content): Boolean = try {
        DocumentsContract.getTreeDocumentId(source.uri) == DocumentsContract.getDocumentId(document(source).uri)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { true }

    suspend fun copy(from: LocalFileSource, to: LocalFileSource, overwrite: Boolean = false, ownedDestination: Boolean = false): Long = withContext(Dispatchers.IO) {
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
                // A failed or incomplete opaque-ID traversal must abort before creating
                // destination entries; treating it as false could copy a directory into itself.
                val nested = from.uri.authority == to.uri.authority && (sourceId == targetId || targetId.startsWith("$sourceId/") || providerContains(from.uri, to.uri))
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
                    val unpublishedShared = previous == null && !permitsAutomaticRollback(destination)
                    val target = if (previous != null) previous else if (unpublishedShared) child(destination, entryName)
                    else withContext(NonCancellable) { createNewChild(destination, entryName, directory).also { owned = it } }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (owned != null) require(name(target) == entryName) { "Провайдер SAF изменил имя создаваемого файла." }
                        if (directory) {
                            if (unpublishedShared) require(createDirectory(target)) { "Не удалось создать папку назначения." }
                            copyDirectory(entry, target, depth + 1)
                        }
                        else total += copy(entry, target, overwrite || owned != null, ownedDestination = owned != null)
                    } catch (failure: Exception) {
                        owned?.let { cleanupOwnedTarget(it, failure, recursive = true) }
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
            stagedWrite(to, overwrite, false, ownedDestination) { output -> copyBounded(input, output) }
        }
    }

    private suspend fun validateMoveSource(from: LocalFileSource) {
        when (from) {
            is LocalFileSource.Local -> checkFile(from.file, true)
            is LocalFileSource.Content -> { checkGrant(from, true); require(!isTreeRoot(from)) { "Перемещение корня SAF запрещено." } }
        }
    }

    private suspend fun deleteMovedSource(from: LocalFileSource) {
        try {
            require(delete(from, recursive = true)) { "Источник не удалён." }
        } catch (failure: Exception) {
            val preserved = FileMoveSourceDeleteException(failure)
            if (failure is CancellationException) { failure.addSuppressed(preserved); throw failure }
            throw preserved
        }
    }

    /** Same-volume rename preserves the inode and never allocates a copy stage. */
    internal suspend fun tryLocalMove(from: LocalFileSource, to: LocalFileSource, overwrite: Boolean): Long? = withContext(Dispatchers.IO) {
        if (from !is LocalFileSource.Local || to !is LocalFileSource.Local) return@withContext null
        currentCoroutineContext().ensureActive()
        val source = checkFile(from.file, true)
        val target = checkFile(to.file, true)
        require(source != target) { "Источник совпадает с назначением." }
        require(source.exists()) { "Источник не найден." }
        val directory = source.isDirectory
        if (!directory && !source.isFile) return@withContext null
        require(!directory || !target.toPath().startsWith(source.toPath())) { "Нельзя перемещать папку внутрь себя." }
        // Directory merges retain their existing copy/delete semantics. Regular-file
        // replacements can keep the old inode beside the destination instead of
        // allocating an unbounded byte copy through scratch.
        require(!target.exists() || overwrite) { "Назначение уже существует. Укажите overwrite=true." }
        if (target.exists() && (directory || !target.isFile)) return@withContext null
        val parent = target.parentFile ?: return@withContext null
        var existingParent = checkFile(parent)
        while (!existingParent.exists()) existingParent = checkFile(existingParent.parentFile ?: return@withContext null)
        require(existingParent.isDirectory) { "Родительский путь назначения занят файлом." }
        val sameVolume = try { Files.getFileStore(source.toPath()) == Files.getFileStore(existingParent.toPath()) }
        catch (_: java.io.IOException) { false }
        if (!sameVolume) return@withContext null
        val bytes = if (directory) {
            // Renaming a tree must not bypass the checks required by copy/delete.
            // Walk is strict: forbidden or truncated descendants abort before rename.
            val descendants = walk(from)
            var total = 0L
            for (entry in descendants) {
                currentCoroutineContext().ensureActive()
                val child = checkFile((entry as LocalFileSource.Local).file, true)
                val relative = source.toPath().relativize(child.toPath())
                checkFile(target.toPath().resolve(relative).toFile(), true)
                if (!child.isDirectory) total = Math.addExact(total, child.length())
            }
            total
        } else source.length()
        // Recheck live capability and symlinks immediately before the filesystem action.
        checkFile(source, true)
        checkFile(target, true)
        currentCoroutineContext().ensureActive()
        createMoveParents(parent)
        checkFile(source, true)
        checkFile(target, true)
        currentCoroutineContext().ensureActive()
        if (target.exists()) {
            require(overwrite) { "Назначение уже существует. Укажите overwrite=true." }
            if (directory || !target.isFile) return@withContext null
            return@withContext replaceLocalMove(source, target, bytes)
        }
        try {
            Files.move(source.toPath(), target.toPath())
            bytes
        } catch (collision: java.nio.file.FileAlreadyExistsException) {
            require(overwrite) { "Назначение уже существует. Укажите overwrite=true." }
            null // Explicit overwrite uses the existing staged transaction.
        } catch (_: java.nio.file.FileSystemException) {
            null // Cross-device/unsupported rename keeps the copy-then-delete transaction.
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    /** Missing parents use the same guarded scope as the destination, never raw absolute routing. */
    private suspend fun createMoveParents(parent: File) {
        val missing = ArrayList<File>()
        var current = checkFile(parent)
        while (!current.exists()) {
            require(missing.size < MAX_DEPTH) { "Слишком глубокая структура папок." }
            missing += checkFile(current, true)
            current = checkFile(current.parentFile ?: error("Не удалось определить папку назначения."))
        }
        require(current.isDirectory) { "Родительский путь назначения занят файлом." }
        for (directory in missing.asReversed()) {
            currentCoroutineContext().ensureActive()
            val guarded = checkFile(directory, true)
            try { Files.createDirectory(guarded.toPath()) }
            catch (_: java.nio.file.FileAlreadyExistsException) {
                require(checkFile(directory).isDirectory) { "Родительский путь назначения занят файлом." }
            }
        }
    }

    /** Once the old target moves, every failure must restore it or retain its recovery path. */
    private suspend fun replaceLocalMove(source: File, target: File, bytes: Long): Long {
        val parent = requireNotNull(target.parentFile)
        val backup = File(parent, ".rk-move-${java.util.UUID.randomUUID()}.bak")
        val sourceKey = Files.readAttributes(checkFile(source).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey()
        val targetKey = Files.readAttributes(checkFile(target).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey()
        // A shared pathname may be replaced by another application between steps.
        // A provider without stable inode identity cannot use this transaction.
        require(sourceKey != null && targetKey != null) { "Хранилище не поддерживает безопасное перемещение с заменой." }
        fun sameInode(file: File, expected: Any): Boolean = Files.readAttributes(checkFile(file).toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == expected
        var backedUp = false
        var installed = false
        try {
            currentCoroutineContext().ensureActive()
            checkFile(source, true)
            checkFile(target, true)
            checkFile(backup, true)
            require(sameInode(source, sourceKey) && sameInode(target, targetKey)) { "Источник или назначение изменились во время перемещения." }
            currentCoroutineContext().ensureActive()
            // Neither move may replace an existing pathname. In particular an
            // unrelated file created at target after backup must survive failure.
            Files.move(target.toPath(), backup.toPath())
            backedUp = true
            currentCoroutineContext().ensureActive()
            checkFile(source, true)
            checkFile(target, true)
            checkFile(backup, true)
            require(sameInode(source, sourceKey) && sameInode(backup, targetKey)) { "Источник или резервная копия изменились во время перемещения." }
            require(!target.exists()) { "Назначение изменилось во время перемещения." }
            currentCoroutineContext().ensureActive()
            Files.move(source.toPath(), target.toPath())
            installed = true
            // Rename is the commit point. Cancellation after it keeps the accepted
            // result; do not move a new source back over a possibly raced pathname.
            withContext(NonCancellable) {
                checkFile(target, true)
                checkFile(backup, true)
                require(sameInode(target, sourceKey) && sameInode(backup, targetKey)) { "Назначение или резервная копия изменились; проверьте оба файла." }
                Files.delete(backup.toPath())
            }
            return bytes
        } catch (failure: Exception) {
            if (!backedUp) throw failure
            if (!installed) {
                try {
                    withContext(NonCancellable) {
                        checkFile(target, true)
                        checkFile(backup, true)
                        require(sameInode(backup, targetKey)) { "Резервная копия изменена другим приложением; файл сохранён для проверки." }
                        require(!target.exists()) { "Назначение занято другим файлом; резервная копия сохранена." }
                        Files.move(backup.toPath(), target.toPath())
                    }
                } catch (restoration: Exception) {
                    val recovery = FileMoveRecoveryException(backup.absolutePath, restoration, installed = false)
                    if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
                    recovery.addSuppressed(failure)
                    throw recovery
                }
                throw failure
            }
            // Source installation succeeded but cleanup failed (including revoked
            // storage access). Never delete the completed destination in response.
            if (!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) throw failure
            val recovery = FileMoveRecoveryException(backup.absolutePath, failure, installed = true)
            if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
            throw recovery
        }
    }

    suspend fun move(from: LocalFileSource, to: LocalFileSource, overwrite: Boolean = false, ownedDestination: Boolean = false): Long {
        validateMoveSource(from)
        if (!ownedDestination) tryLocalMove(from, to, overwrite)?.let { return it }
        val bytes = copy(from, to, overwrite, ownedDestination)
        deleteMovedSource(from)
        return bytes
    }

    suspend fun write(source: LocalFileSource, bytes: ByteArray, overwrite: Boolean = false, append: Boolean = false): Long = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_WRITE_BYTES) { "Запись превышает 16 МиБ." }
        require(!exists(source) || overwrite || append) { "Файл уже существует. Укажите overwrite=true или append=true." }
        stagedWrite(source, overwrite || append, append) { output -> output.write(bytes); bytes.size.toLong() }
    }

    private suspend fun stagedWrite(source: LocalFileSource, overwrite: Boolean, append: Boolean, ownedDestination: Boolean = false, write: suspend (OutputStream) -> Long): Long {
        require(!append || source is LocalFileSource.Local) { "Добавление к SAF-файлу не поддерживается." }
        val shared = source is LocalFileSource.Local && !permitsAutomaticRollback(source)
        val destinationParent = if (source is LocalFileSource.Local) checkFile(source.file, true).parentFile else scratchRoot
        // A new shared destination uses exclusive cross-filesystem installation, so its
        // stage stays private. Replacements keep a same-volume stage for atomic rename.
        val sharedStage = shared && overwrite
        val parent = if (shared && !overwrite) scratchRoot else destinationParent
        if (shared && !overwrite) {
            val directory = checkFile(requireNotNull(destinationParent))
            require(directory.mkdirs() || directory.isDirectory) { "Не удалось создать папку назначения. Проверьте доступ к памяти и свободное место." }
        }
        requireNotNull(parent).mkdirs()
        val staging = File.createTempFile(".rk-write-", ".tmp", parent)
        try {
            val stagedOutput = if (sharedStage) {
                checkFile(staging, true)
                GuardedSharedOutputStream(Files.newOutputStream(staging.toPath(), StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) { checkFile(staging, true) }
            } else staging.outputStream()
            val count = stagedOutput.use { output ->
                if (append && exists(source)) openInput(source).use { copyBounded(it, output) }
                write(output)
            }
            currentCoroutineContext().ensureActive()
            when (source) {
                is LocalFileSource.Local -> {
                    val target = checkFile(source.file, true)
                    if (sharedStage) checkFile(staging)
                    require(overwrite || !target.exists()) { "Файл уже существует." }
                    // ATOMIC_MOVE can replace a raced target without REPLACE_EXISTING.
                    // link(2) installs a complete same-filesystem inode exclusively;
                    // an existing target is preserved even under a creation race.
                    try { if (overwrite) Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        else if (shared) {
                            // Android's emulated storage and FAT cards cannot create hard links.
                            // CREATE_NEW still reserves the destination without replacing a raced file.
                            installNewSharedStorageFile(staging, target, verifyAccess = { checkFile(staging); checkFile(target, true) })
                        } else Files.createLink(target.toPath(), staging.toPath()) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        if (overwrite) Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        else throw IllegalStateException("Хранилище не поддерживает безопасное создание файла.")
                    }
                }
                is LocalFileSource.Content -> {
                    // Existing documents keep their original bytes before provider truncation.
                    // A cancelled or failed commit restores them under NonCancellable; if that
                    // fails the complete backup remains visible in scratch for recovery.
                    checkGrant(source, true)
                    require(!exists(source) || overwrite) { "Файл уже существует." }
                    if (ownedDestination) {
                        staging.inputStream().use { input -> openOutput(source).use { output -> copyBounded(input, output) } }
                        currentCoroutineContext().ensureActive()
                    } else {
                        val backup = File.createTempFile(".rk-restore-", ".bak", scratchRoot)
                        commitStreamWithRollback(staging, backup, { openInput(source) }, { openOutput(source) })
                    }
                }
            }
            return count
        } catch (failure: Exception) {
            if (sharedStage) {
                val partial = FileCreationCleanupFailedException(LocalFileSource.Local(staging), failure, sharedPathMayBePartial = true)
                if (failure is CancellationException) { failure.addSuppressed(partial); throw failure }
                throw partial
            }
            throw failure
        } finally {
            // Same-volume shared stages can also be replaced by another application.
            // A successful rename consumes ours; on failure the warning exposes its path.
            if (!sharedStage) staging.delete()
        }
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

/** Exclusive installation for shared storage; never unlink a pathname another app may replace. */
internal suspend fun installNewSharedStorageFile(
    staging: File,
    target: File,
    verifyAccess: () -> Unit,
    write: suspend (InputStream, OutputStream) -> Unit = { input, output ->
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            verifyAccess()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    },
) {
    verifyAccess()
    val path = target.toPath()
    // Creation failure means no ownership, so it deliberately remains outside cleanup.
    val output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    try {
        output.use {
            Files.newInputStream(staging.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input -> write(input, it) }
            currentCoroutineContext().ensureActive()
            verifyAccess()
        }
        verifySharedInstalledContents(staging, target, verifyAccess)
    } catch (failure: Exception) {
        // Even fstat(fd) followed by lstat(path) cannot make unlink(path) conditional
        // on the inode: a different app may replace the shared pathname between them.
        // Preserve the path and report the possible partial file instead of deleting it.
        val partial = FileCreationCleanupFailedException(LocalFileSource.Local(target), failure, sharedPathMayBePartial = true)
        if (failure is CancellationException) { failure.addSuppressed(partial); throw failure }
        throw partial
    }
}

/** A different app can replace the pathname while our exclusive output descriptor is open. */
private suspend fun verifySharedInstalledContents(staging: File, target: File, verifyAccess: () -> Unit) {
    verifyAccess()
    Files.newInputStream(staging.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { expected ->
        Files.newInputStream(target.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { actual ->
            val left = ByteArray(8192)
            val right = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                verifyAccess()
                val count = expected.read(left)
                if (count < 0) {
                    require(actual.read() < 0) { "Файл назначения изменён другим приложением. Проверьте его перед повтором." }
                    break
                }
                var offset = 0
                while (offset < count) {
                    currentCoroutineContext().ensureActive()
                    verifyAccess()
                    val read = actual.read(right, offset, count - offset)
                    require(read > 0) { "Файл назначения изменён другим приложением. Проверьте его перед повтором." }
                    offset += read
                }
                require((0 until count).all { left[it] == right[it] }) { "Файл назначения изменён другим приложением. Проверьте его перед повтором." }
            }
        }
    }
}

private class GuardedSharedInputStream(input: InputStream, private val verifyAccess: () -> Unit) : FilterInputStream(input) {
    override fun read(): Int { verifyAccess(); return `in`.read() }
    override fun read(buffer: ByteArray): Int { verifyAccess(); return `in`.read(buffer) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { verifyAccess(); return `in`.read(buffer, offset, length) }
    override fun skip(count: Long): Long { verifyAccess(); return `in`.skip(count) }
    override fun available(): Int { verifyAccess(); return `in`.available() }
    override fun reset() { verifyAccess(); `in`.reset() }
}

private class GuardedSharedOutputStream(output: OutputStream, private val verifyAccess: () -> Unit) : FilterOutputStream(output) {
    override fun write(value: Int) { verifyAccess(); out.write(value) }
    override fun write(buffer: ByteArray) { verifyAccess(); out.write(buffer) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { verifyAccess(); out.write(buffer, offset, length) }
    override fun flush() { verifyAccess(); out.flush() }
    override fun close() = out.close()
}

/** Preserve the provider exception class without carrying its message into tool results. */
private inline fun <T> safProviderCall(action: () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: SafFileOperationException) {
    throw failure
} catch (failure: Exception) {
    throw SafFileOperationException(failure)
}

private class SafCursor(private val cursor: Cursor) : Cursor by cursor {
    override fun close() = safProviderCall { cursor.close() }
}

private class SafInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = safProviderCall { `in`.read() }
    override fun read(buffer: ByteArray): Int = safProviderCall { `in`.read(buffer) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = safProviderCall { `in`.read(buffer, offset, length) }
    override fun skip(count: Long): Long = safProviderCall { `in`.skip(count) }
    override fun available(): Int = safProviderCall { `in`.available() }
    override fun reset() = safProviderCall { `in`.reset() }
    override fun close() = safProviderCall { `in`.close() }
}

private class SafOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun write(value: Int) = safProviderCall { out.write(value) }
    override fun write(buffer: ByteArray) = safProviderCall { out.write(buffer) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) = safProviderCall { out.write(buffer, offset, length) }
    override fun flush() = safProviderCall { out.flush() }
    override fun close() = safProviderCall { out.close() }
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

internal data class DocumentChild(val id: String, val isDirectory: Boolean)

internal suspend fun boundedDocumentReachable(
    rootId: String,
    targetId: String,
    maxEntries: Int = LocalFileAccess.MAX_ENTRIES,
    maxDepth: Int = LocalFileAccess.MAX_DEPTH,
    children: suspend (String, Int) -> List<DocumentChild>,
): Boolean {
    require(rootId.isNotBlank() && targetId.isNotBlank() && maxEntries >= 0 && maxDepth >= 0)
    currentCoroutineContext().ensureActive()
    if (rootId == targetId) return true
    val visited = mutableSetOf(rootId)
    val pending = ArrayDeque<Pair<String, Int>>()
    pending.addLast(rootId to 0)
    var remaining = maxEntries
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (id, depth) = pending.removeFirst()
        val entries = children(id, remaining)
        currentCoroutineContext().ensureActive()
        check(entries.size <= remaining) { "Слишком много документов для подтверждения границ папки SAF." }
        remaining -= entries.size // Duplicate rows consume budget too.
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            check(entry.id.isNotBlank() && entry.id.length <= 8192) { "Провайдер SAF вернул некорректный идентификатор." }
            if (entry.id == targetId) return true
            if (entry.isDirectory && visited.add(entry.id)) {
                check(depth < maxDepth) { "Структура SAF слишком глубокая для подтверждения границ папки." }
                pending.addLast(entry.id to depth + 1)
            }
        }
    }
    return false // Complete traversal proves that the target is unrelated.
}

internal const val NO_STORAGE_GRANT_MESSAGE =
    "Нет доступа к этой папке. Включите доступ ко всем файлам в Настройки → Разрешения и доступ, или выдайте папку через grant_directory_access (например, Download/Rikka)."

private class SafStoragePathNotFoundException : IllegalArgumentException("Файл или папка не найдены в выбранной папке SAF.")

internal data class FileWritePlan(val existing: LocalFileSource?, val parent: LocalFileSource.Content? = null, val name: String = "")

internal class FileCreationCleanupFailedException(
    val created: LocalFileSource,
    cause: Exception,
    val sharedPathMayBePartial: Boolean = false,
) : java.io.IOException(if (sharedPathMayBePartial)
    "Запись прервана. Файл мог сохраниться частично. Проверьте созданный файл перед повтором."
else "Не удалось убрать созданный документ после ошибки доступа.", cause)

internal class FileMoveSourceDeleteException(cause: Exception) :
    java.io.IOException("Копия сохранена, но источник не удалось полностью удалить.", cause)

internal class FileMoveRecoveryException(val backupPath: String, cause: Exception, val installed: Boolean) :
    java.io.IOException(if (installed) "Файл перемещён, но прежняя версия осталась в резервной копии: $backupPath"
        else "Не удалось восстановить прежнее имя файла. Резервная копия сохранена: $backupPath", cause)

internal fun isMoveCopyPreserved(error: Exception): Boolean = error is FileMoveSourceDeleteException ||
    error is FileMoveRecoveryException && error.installed ||
    error.suppressed.any { it is FileMoveSourceDeleteException || it is FileMoveRecoveryException && it.installed }

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
