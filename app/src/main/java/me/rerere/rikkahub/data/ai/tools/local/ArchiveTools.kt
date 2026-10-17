// Adapted from ExTV/rikkahub-agent local/ArchiveTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

internal data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxEntryBytes > 0 && maxTotalBytes > 0 && maxArchiveBytes > 0) }
}
internal class ArchiveSafetyException(message: String) : Exception(message)
internal data class ArchiveStats(val entries: Int, val bytes: Long)
internal data class ArchiveSource(val entryName: String, val isDirectory: Boolean = false, val open: suspend () -> InputStream)

/** A child component never reaches LocalFileAccess before this structural check. */
internal fun archiveEntryParts(entryName: String): List<String> {
    val name = entryName.replace('\\', '/')
    val stripped = name.removeSuffix("/")
    val parts = stripped.split('/')
    if (name.length > 4096 || stripped.isEmpty() || name.startsWith('/') || parts.size > 64 ||
        parts.any { it.isEmpty() || it == "." || it == ".." || it.length > 255 || ':' in it || it.any { c -> c.code < 32 || c.code == 127 } }) {
        throw ArchiveSafetyException("В архиве найден небезопасный путь.")
    }
    return parts
}

internal fun isUnsafeZipEntry(entryName: String): Boolean = try { archiveEntryParts(entryName); false }
    catch (_: ArchiveSafetyException) { true }

internal class ArchiveBudget(private val limits: ArchiveLimits) {
    var totalBytes: Long = 0; private set
    private var entryBytes = 0L
    fun beginEntry(declaredSize: Long = -1) {
        entryBytes = 0
        if (declaredSize > limits.maxEntryBytes) throw ArchiveSafetyException("Размер записи архива превышает допустимый предел.")
    }
    fun consume(bytes: Int) {
        if (bytes.toLong() > limits.maxEntryBytes - entryBytes || bytes.toLong() > limits.maxTotalBytes - totalBytes) {
            throw ArchiveSafetyException("Архив превышает допустимый объём распаковки.")
        }
        entryBytes += bytes
        totalBytes += bytes
    }
}

internal suspend fun copyArchiveEntry(input: InputStream, output: OutputStream, budget: ArchiveBudget): Long {
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) return copied
        if (read == 0) continue
        budget.consume(read)
        output.write(buffer, 0, read)
        copied += read
    }
}

private class BoundedArchiveInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var bytes = 0L
    private fun count(read: Int): Int {
        if (read > 0) {
            if (read.toLong() > limit - bytes) throw ArchiveSafetyException("Размер файла архива превышает допустимый предел.")
            bytes += read
        }
        return read
    }
    override fun read(): Int { val value = `in`.read(); if (value >= 0) count(1); return value }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(`in`.read(buffer, offset, length))
}
private class BoundedArchiveOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var bytes = 0L
    private fun count(length: Int) {
        if (length.toLong() > limit - bytes) throw ArchiveSafetyException("Размер создаваемого архива превышает допустимый предел.")
        bytes += length
    }
    override fun write(value: Int) { count(1); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { count(length); out.write(buffer, offset, length) }
}
private val archiveSink = object : OutputStream() { override fun write(value: Int) {} ; override fun write(buffer: ByteArray, offset: Int, length: Int) {} }

