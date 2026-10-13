package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.Base64

/** Reproduces the phone bug through the real persisted SAF permission list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, manifest = Config.NONE)
class SafAbsolutePathRoutingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

    private fun primary(context: Context): File = temporary.newFolder("primary").also { directory ->
        ShadowEnvironment.setExternalStorageDirectory(directory.toPath())
        val manager = context.getSystemService(StorageManager::class.java)
        shadowOf(manager).addStorageVolume(StorageVolumeBuilder("primary", directory, "Primary",
            Process.myUserHandle(), Environment.MEDIA_MOUNTED).setIsPrimary(true).setIsEmulated(true).build())
        File(directory, "Download").mkdirs()
    }

    private suspend fun denied(action: suspend () -> Any?) {
        try { action(); fail("Access escaped its held grant") } catch (_: IllegalArgumentException) { }
    }

    private fun body(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    private fun attachProvider(context: Context, authority: String, provider: DocumentsProvider) {
        val info = ProviderInfo().apply {
            this.authority = authority; name = provider.javaClass.name
            packageName = context.packageName; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider.attachInfo(context, info)
        // Android's ContentProvider transport converts the deprecated resolver query
        // overload to Bundle query arguments. Robolectric calls the provider overload
        // directly, but DocumentsProvider deliberately rejects it since Android O.
        val transport = ModernDocumentsQueryTransport(provider)
        transport.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, transport)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { providerInfo = info })
    }

    @Test fun persistedDownloadTreeRoutesItsCanonicalAbsolutePathThroughSaf(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val download = File(primary, "Download")
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = LocalFileAccess(context).resolve(download.canonicalPath)
        assertTrue("The granted external path must use ContentResolver, never local File I/O", source is LocalFileSource.Content)
        val uri = (source as LocalFileSource.Content).uri
        assertEquals(tree, DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
        assertEquals("primary:Download", DocumentsContract.getDocumentId(uri))
    }

    @Test fun selectedDescendantsKeepTheirTreeAndLiteralFileName(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val source = LocalFileAccess(context).resolve(File(primary, "Download/отчёт + %2e%2e.txt").absolutePath)
        assertTrue(source is LocalFileSource.Content)
        assertEquals("primary:Download/отчёт + %2e%2e.txt", DocumentsContract.getDocumentId((source as LocalFileSource.Content).uri))
    }

    @Test fun trustedSystemAliasUsesSafAndReadFileDoesNotRequireRawLinuxAccess(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val alias = File(temporary.root, "sdcard")
        Files.createSymbolicLink(alias.toPath(), primary.toPath())
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"),
            context = context, primaryStorageAliases = listOf(alias))
        val physical = File(primary, "Download/отчёт.txt")
        assertFalse("The bytes exist only in the document provider", physical.exists())
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Download/отчёт.txt")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("Данные SAF".toByteArray()))
        val canonical = access.resolve(physical.path) as LocalFileSource.Content
        assertEquals(canonical, access.resolve(File(alias, "Download/отчёт.txt").path))
        assertEquals(canonical, access.resolve(physical.toURI().toString()))
        val result = fileManagerTools(access).single { it.name == "read_file" }.execute(buildJsonObject {
            put("path", File(alias, "Download/отчёт.txt").path)
        })
        assertEquals("Данные SAF", body(result)["content"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { access.checkFile(physical) }
    }

    @Test fun readAndWriteFlagsRevocationAndPathBoundariesRemainAuthoritative(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val access = LocalFileAccess(context)
        val path = File(primary, "Download/file.txt").path
        denied { access.resolve(path) }
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val readSource = access.resolve(path)
        denied { access.resolve(path, write = true) }
        denied { access.openOutput(readSource) }
        denied { access.resolve(File(primary, "Download2/file.txt").path) }
        denied { access.resolve(File(primary, "Pictures/file.txt").path) }
        denied { access.resolve(File(primary, "Download/../Pictures/file.txt").path) }
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(primary, "Download/escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(primary, "Download/alias").toPath(), File(primary, "Download").toPath())
        denied { access.resolve(File(primary, "Download/escape/secret").path) }
        denied { access.resolve(File(primary, "Download/alias/file.txt").path) }
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        denied { access.openInput(readSource) }
        denied { access.resolve(path) }
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(access.resolve(path, write = true) is LocalFileSource.Content)
        denied { access.resolve(path) }
    }

    @Test fun downloadsProviderSuppliesOpaqueIdsForEachSelectedDescendant(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val authority = "com.android.providers.downloads.documents"
        val provider = OpaqueDownloadsProvider()
        attachProvider(context, authority, provider)
        val downloadTree = DocumentsContract.buildTreeDocumentUri(authority, "downloads")
        context.contentResolver.takePersistableUriPermission(downloadTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val access = LocalFileAccess(context)
        val root = access.resolve(File(primary, "Download").path) as LocalFileSource.Content
        assertEquals("downloads", DocumentsContract.getDocumentId(root.uri))
        val child = access.resolve(File(primary, "Download/folder/отчёт.txt").path) as LocalFileSource.Content
        assertEquals("msf:2", DocumentsContract.getDocumentId(child.uri))
        assertEquals("downloads", DocumentsContract.getTreeDocumentId(child.uri))
        assertTrue("Each path segment must be selected from the provider", provider.queriedParents.containsAll(listOf("downloads", "msd:1")))
        denied { access.resolve(File(primary, "Download/folder/missing.txt").path) }
        denied { access.resolve(File(primary, "Download2/отчёт.txt").path) }
        context.contentResolver.releasePersistableUriPermission(downloadTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        denied { access.openInput(child) }
    }

    @Test fun actualTextBinaryAndCopyToolsCommitSafBytesThroughAbsolutePaths(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val alias = File(temporary.root, "sdcard")
        Files.createSymbolicLink(alias.toPath(), primary.toPath())
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"),
            context = context, primaryStorageAliases = listOf(alias))
        val provider = MemoryStorageProvider(context, tree, mapOf("text.txt" to "original".toByteArray(),
            "binary.bin" to byteArrayOf(8), "source.txt" to "Источник SAF".toByteArray(), "copy.txt" to "old copy".toByteArray()))
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        fun physical(name: String) = File(primary, "Download/$name").path
        fun aliased(name: String) = File(alias, "Download/$name").path
        val original = access.resolve(physical("text.txt"))
        assertTrue("Metadata must come from the registered document provider", access.exists(original))
        val text = writeTextFileTool(access)
        assertNotNull(body(text.execute(buildJsonObject { put("path", aliased("text.txt")); put("content", "refused") }))["error"])
        assertEquals("original", provider.bytes("text.txt").toString(Charsets.UTF_8))
        assertTrue(body(text.execute(buildJsonObject { put("path", aliased("text.txt")); put("content", "Новый текст"); put("overwrite", true) }))["success"]!!.jsonPrimitive.boolean)
        assertEquals("Новый текст", provider.bytes("text.txt").toString(Charsets.UTF_8))
        val tools = fileManagerTools(access)
        val binary = byteArrayOf(0, 1, 2, -1)
        assertTrue(body(tools.single { it.name == "write_binary_file" }.execute(buildJsonObject {
            put("path", physical("binary.bin")); put("base64_content", Base64.getEncoder().encodeToString(binary)); put("overwrite", true)
        }))["success"]!!.jsonPrimitive.boolean)
        assertArrayEquals(binary, provider.bytes("binary.bin"))
        val copy = tools.single { it.name == "copy_file" }
        assertTrue(body(copy.execute(buildJsonObject { put("src", aliased("source.txt")); put("dst", physical("copy.txt")); put("overwrite", true) }))["success"]!!.jsonPrimitive.boolean)
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("copy.txt"))
        assertEquals(setOf("text.txt", "binary.bin", "copy.txt"), provider.openedOutputs.toSet())
        assertFalse(File(primary, "Download/text.txt").exists())
        assertFalse(File(primary, "Download/copy.txt").exists())
        // Existing transactional SAF writes need a readable original target; pathname routing
        // must not silently create a document or weaken backup/rollback for a missing target.
        assertNotNull(body(copy.execute(buildJsonObject { put("src", physical("source.txt")); put("dst", aliased("missing.txt")) }))["error"])
        assertFalse(provider.contains("missing.txt"))
        val created = access.createFile(access.resolve(File(alias, "Download").path), "created.txt")
        assertTrue(created is LocalFileSource.Content)
        assertTrue(body(text.execute(buildJsonObject { put("path", physical("created.txt")); put("content", "Создан SAF"); put("overwrite", true) }))["success"]!!.jsonPrimitive.boolean)
        assertEquals("Создан SAF", provider.bytes("created.txt").toString(Charsets.UTF_8))
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val preserved = provider.bytes("text.txt").copyOf()
        assertNotNull(body(text.execute(buildJsonObject { put("path", physical("text.txt")); put("content", "denied"); put("overwrite", true) }))["error"])
        assertNotNull(body(copy.execute(buildJsonObject { put("src", physical("source.txt")); put("dst", physical("text.txt")); put("overwrite", true) }))["error"])
        assertArrayEquals(preserved, provider.bytes("text.txt"))
    }

    private class ModernDocumentsQueryTransport(private val documents: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = documents.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }, null)
        override fun getType(uri: Uri): String? = documents.getType(uri)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = documents.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = documents.insert(uri, values)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = documents.delete(uri, selection, selectionArgs)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = documents.update(uri, values, selection, selectionArgs)
    }

    /** Bytes are reachable exclusively through the production ContentResolver stream operations. */
    private class MemoryStorageProvider(private val android: Context, private val tree: Uri,
        initial: Map<String, ByteArray>) : DocumentsProvider() {
        private val rootId = DocumentsContract.getTreeDocumentId(tree)
        private val contents = initial.mapKeys { (name, _) -> "$rootId/$name" }.toMutableMap()
        val openedOutputs = mutableListOf<String>()
        init { contents.keys.toList().forEach(::registerStreams) }
        fun bytes(name: String) = contents.getValue("$rootId/$name")
        fun contains(name: String) = "$rootId/$name" in contents
        private fun registerStreams(id: String) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            shadowOf(android.contentResolver).registerInputStreamSupplier(uri) {
                ByteArrayInputStream(contents[id] ?: throw FileNotFoundException(id))
            }
            shadowOf(android.contentResolver).registerOutputStreamSupplier(uri) {
                require(id in contents)
                openedOutputs += id.substringAfterLast('/')
                object : ByteArrayOutputStream() {
                    override fun close() { contents[id] = toByteArray(); super.close() }
                }
            }
        }
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            rows(listOf(documentId).filter { it == rootId || it in contents }, projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            rows(if (parentDocumentId == rootId) contents.keys.toList() else emptyList(), projection)
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = parentDocumentId == rootId && documentId in contents
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(parentDocumentId == rootId && mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
            val id = "$rootId/$displayName"
            require(id !in contents)
            contents[id] = byteArrayOf(); registerStreams(id)
            return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            throw FileNotFoundException(documentId)
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                ids.forEach { id -> addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id.substringAfterLast('/')
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == rootId) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> contents[id]?.size ?: 0
                    DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0
                    else -> null
                } }) }
            }
        }
    }

    /** Numeric IDs intentionally have no path-prefix relationship with the held Downloads root. */
    private class OpaqueDownloadsProvider : DocumentsProvider() {
        val queriedParents = mutableListOf<String>()
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(documentId), projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            queriedParents += parentDocumentId
            return rows(when (parentDocumentId) { "downloads" -> listOf("msd:1"); "msd:1" -> listOf("msf:2"); else -> emptyList() }, projection)
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
            parentDocumentId == "downloads" && documentId in listOf("msd:1", "msf:2") || parentDocumentId == "msd:1" && documentId == "msf:2"
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            throw FileNotFoundException(documentId)
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                ids.forEach { id -> addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> when (id) { "downloads" -> "Download"; "msd:1" -> "folder"; else -> "отчёт.txt" }
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "msf:2") "text/plain" else DocumentsContract.Document.MIME_TYPE_DIR
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS -> 0
                    else -> null
                } }) }
            }
        }
    }
}
