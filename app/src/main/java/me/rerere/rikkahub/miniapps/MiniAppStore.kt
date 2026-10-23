package me.rerere.rikkahub.miniapps

// Kotlin adaptation of Moru mini_app_store.dart and archive tests at
// 2101b5e457031d8adb1098dac880bdbb3019623f. IO is bounded while streaming;
// whole-directory staging/rollback preserves the previous installation on failure.
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class MiniAppStore(val rootDirectory: File, private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    // Lifecycle first, then reminder coordination, then the short file-state mutex.
    // Delete hooks run inside lifecycle ownership and must not acquire it again.
    internal suspend fun <T> withAppLifecycle(block: suspend () -> T): T = lifecycleMutex.withLock { block() }
    private var loaded = false
    private val catalog = MutableStateFlow<List<MiniApp>>(emptyList())
    val apps: StateFlow<List<MiniApp>> = catalog.asStateFlow()
    private val events = MutableSharedFlow<MiniAppStorageChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<MiniAppStorageChange> = events.asSharedFlow()
    private val deleteHooks = mutableListOf<suspend (String) -> Unit>()
    fun addDeleteHook(hook: suspend (String) -> Unit) { synchronized(deleteHooks) { deleteHooks.add(hook) } }
    fun byId(id: String): MiniApp? = catalog.value.find { it.id == id }
    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { mutex.withLock { loadLocked(); block() } }
    suspend fun load() { io { } }
    private fun loadLocked() {
        if (loaded) return
        rootDirectory.mkdirs()
        requireRegularRoot()
        catalog.value = rootDirectory.listFiles().orEmpty().mapNotNull { dir ->
            if (dir.name.startsWith('.') || Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) return@mapNotNull null
            try {
                safe(dir.resolve("manifest.json"), dir)
                val app = parseManifest(readObject(dir.resolve("manifest.json"), maxBytes), dir, true)
                if (app.id != dir.name) null else app
            } catch (_: Exception) { null }
        }.sortedByDescending { it.updatedAt }
        loaded = true
    }
    private fun requireRegularRoot() {
        if (!rootDirectory.isDirectory || Files.isSymbolicLink(rootDirectory.toPath())) fail("invalid_path", "Unsafe app directory.")
    }
    private fun requireApp(id: String): MiniApp {
        val app = byId(id) ?: fail("not_found", "No mini app \"$id\".")
        safe(app.directory, rootDirectory)
        return app
    }
    suspend fun install(source: File): MiniAppInstallResult = withAppLifecycle { io { installLocked(source) } }
    private fun installLocked(source: File): MiniAppInstallResult {
        if (!source.isDirectory || Files.isSymbolicLink(source.toPath())) fail("invalid_path", "Unsafe source directory.")
        val sourceManifest = source.resolve(manifestFile)
        if (!sourceManifest.exists()) fail("missing_manifest", "The folder has no ${Companion.manifestFile}.")
        safe(sourceManifest, source)
        val manifest = try { readObject(sourceManifest, maxBytes) } catch (e: MiniAppException) { throw e } catch (_: Exception) { fail("invalid_manifest", "Invalid moru-app.json.") }
        val parsed = parseManifest(manifest, rootDirectory, false)
        val destination = rootDirectory.resolve(parsed.id)
        if (destination.exists()) safe(destination, rootDirectory)
        val app = parsed.copy(directory = destination, updatedAt = System.currentTimeMillis())
        val staging = Files.createTempDirectory(rootDirectory.toPath(), ".${app.id}-").toFile()
        val backup = rootDirectory.resolve(".${app.id}-backup-${UUID.randomUUID()}")
        var count = 0
        var bytes = 0L
        try {
            val code = staging.resolve("app").apply { mkdirs() }
            fun walk(dir: File) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith('.') || name in skippedDirectories) return@forEach
                    safe(file, source)
                    if (file.isDirectory) { walk(file); return@forEach }
                    if (!file.isFile) fail("invalid_path", "Unsupported app file.")
                    val relative = file.relativeTo(source).invariantSeparatorsPath
                    if (relative == Companion.manifestFile || relative == bridgeFile) return@forEach
                    if (++count > maxFiles) fail("too_many_files", "A mini app may have at most $maxFiles files.")
                    val target = code.resolve(relative)
                    target.parentFile!!.mkdirs()
                    file.inputStream().use { input -> target.outputStream().use { output -> bytes += copyBounded(input, output, maxBytes - bytes) } }
                } ?: fail("invalid_path", "Cannot read source directory.")
            }
            walk(source)
            if (!code.resolve(app.entry).isFile) fail("missing_entry", "Entry file \"${app.entry}\" not found.")
            app.icon?.let { if (!code.resolve(it).isFile) fail("missing_icon", "Icon file not found."); if (!it.endsWith(".svg", true)) fail("invalid_icon", "The icon must be an SVG.") }
            code.resolve(bridgeFile).writeText(MiniAppBridgeScript.source)
            if (app.entry.substringAfterLast('.').lowercase() in setOf("html", "htm")) {
                val entry = code.resolve(app.entry)
                entry.writeText(withBridgeScript(entry.readText(), app.entry))
            }
            staging.resolve("manifest.json").writeText(app.toJson().toString())
            // Carry only private state across publication; staged code never includes it.
            for (name in listOf("data.json", "reminders.json")) {
                val old = destination.resolve(name)
                if (old.exists()) { safe(old, destination); old.inputStream().use { input -> staging.resolve(name).outputStream().use { copyBounded(input, it, maxDataBytes.toLong()) } } }
            }
            val updated = byId(app.id) != null
            if (destination.exists()) Files.move(destination.toPath(), backup.toPath(), ATOMIC_MOVE)
            try { Files.move(staging.toPath(), destination.toPath(), ATOMIC_MOVE) }
            catch (e: Exception) { if (backup.exists()) Files.move(backup.toPath(), destination.toPath(), ATOMIC_MOVE); throw e }
            catalog.value = (catalog.value.filterNot { it.id == app.id } + app).sortedByDescending { it.updatedAt }
            backup.deleteRecursively()
            return MiniAppInstallResult(app, updated, count, bytes)
        } finally { staging.deleteRecursively() }
    }
    suspend fun delete(id: String): Unit = withAppLifecycle {
        load()
        if (byId(id) == null) return@withAppLifecycle
        synchronized(deleteHooks) { deleteHooks.toList() }.forEach { it(id) }
        io { val app = requireApp(id); if (!app.directory.deleteRecursively()) throw IOException("Cannot delete mini app."); catalog.value = catalog.value.filterNot { it.id == id } }
    }
    suspend fun refreshBridge(app: MiniApp) { io { val installed = requireApp(app.id); val file = installed.codeDirectory.resolve(bridgeFile); safe(file, installed.directory); atomicWrite(file, MiniAppBridgeScript.source.toByteArray()) } }
    suspend fun storageGet(id: String, key: String): JsonElement = storageAll(id)[key] ?: JsonNull
    suspend fun storageKeys(id: String): List<String> = storageAll(id).keys.toList()
    suspend fun storageAll(id: String): JsonObject = io { state(requireApp(id), "data.json") }
    suspend fun storageSet(id: String, key: String, value: JsonElement, fromApp: Boolean = false) {
        if (key.isEmpty() || key.length > maxKeyLength) fail("invalid_key", "Keys must be 1-$maxKeyLength characters.")
        update(id, key, fromApp) { it[key] = value }
    }
    suspend fun storageRemove(id: String, key: String, fromApp: Boolean = false) { update(id, key, fromApp) { it.remove(key) } }
    private suspend fun update(id: String, key: String, fromApp: Boolean, change: (MutableMap<String, JsonElement>) -> Unit) {
        io { val app = requireApp(id); val data = state(app, "data.json").toMutableMap(); change(data); writeState(app, "data.json", JsonObject(data)) }
        if (!fromApp) events.emit(MiniAppStorageChange(id, key))
    }
    suspend fun readReminders(id: String): JsonObject = io { state(requireApp(id), "reminders.json") }
    suspend fun writeReminders(id: String, reminders: JsonObject) { io { writeState(requireApp(id), "reminders.json", reminders) } }
    private fun state(app: MiniApp, name: String): JsonObject {
        val file = app.directory.resolve(name)
        safe(file, app.directory)
        return if (file.exists()) readObject(file, maxDataBytes) else JsonObject(emptyMap())
    }
    private fun writeState(app: MiniApp, name: String, value: JsonObject) {
        val bytes = value.toString().toByteArray()
        if (bytes.size > maxDataBytes) fail("storage_full", "App data may not exceed 5 MB.")
        val file = app.directory.resolve(name); safe(file, app.directory); atomicWrite(file, bytes)
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File.createTempFile(".write-", ".tmp", file.parentFile)
        try { FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { temp.delete() }
    }
    suspend fun exportArchive(id: String, withData: Boolean, into: File): File = io {
        val app = requireApp(id)
        into.mkdirs()
        val temp = File.createTempFile(".export-", ".tmp", into)
        val target = into.resolve("$id$archiveExtension")
        try {
            ZipOutputStream(temp.outputStream()).use { zip ->
                fun add(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                add(manifestFile, JsonObject(app.toJson().filterKeys { it != "updatedAt" }).toString().toByteArray())
                var count = 0
                var total = 0L
                fun walk(dir: File) {
                    dir.listFiles()?.forEach { file ->
                        safe(file, app.codeDirectory)
                        if (file.isDirectory) { walk(file); return@forEach }
                        if (!file.isFile) fail("invalid_path", "Unsupported app file.")
                        val name = file.relativeTo(app.codeDirectory).invariantSeparatorsPath
                        if (name == bridgeFile) return@forEach
                        if (++count > maxFiles) fail("too_many_files", "Too many app files.")
                        zip.putNextEntry(ZipEntry(name))
                        if (name == app.entry && name.substringAfterLast('.').lowercase() in setOf("html", "htm")) {
                            // Export the source, not the host-generated insertion. Keeping the
                            // strict source bound makes exact-limit apps round-trip repeatedly.
                            val tag = generatedBridgeTag(app.entry)
                            val encoded = ByteArrayOutputStream()
                            file.inputStream().use { copyBounded(it, encoded, maxBytes.toLong() + tag.toByteArray().size) }
                            val source = encoded.toString("UTF-8").replaceFirst(tag, "").toByteArray()
                            total += copyBounded(ByteArrayInputStream(source), zip, maxBytes - total)
                        } else {
                            file.inputStream().use { total += copyBounded(it, zip, maxBytes - total) }
                        }
                        zip.closeEntry()
                    } ?: fail("invalid_path", "Cannot read app files.")
                }
                walk(app.codeDirectory)
                if (withData) state(app, "data.json").takeIf { it.isNotEmpty() }?.let { add(archiveDataFile, it.toString().toByteArray()) }
            }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            target
        } finally { temp.delete() }
    }
    suspend fun importArchive(file: File): MiniAppImportResult = withAppLifecycle { io {
        // Snapshot the bounded archive first, so subsequent caller modifications cannot bypass checks.
        val snapshot = File.createTempFile(".archive-", ".zip", rootDirectory)
        val staging = Files.createTempDirectory(rootDirectory.toPath(), ".import-").toFile()
        try {
            file.inputStream().use { input -> snapshot.outputStream().use { copyBounded(input, it, (maxBytes + maxDataBytes).toLong()) } }
            rejectArchiveLinks(snapshot)
            var count = 0
            var bytes = 0L
            val names = mutableSetOf<String>()
            try {
                ZipFile(snapshot).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.replace('\\', '/').split('/').any { it == ".." }) fail("invalid_archive", "Archive traversal is forbidden.")
                        val name = relative(entry.name, null) ?: continue
                        if (!names.add(name)) fail("invalid_archive", "Duplicate archive paths.")
                        if (entry.isDirectory) continue
                        if (++count > maxFiles + 2) fail("too_large", "Too many archive files.")
                        val target = staging.resolve(name)
                        target.parentFile!!.mkdirs()
                        val remaining = minOf((maxBytes + maxDataBytes).toLong() - bytes, if (name == archiveDataFile) maxDataBytes.toLong() else maxBytes.toLong())
                        zip.getInputStream(entry).use { input -> target.outputStream().use { bytes += copyBounded(input, it, remaining) } }
                    }
                }
            } catch (e: MiniAppException) { throw e } catch (_: Exception) { fail("invalid_archive", "This is not a Moru app file.") }
            if (!staging.resolve(manifestFile).isFile) fail("invalid_archive", "This is not a Moru app file.")
            val dataFile = staging.resolve(archiveDataFile)
            val data = if (dataFile.exists()) try { readObject(dataFile, maxDataBytes) } catch (_: Exception) { fail("invalid_archive", "The app data in the file is damaged.") } else null
            dataFile.delete()
            val installed = installLocked(staging)
            var restored = false
            if (data != null && data.isNotEmpty() && state(installed.app, "data.json").isEmpty()) {
                writeState(installed.app, "data.json", data)
                restored = true
            }
            MiniAppImportResult(installed.app, installed.updated, restored)
        } finally { snapshot.delete(); staging.deleteRecursively() }
    } }
    // java.util.zip does not expose Unix file modes; inspect the central directory
    // to reject symbolic links rather than accidentally importing them as files.
    private fun rejectArchiveLinks(file: File) {
        val data = file.readBytes() // already bounded snapshot
        fun u16(i: Int) = (data[i].toInt() and 255) or ((data[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        var end = data.size - 22
        while (end >= maxOf(0, data.size - 65557) && !(data[end] == 0x50.toByte() && data[end + 1] == 0x4b.toByte() && data[end + 2] == 5.toByte() && data[end + 3] == 6.toByte())) end--
        if (end < maxOf(0, data.size - 65557) || end < 0) fail("invalid_archive", "Invalid ZIP directory.")
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 10) == 65535) fail("invalid_archive", "Unsupported ZIP directory.")
        var offset = u32(end + 16)
        repeat(u16(end + 10)) {
            if (offset < 0 || offset + 46 > end) fail("invalid_archive", "Invalid ZIP directory.")
            val i = offset.toInt()
            if (u32(i) != 0x02014b50L) fail("invalid_archive", "Invalid ZIP directory.")
            val mode = ((u32(i + 38) ushr 16) and 0xf000).toInt()
            if (mode != 0 && mode != 0x8000 && mode != 0x4000) fail("invalid_archive", "Archive links and special files are forbidden.")
            offset += 46 + u16(i + 28) + u16(i + 30) + u16(i + 32)
        }
    }
    private fun parseManifest(json: JsonObject, directory: File, installed: Boolean): MiniApp {
        fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val id = text("id")
        if (!idPattern.matches(id)) fail("invalid_id", "ID must use 1-40 lowercase letters, digits or hyphens, starting with a letter or digit.")
        val name = text("name")
        if (name.isEmpty() || name.length > 40) fail("invalid_name", "\"name\" must be 1-40 characters.")
        val network = json["network"]?.takeUnless { it == JsonNull }?.let { raw ->
            val list = raw as? JsonArray ?: fail("invalid_network", "Network must be a list of hosts.")
            if (list.size > maxHosts) fail("invalid_network", "Too many network hosts.")
            list.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty().trim().lowercase().also { host -> if (!hostPattern.matches(host)) fail("invalid_network", "Invalid network host.") } }.distinct()
        }.orEmpty()
        val permissions = json["permissions"]?.takeUnless { it == JsonNull }?.let { raw ->
            val list = raw as? JsonArray ?: fail("invalid_permissions", "Permissions must be a list.")
            list.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty().trim().also { permission -> if (permission !in knownPermissions) fail("invalid_permissions", "Unknown permission.") } }.toSet()
        }.orEmpty()
        return MiniApp(id, name, directory, text("description"), relative(text("entry"), "index.html")!!,
            relative(text("icon"), null), text("data").take(2000), network, permissions,
            if (installed) (json["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0 else 0)
    }
    private fun readObject(file: File, limit: Int): JsonObject {
        val output = ByteArrayOutputStream()
        file.inputStream().use { copyBounded(it, output, limit.toLong()) }
        return Json.parseToJsonElement(output.toString("UTF-8")) as? JsonObject ?: fail("invalid_manifest", "Expected JSON object.")
    }
    private fun safe(file: File, root: File) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(rootPath)) fail("invalid_path", "Path leaves app directory.")
        var current = rootPath
        if (Files.isSymbolicLink(current)) fail("invalid_path", "Symbolic links are forbidden.")
        for (part in rootPath.relativize(path)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) fail("invalid_path", "Symbolic links are forbidden.")
        }
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)) fail("invalid_path", "Special files are forbidden.")
    }
    companion object {
        const val manifestFile = "moru-app.json"
        const val bridgeFile = "moru.js"
        const val maxFiles = 500
        const val maxBytes = 20 * 1024 * 1024
        const val maxDataBytes = 5 * 1024 * 1024
        const val maxKeyLength = 200
        const val maxHosts = 20
        const val archiveExtension = ".moruapp"
        const val archiveDataFile = ".moru-data.json"
        val skippedDirectories = setOf("node_modules", ".git", "__pycache__")
        val knownPermissions = setOf("calendar")
        private val idPattern = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
        private val hostPattern = Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
        fun linkFor(id: String) = "rikkahub://app/$id"
        fun idFromLink(link: String): String? = try {
            val uri = URI(link)
            val path = uri.rawPath.orEmpty()
            if (!uri.scheme.equals("rikkahub", true) || !uri.host.equals("app", true) || uri.port != -1 || uri.userInfo != null) null
            else path.removePrefix("/").removeSuffix("/").takeIf { idPattern.matches(it) }
        } catch (_: Exception) { null }
        fun allowsHost(app: MiniApp, host: String): Boolean {
            val name = host.lowercase()
            return app.network.any { allowed -> if (allowed.startsWith("*.")) name == allowed.substring(2) || name.endsWith(allowed.substring(1)) else name == allowed }
        }
        fun withBridgeScript(html: String, entry: String): String {
            if (html.contains(bridgeFile)) return html
            val tag = generatedBridgeTag(entry)
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            return if (head == null) tag + html else html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
        }
        private fun generatedBridgeTag(entry: String): String {
            val src = "../".repeat(entry.count { it == '/' }) + bridgeFile
            return "<script src=\"$src\"></script><!--rikka-mini-app-bridge:v1-->"
        }
        private fun relative(raw: String?, fallback: String?): String? {
            val value = raw.orEmpty().trim().replace('\\', '/')
            if (value.isEmpty()) return fallback
            if (value.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(value) || '\u0000' in value) fail("invalid_path", "Path leaves app directory.")
            val parts = mutableListOf<String>()
            for (part in value.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) fail("invalid_path", "Path leaves app directory.") else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
            return parts.joinToString("/").takeIf { it.isNotEmpty() } ?: fail("invalid_path", "Empty app path.")
        }
        private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
            var total = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return total
                total += read
                if (total > limit) fail("too_large", "The app is too large.")
                output.write(buffer, 0, read)
            }
        }
        private fun fail(code: String, message: String): Nothing = throw MiniAppException(code, message)
    }
}