/** Listing also drains through the byte budget; closeEntry must never bypass bomb limits. */
internal suspend fun readZipArchive(
    input: InputStream,
    limits: ArchiveLimits = ArchiveLimits(),
    consume: suspend (ZipEntry, InputStream, ArchiveBudget) -> Unit,
): ArchiveStats = input.use { raw ->
    val guarded = PushbackInputStream(BufferedInputStream(BoundedArchiveInput(raw, limits.maxArchiveBytes)), 4)
    val signature = ByteArray(4)
    var signatureBytes = 0
    while (signatureBytes < 4) {
        coroutineContext.ensureActive()
        val read = guarded.read(signature, signatureBytes, 4 - signatureBytes)
        if (read < 0) break
        signatureBytes += read
    }
    if (signatureBytes != 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4b.toByte() ||
        !(signature[2] == 3.toByte() && signature[3] == 4.toByte() || signature[2] == 5.toByte() && signature[3] == 6.toByte())) {
        throw ArchiveSafetyException("Файл не является поддерживаемым ZIP-архивом.")
    }
    guarded.unread(signature)
    ZipInputStream(guarded, Charsets.UTF_8).use { zip ->
        val budget = ArchiveBudget(limits)
        val names = HashSet<String>()
        var count = 0
        while (true) {
            coroutineContext.ensureActive()
            val entry = zip.nextEntry ?: break
            if (++count > limits.maxEntries) throw ArchiveSafetyException("В архиве слишком много записей.")
            val name = archiveEntryParts(entry.name).joinToString("/")
            if (!names.add(name)) throw ArchiveSafetyException("В архиве повторяется путь к файлу или папке.")
            budget.beginEntry(entry.size)
            consume(entry, zip, budget)
            copyArchiveEntry(zip, archiveSink, budget)
            zip.closeEntry()
        }
        ArchiveStats(count, budget.totalBytes)
    }
}

internal suspend fun writeZipArchive(
    sources: List<ArchiveSource>,
    output: OutputStream,
    compressionLevel: Int,
    limits: ArchiveLimits = ArchiveLimits(),
): ArchiveStats = output.use { raw -> ZipOutputStream(BoundedArchiveOutput(raw, limits.maxArchiveBytes)).use { zip ->
    require(compressionLevel in 0..9)
    if (sources.size > limits.maxEntries) throw ArchiveSafetyException("Слишком много файлов для одного архива.")
    zip.setLevel(compressionLevel)
    val budget = ArchiveBudget(limits)
    val names = HashSet<String>()
    var count = 0
    for (source in sources) {
        coroutineContext.ensureActive()
        val name = archiveEntryParts(source.entryName).joinToString("/")
        if (!names.add(name)) throw ArchiveSafetyException("Исходные файлы имеют одинаковые имена в архиве. Укажите base_dir или измените список источников.")
        zip.putNextEntry(ZipEntry(if (source.isDirectory) "$name/" else name))
        budget.beginEntry()
        if (!source.isDirectory) source.open().use { copyArchiveEntry(it, zip, budget) }
        zip.closeEntry()
        count++
    }
    ArchiveStats(count, budget.totalBytes)
} }

private fun archiveParts(value: JsonObject) = listOf(UIMessagePart.Text(value.toString()))
private fun archiveError(message: String) = archiveParts(buildJsonObject { put("error", message) })
private class ArchiveRecoveryWarning(message: String) : Exception(message)
private suspend fun archiveOperation(operation: FileToolOperation, action: suspend () -> JsonObject): List<UIMessagePart> = withContext(Dispatchers.IO) {
    try {
        var recoveryWarning: String? = null
        val result = withTimeoutOrNull(180_000) {
            try { action() }
            catch (cancelled: CancellationException) {
                recoveryWarning = cancelled.suppressed.filterIsInstance<ArchiveRecoveryWarning>().firstOrNull()?.message
                throw cancelled
            }
        }
        if (result == null) archiveError(listOfNotNull(
            "Время операции с архивом истекло. Повторите запрос с меньшим архивом.", recoveryWarning,
        ).joinToString(" ")) else archiveParts(result)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (unsafe: ArchiveSafetyException) {
        FileOperationDiagnostics.record(operation, unsafe)
        archiveError(unsafe.message ?: "Архив не прошёл проверку безопасности.")
    }
    catch (error: Exception) {
        (safFileCause(error) as? CancellationException)?.let { throw it }
        FileOperationDiagnostics.record(operation, error)
        archiveError(if (safFileCause(error) != null) deviceToolFailureReason(error, operation)
        else "Не удалось выполнить операцию с архивом. Проверьте ZIP-файл, свободное место и доступ к выбранным папкам.")
    }
}
private fun JsonObject.archivePath(key: String): String = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 4096 }
    ?: throw ArchiveSafetyException("Укажите корректный путь в параметре $key.")

private suspend fun createArchiveScratch(access: LocalFileAccess): LocalFileSource.Local {
    val source = access.resolve("/scratch/.archive-${UUID.randomUUID()}", write = true) as? LocalFileSource.Local
        ?: throw ArchiveSafetyException("Недоступна временная папка для архива.")
    val file = access.checkFile(source.file, write = true)
    if (!file.mkdirs()) throw ArchiveSafetyException("Не удалось создать временную папку для архива.")
    return LocalFileSource.Local(access.checkFile(file, write = true))
}
private fun scratchFile(access: LocalFileAccess, scratch: LocalFileSource.Local, name: String): LocalFileSource.Local =
    LocalFileSource.Local(access.checkFile(File(scratch.file, name), write = true))

/** Scratch files are flat and can exceed LocalFileAccess's general recursive-walk ceiling. */
private suspend fun cleanArchiveScratch(access: LocalFileAccess, scratch: LocalFileSource.Local) = withContext(NonCancellable) {
    try {
        var count = 0
        Files.newDirectoryStream(access.checkFile(scratch.file).toPath()).use { children ->
            for (path in children) {
                check(++count <= 20_001)
                val child = LocalFileSource.Local(access.checkFile(path.toFile(), write = true))
                check(!access.isDirectory(child))
                check(access.delete(child))
            }
        }
        access.delete(scratch)
    } catch (_: Exception) { false }
}

private fun createArchiveDirectories(access: LocalFileAccess, target: LocalFileSource.Local, created: MutableList<LocalFileSource>) {
    val missing = ArrayList<File>()
    var directory = access.checkFile(target.file)
    while (!directory.exists()) {
        if (missing.size >= 64) throw ArchiveSafetyException("Слишком глубокий путь папки назначения.")
        missing.add(access.checkFile(directory, write = true))
        directory = directory.parentFile ?: throw ArchiveSafetyException("Не удалось определить папку назначения.")
        access.checkFile(directory)
    }
    if (!directory.isDirectory) throw ArchiveSafetyException("Родительский путь назначения занят файлом.")
    for (file in missing.asReversed()) {
        if (!file.mkdir()) throw ArchiveSafetyException("Не удалось создать папку назначения.")
        created.add(LocalFileSource.Local(access.checkFile(file, write = true)))
    }
}

internal suspend fun collectArchiveSources(access: LocalFileAccess, roots: List<LocalFileSource>, base: LocalFileSource?): List<ArchiveSource> {
    val sources = ArrayList<ArchiveSource>()
    val seenDirectories = HashSet<String>()
    var visited = 0
    suspend fun walk(source: LocalFileSource, entryName: String, depth: Int) {
        coroutineContext.ensureActive()
        if (++visited > 10_000 || depth > 64) throw ArchiveSafetyException("Слишком много файлов или слишком глубокая структура папок.")
        archiveEntryParts(entryName)
        if (!access.exists(source)) throw ArchiveSafetyException("Один из исходных файлов не существует или недоступен.")
        if (source is LocalFileSource.Local) {
            if (Files.isSymbolicLink(source.file.toPath())) throw ArchiveSafetyException("Символические ссылки нельзя добавлять в архив.")
            access.checkFile(source.file)
        }
        if (!access.isDirectory(source)) {
            sources.add(ArchiveSource(entryName) { access.openInput(source) })
            return
        }
        if (!seenDirectories.add(source.reference)) throw ArchiveSafetyException("Обнаружен повторный или циклический путь к папке.")
        sources.add(ArchiveSource(entryName, isDirectory = true) { throw IllegalStateException("Папка не содержит потока") })
        if (source is LocalFileSource.Local) {
            Files.newDirectoryStream(access.checkFile(source.file).toPath()).use { children ->
                for (path in children) {
                    coroutineContext.ensureActive()
                    if (Files.isSymbolicLink(path)) throw ArchiveSafetyException("Символические ссылки нельзя добавлять в архив.")
                    val child = LocalFileSource.Local(access.checkFile(path.toFile()))
                    walk(child, "$entryName/${path.fileName}", depth + 1)
                }
            }
        } else {
            val children = access.list(source, limit = minOf(LocalFileAccess.MAX_ENTRIES, 10_000 - visited),
                onTruncated = { throw ArchiveSafetyException("Слишком много файлов для одного архива.") })
            for (child in children) walk(child, "$entryName/${access.name(child)}", depth + 1)
        }
    }
    for (root in roots) {
        val name = if (base != null) {
            if (base !is LocalFileSource.Local || root !is LocalFileSource.Local) throw ArchiveSafetyException("base_dir поддерживается только для файлов выбранного рабочего пространства.")
            val baseFile = access.checkFile(base.file)
            val rootFile = access.checkFile(root.file)
            if (!rootFile.toPath().startsWith(baseFile.toPath()) || rootFile == baseFile) throw ArchiveSafetyException("Источник должен находиться внутри base_dir.")
            rootFile.relativeTo(baseFile).path.replace(File.separatorChar, '/')
        } else access.name(root)
        walk(root, name, 0)
    }
    return sources
}

private data class ArchiveChange(val target: LocalFileSource, val backup: LocalFileSource.Local?)

/** Android document creation must become rollback-visible before cancellation can resume. */
internal suspend fun <T> recordArchiveCreation(create: suspend () -> T, record: (T) -> Unit): T {
    val created = withContext(NonCancellable) { create().also(record) }
    coroutineContext.ensureActive()
    return created
}

internal suspend fun createOwnedArchiveChild(
    access: LocalFileAccess,
    parent: LocalFileSource,
    name: String,
    isDirectory: Boolean,
    record: (LocalFileSource) -> Unit,
): LocalFileSource {
    val created = recordArchiveCreation(
        create = { if (isDirectory) access.createNewChild(parent, name, isDirectory = true) else access.createFile(parent, name) },
        record = record,
    )
    // Record the returned URI before querying metadata: refusal and cancellation must
    // only roll back the document that this operation actually created.
    if (access.name(created) != name) throw ArchiveSafetyException("Провайдер SAF изменил имя создаваемого файла или папки.")
    return created
}

private suspend fun backupArchiveTarget(access: LocalFileAccess, target: LocalFileSource, scratch: LocalFileSource.Local, index: Int, budget: ArchiveBudget): LocalFileSource.Local {
    val backup = scratchFile(access, scratch, "backup-$index")
    budget.beginEntry(access.size(target))
    access.openInput(target).use { input -> access.openOutput(backup).use { output ->
        copyArchiveEntry(input, output, budget)
    } }
    return backup
}

/** Rollback is best-effort on remote providers; keep backups if Android refuses restoration. */
private suspend fun rollbackArchiveChanges(access: LocalFileAccess, changes: List<ArchiveChange>, directories: List<LocalFileSource>): Boolean = withContext(NonCancellable) {
    var restored = true
    for (change in changes.asReversed()) {
        if (!access.permitsAutomaticRollback(change.target)) { restored = false; continue }
        try {
            if (change.backup == null) {
                if (access.exists(change.target) && !access.delete(change.target)) restored = false
            } else access.copy(change.backup, change.target, overwrite = true)
        } catch (_: Exception) { restored = false }
    }
    for (directory in directories.asReversed()) {
        if (!access.permitsAutomaticRollback(directory)) { restored = false; continue }
        try {
            if (access.exists(directory) && access.list(directory).isEmpty() && !access.delete(directory)) restored = false
        } catch (_: Exception) { restored = false }
    }
    restored
}

private fun archiveRollbackWarning(
    access: LocalFileAccess,
    changes: List<ArchiveChange>,
    directories: List<LocalFileSource>,
    scratch: LocalFileSource.Local,
    failure: Throwable,
): String {
    val targets = (changes.map { it.target } + directories).distinctBy { it.reference }
    val partialPaths = targets.mapTo(linkedSetOf()) { it.reference }
    val scratchPath = "/scratch/${scratch.file.name}"
    val recoveryPaths = changes.mapNotNullTo(linkedSetOf()) { it.backup?.let { backup -> "$scratchPath/${backup.file.name}" } }
    // Lower file-access layers may retain a same-volume stage or a SAF restore
    // backup. Keep these paths when replacing their exception with archive guidance.
    val pending = ArrayDeque<Throwable>().apply { add(failure) }
    val visited = HashSet<Throwable>()
    while (pending.isNotEmpty() && visited.size < 64) {
        val error = pending.removeFirst()
        if (!visited.add(error)) continue
        when (error) {
            is FileCreationCleanupFailedException -> partialPaths.add(error.created.reference)
            is FileRestoreFailedException -> recoveryPaths.add("/scratch/${error.backup.name}")
        }
        error.cause?.let(pending::addLast)
        error.suppressed.forEach(pending::addLast)
    }
    val reason = if (targets.any { !access.permitsAutomaticRollback(it) })
        "Операция прервана. В общей памяти могли остаться частичные файлы: проверьте их перед повтором."
    else "Android не смог полностью восстановить файлы или удалить частичные результаты."
    return buildString {
        append(reason)
        if (partialPaths.isNotEmpty()) append(" Пути назначения и частичных файлов: ${partialPaths.joinToString(", ")}.")
        append(" Временные файлы сохранены в $scratchPath.")
        if (recoveryPaths.isNotEmpty()) append(" Резервные копии: ${recoveryPaths.joinToString(", ")}.")
    }
}

fun zipFilesTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "zip_files",
    description = "Создать ZIP из файлов и папок рабочего пространства, временной области или явно выданных SAF-папок. Предпочитайте этот встроенный инструмент вместо workspace_exec для создания ZIP: он не требует установки zip/unzip через apt или другой менеджер пакетов. sources и destination поддерживают /workspace/..., относительные пути от текущей папки рабочего пространства (без рабочего пространства — от /scratch), /scratch/..., file:// и content://. base_dir задаёт локальную базовую папку для имён записей. overwrite по умолчанию false; для замены существующего ZIP укажите overwrite=true. compression_level: 0–9, по умолчанию 6. Максимум 10 000 записей, 2 ГиБ данных и 512 МиБ ZIP." + ASSISTANT_FILE_DIRECTORY_HINT,
    parameters = { InputSchema.Obj(buildJsonObject {
        put("sources", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
        put("destination", buildJsonObject { put("type", "string") })
        put("overwrite", buildJsonObject { put("type", "boolean"); put("default", false) })
        put("base_dir", buildJsonObject { put("type", "string") })
        put("compression_level", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 9) })
    }, listOf("sources", "destination")) },
    needsApproval = { true },
    execute = { input -> archiveOperation(FileToolOperation.ZIP) {
        val obj = input.jsonObject
        val references = obj["sources"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: throw ArchiveSafetyException("sources должен содержать пути к файлам.") }
            ?: throw ArchiveSafetyException("Укажите sources.")
        if (references.isEmpty() || references.size > 10_000) throw ArchiveSafetyException("Укажите от 1 до 10 000 источников.")
        val destinationPlan = access.planWritePath(obj.archivePath("destination"))
        if (destinationPlan.existing?.let { access.isDirectory(it) } == true) throw ArchiveSafetyException("destination должен указывать файл ZIP, а не папку.")
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!overwrite && destinationPlan.existing?.let { access.exists(it) } == true) {
            throw ArchiveSafetyException("Файл назначения уже существует. Для замены ZIP укажите overwrite=true.")
        }
        val roots = references.map { access.resolve(it) }
        val base = obj["base_dir"]?.jsonPrimitive?.contentOrNull?.let { access.resolve(it) }
        val level = obj["compression_level"]?.jsonPrimitive?.intOrNull ?: 6
        if (level !in 0..9) throw ArchiveSafetyException("compression_level должен быть от 0 до 9.")
        val sources = collectArchiveSources(access, roots, base)
        val scratch = createArchiveScratch(access)
        var retainBackups = false
        val changes = ArrayList<ArchiveChange>()
        val createdDirectories = ArrayList<LocalFileSource>()
        val backupBudget = ArchiveBudget(ArchiveLimits())
        try {
            val staged = scratchFile(access, scratch, "archive.zip")
            val stats = writeZipArchive(sources, access.openOutput(staged), level)
            val result = access.withWriteTarget(destinationPlan) { destination, created ->
                val exists = !created && access.exists(destination)
                if (exists && !overwrite) throw ArchiveSafetyException("Файл назначения уже существует. Для замены ZIP укажите overwrite=true.")
                val backup = if (exists) backupArchiveTarget(access, destination, scratch, 0, backupBudget) else null
                if (destination is LocalFileSource.Local) {
                    val parent = destination.file.parentFile ?: throw ArchiveSafetyException("Не удалось определить папку назначения.")
                    createArchiveDirectories(access, LocalFileSource.Local(parent), createdDirectories)
                }
                // Reserve a new private local file exclusively before taking rollback
                // ownership. A raced existing file must never become ours to delete.
                val reserveLocal = !created && !exists && destination is LocalFileSource.Local && access.permitsAutomaticRollback(destination)
                val target = if (reserveLocal) {
                    val local = destination as LocalFileSource.Local
                    createOwnedArchiveChild(access, LocalFileSource.Local(requireNotNull(local.file.parentFile)), local.file.name, false,
                        record = { changes.add(ArchiveChange(it, null)) })
                } else destination.also { changes.add(ArchiveChange(it, backup)) }
                access.copy(staged, target, overwrite = overwrite || created || reserveLocal, ownedDestination = created || reserveLocal)
                coroutineContext.ensureActive()
                buildJsonObject { put("success", true); put("bytes_written", stats.bytes); put("entry_count", stats.entries) }
            }.second
            changes.clear()
            createdDirectories.clear()
            result
        } catch (failure: Throwable) {
            if (!rollbackArchiveChanges(access, changes, createdDirectories)) {
                retainBackups = true
                val warning = archiveRollbackWarning(access, changes, createdDirectories, scratch, failure)
                val cancelled = failure as? CancellationException ?: (failure as? Exception)?.let { safFileCause(it) as? CancellationException }
                if (cancelled != null) {
                    cancelled.addSuppressed(ArchiveRecoveryWarning(warning))
                    throw cancelled
                }
                throw ArchiveSafetyException(warning)
            }
            throw failure
        } finally {
            if (!retainBackups) cleanArchiveScratch(access, scratch)
        }
    } },
)

private data class ExtractionItem(val parts: List<String>, val directory: Boolean, val staged: LocalFileSource.Local?)

private suspend fun safeArchiveChild(access: LocalFileAccess, parent: LocalFileSource, name: String): LocalFileSource? {
    if (parent is LocalFileSource.Local && Files.isSymbolicLink(File(parent.file, name).toPath())) {
        throw ArchiveSafetyException("Папка назначения содержит символическую ссылку.")
    }
    return access.findChild(parent, name)
}
private fun checkExtractionRoot(access: LocalFileAccess, root: LocalFileSource, target: LocalFileSource) {
    if (root is LocalFileSource.Local && target is LocalFileSource.Local) {
        val rootFile = access.checkFile(root.file)
        val targetFile = access.checkFile(target.file, write = true)
        if (!targetFile.toPath().startsWith(rootFile.toPath())) throw ArchiveSafetyException("Путь записи выходит за пределы папки распаковки.")
    }
}

fun unzipFileTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "unzip_file",
    description = "Распаковать ZIP в разрешённую папку. Предпочитайте этот встроенный инструмент вместо workspace_exec для распаковки ZIP: он не требует установки zip/unzip через apt или другой менеджер пакетов. source и destination_dir поддерживают /workspace/..., относительные пути от текущей папки рабочего пространства (без рабочего пространства — от /scratch), /scratch/..., file:// и content:// явно выданных SAF-папок. overwrite по умолчанию false. Небезопасные пути, символические ссылки и слишком большие архивы отклоняются. При отмене частичные новые файлы в workspace и SAF удаляются по возможности. При прямом доступе к памяти проверьте папку назначения: могли остаться частичные файлы." + ASSISTANT_FILE_DIRECTORY_HINT,
    parameters = { InputSchema.Obj(buildJsonObject {
        put("source", buildJsonObject { put("type", "string") })
        put("destination_dir", buildJsonObject { put("type", "string") })
        put("overwrite", buildJsonObject { put("type", "boolean") })
    }, listOf("source", "destination_dir")) },
    needsApproval = { true },
    execute = { input -> archiveOperation(FileToolOperation.UNZIP) {
        val obj = input.jsonObject
        val source = access.resolve(obj.archivePath("source"))
        val destinationReference = obj.archivePath("destination_dir")
        val root = access.resolve(destinationReference).also {
            if (it is LocalFileSource.Content) access.resolve(destinationReference, write = true)
        }
        if (access.exists(root) && !access.isDirectory(root)) throw ArchiveSafetyException("destination_dir должен указывать папку.")
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        val scratch = createArchiveScratch(access)
        val items = ArrayList<ExtractionItem>()
        val changes = ArrayList<ArchiveChange>()
        val createdDirectories = ArrayList<LocalFileSource>()
        val backupBudget = ArchiveBudget(ArchiveLimits())
        var retainBackups = false
        try {
            val stats = readZipArchive(access.openInput(source)) { entry, zip, budget ->
                val parts = archiveEntryParts(entry.name)
                val staged = if (entry.isDirectory) null else scratchFile(access, scratch, "entry-${items.size}").also {
                    access.openOutput(it).use { output -> copyArchiveEntry(zip, output, budget) }
                }
                items.add(ExtractionItem(parts, entry.isDirectory, staged))
            }
            // Reject file/directory conflicts and excessive implicit directories before touching the target.
            val filePaths = items.filterNot { it.directory }.map { it.parts.joinToString("/") }.toHashSet()
            val directoryPaths = HashSet<String>()
            for (item in items) {
                val directoryCount = if (item.directory) item.parts.size else item.parts.size - 1
                for (count in 1..directoryCount) {
                    val path = item.parts.take(count).joinToString("/")
                    if (path in filePaths || directoryPaths.add(path) && directoryPaths.size > 10_000) {
                        throw ArchiveSafetyException("В архиве конфликтуют файлы и папки либо слишком много вложенных папок.")
                    }
                }
            }
            if (root is LocalFileSource.Local && !access.permitsAutomaticRollback(root)) {
                // Validate every shared destination before publishing the first file. In
                // particular a later Android/data entry must not leave earlier entries behind.
                for (item in items) {
                    coroutineContext.ensureActive()
                    access.checkFile(File(root.file, item.parts.joinToString(File.separator)), write = true)
                }
            }
            if (!access.exists(root)) {
                val local = root as? LocalFileSource.Local ?: throw ArchiveSafetyException("Выберите существующую SAF-папку назначения.")
                createArchiveDirectories(access, local, createdDirectories)
            }
            val directories = hashMapOf("" to root)
            suspend fun directory(parts: List<String>): LocalFileSource {
                var parent = root
                for (index in parts.indices) {
                    coroutineContext.ensureActive()
                    val key = parts.take(index + 1).joinToString("/")
                    val cached = directories[key]
                    if (cached != null) { parent = cached; continue }
                    val existing = safeArchiveChild(access, parent, parts[index])
                    val child = existing ?: createOwnedArchiveChild(access, parent, parts[index], isDirectory = true,
                        record = { createdDirectories.add(it) })
                    if (!access.isDirectory(child)) throw ArchiveSafetyException("Имя папки архива занято файлом в папке назначения.")
                    checkExtractionRoot(access, root, child)
                    directories[key] = child
                    parent = child
                }
                return parent
            }
            for (item in items) {
                coroutineContext.ensureActive()
                if (item.directory) { directory(item.parts); continue }
                val parent = directory(item.parts.dropLast(1))
                val existing = safeArchiveChild(access, parent, item.parts.last())
                if (existing != null && (!overwrite || access.isDirectory(existing))) {
                    throw ArchiveSafetyException("Файл назначения уже существует. Для замены файлов укажите overwrite=true.")
                }
                val backup = existing?.let { backupArchiveTarget(access, it, scratch, changes.size, backupBudget) }
                val unpublishedShared = existing == null && !access.permitsAutomaticRollback(parent)
                val target = if (existing != null) existing.also { changes.add(ArchiveChange(it, backup)) }
                    else if (unpublishedShared) access.child(parent, item.parts.last()).also { changes.add(ArchiveChange(it, null)) }
                    else createOwnedArchiveChild(access, parent, item.parts.last(), isDirectory = false,
                        record = { changes.add(ArchiveChange(it, null)) })
                checkExtractionRoot(access, root, target)
                access.copy(item.staged ?: error("Нет временного файла"), target, overwrite = overwrite || !unpublishedShared)
            }
            coroutineContext.ensureActive()
            changes.clear()
            createdDirectories.clear()
            buildJsonObject { put("success", true); put("entries_extracted", items.count { !it.directory }); put("bytes_written", stats.bytes) }
        } catch (failure: Throwable) {
            if (!rollbackArchiveChanges(access, changes, createdDirectories)) {
                retainBackups = true
                val warning = archiveRollbackWarning(access, changes, createdDirectories, scratch, failure)
                val cancelled = failure as? CancellationException ?: (failure as? Exception)?.let { safFileCause(it) as? CancellationException }
                if (cancelled != null) {
                    cancelled.addSuppressed(ArchiveRecoveryWarning(warning))
                    throw cancelled
                }
                throw ArchiveSafetyException(warning)
            }
            throw failure
        } finally {
            if (!retainBackups) cleanArchiveScratch(access, scratch)
        }
    } },
)

fun listZipContentsTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "list_zip_contents",
    description = "Показать записи ZIP без распаковки в пользовательскую папку. Предпочитайте этот встроенный инструмент вместо workspace_exec для просмотра ZIP: он не требует установки zip/unzip через apt или другой менеджер пакетов. source поддерживает /workspace/..., относительные пути от текущей папки рабочего пространства (без рабочего пространства — от /scratch), /scratch/..., file:// и content:// явно выданных SAF-папок. limit ограничивает число записей в ответе: 1–10 000, по умолчанию 1000; truncated сообщает об усечении ответа. Весь ZIP проверяется независимо от limit: максимум 10 000 записей, 2 ГиБ распакованных данных и 512 МиБ ZIP." + ASSISTANT_FILE_DIRECTORY_HINT,
    parameters = { InputSchema.Obj(buildJsonObject {
        put("source", buildJsonObject { put("type", "string") })
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 10_000); put("default", 1000) })
    }, listOf("source")) },
    needsApproval = { true },
    execute = { input -> archiveOperation(FileToolOperation.LIST_ZIP) {
        val obj = input.jsonObject
        val limit = if ("limit" in obj) obj["limit"]?.jsonPrimitive?.intOrNull
            ?: throw ArchiveSafetyException("limit должен быть целым числом от 1 до 10 000.") else 1000
        if (limit !in 1..10_000) throw ArchiveSafetyException("limit должен быть целым числом от 1 до 10 000.")
        val source = access.resolve(obj.archivePath("source"))
        val entries = ArrayList<ZipEntry>()
        // Limit retained metadata only. readZipArchive still validates and drains
        // every hidden entry with the same count, path and byte budgets.
        val stats = readZipArchive(access.openInput(source)) { entry, _, _ -> if (entries.size < limit) entries.add(entry) }
        val active = coroutineContext
        buildJsonObject {
            put("truncated", stats.entries > entries.size)
            put("entries", buildJsonArray {
                for (entry in entries) {
                    active.ensureActive()
                    addJsonObject {
                        put("name", entry.name)
                        put("size", entry.size)
                        put("compressed_size", entry.compressedSize)
                        put("is_dir", entry.isDirectory)
                        put("modified_at_unix_ms", entry.time)
                        put("crc32", entry.crc)
                    }
                }
            })
        }
    } },
)
