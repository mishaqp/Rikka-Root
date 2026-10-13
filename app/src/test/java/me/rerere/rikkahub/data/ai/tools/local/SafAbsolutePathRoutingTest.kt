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
import kotlinx.coroutines.*
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
        // New absolute-path documents use provider creation; existing ones retain rollback.
        assertTrue(body(copy.execute(buildJsonObject { put("src", physical("source.txt")); put("dst", aliased("missing.txt")) }))["success"]!!.jsonPrimitive.boolean)
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("missing.txt"))
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

    private suspend fun withNewFileFixture(providerTree: Uri = tree, action: suspend (Context, LocalFileAccess, MemoryStorageProvider, (String) -> String) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val primary = primary(context)
        val provider = MemoryStorageProvider(context, providerTree, mapOf("source.txt" to "Источник".toByteArray()),
            opaqueIds = providerTree.authority == "com.android.providers.downloads.documents")
        attachProvider(context, providerTree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(providerTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val alias = File(temporary.root, "sdcard")
        Files.createSymbolicLink(alias.toPath(), primary.toPath())
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"),
            context = context, primaryStorageAliases = listOf(alias))
        action(context, access, provider) { File(alias, "Download/$it").path }
        assertFalse(File(primary, "Download/new.txt").exists())
    }

    private suspend fun assertDirectoryCopyIntoGrantedRoot(providerTree: Uri, rootId: String) { withNewFileFixture(providerTree) { _, access, provider, path ->
        assertTrue(access.createDirectory(access.resolve("/scratch/source", write = true)))
        val bytes = "Копия папки".toByteArray()
        access.write(access.resolve("/scratch/source/a.txt", write = true), bytes)
        val destination = File(path("a.txt")).parent!!
        val copy = fileManagerTools(access).single { it.name == "copy_file" }
        denied { access.resolve(File(destination).parent!!, write = true) }
        assertNotNull(body(copy.execute(buildJsonObject { put("src", "/scratch/source"); put("dst", destination) }))["error"])
        assertTrue("Default refusal must not create children", provider.createdNames.isEmpty())
        val result = body(copy.execute(buildJsonObject { put("src", "/scratch/source"); put("dst", destination); put("overwrite", true) }))
        assertEquals("Granted root directory copy failed: $result", "true", result["success"]?.jsonPrimitive?.content)
        assertEquals(rootId, DocumentsContract.getDocumentId(Uri.parse(result["to"]!!.jsonPrimitive.content)))
        assertArrayEquals(bytes, provider.bytes("a.txt"))
        assertEquals(listOf("a.txt"), provider.createdNames)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
        assertTrue(access.exists(access.resolve("/scratch/source/a.txt")))
        assertFalse(File(destination, "a.txt").exists())
    } }

    @Test fun copiesDirectoryIntoGrantedRootWithoutGrantOnItsParent(): Unit = runBlocking {
        assertDirectoryCopyIntoGrantedRoot(tree, "primary:Download")
    }

    @Test fun copiesDirectoryIntoGrantedDownloadsRootWithOpaqueChildren(): Unit = runBlocking {
        val downloads = DocumentsContract.buildTreeDocumentUri("com.android.providers.downloads.documents", "downloads")
        assertDirectoryCopyIntoGrantedRoot(downloads, "downloads")
    }

    @Test fun createsNewTextDocumentAtAbsoluteSafPath(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        val result = body(writeTextFileTool(access).execute(buildJsonObject { put("path", path("new.txt")); put("content", "Новый") }))
        assertEquals("Text creation failed: $result", "true", result["success"]?.jsonPrimitive?.content)
        assertEquals("Новый", provider.bytes("new.txt").toString(Charsets.UTF_8))
    } }

    @Test fun createsNewBinaryDocumentAtAbsoluteSafPath(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        val result = body(fileManagerTools(access).single { it.name == "write_binary_file" }.execute(buildJsonObject {
            put("path", path("new.bin")); put("base64_content", "AAEC/w==")
        }))
        assertEquals("Binary creation failed: $result", "true", result["success"]?.jsonPrimitive?.content)
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), provider.bytes("new.bin"))
    } }

    @Test fun createsNewCopyDocumentAtAbsoluteSafPath(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject { put("src", path("source.txt")); put("dst", path("copy-new.txt")) }))
        assertEquals("Copy creation failed: $result", "true", result["success"]?.jsonPrimitive?.content)
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("copy-new.txt"))
    } }

    @Test fun createsNewZipDocumentAtAbsoluteSafPath(): Unit = runBlocking { withNewFileFixture { context, access, provider, path ->
        val result = body(zipFilesTool(context, access).execute(buildJsonObject {
            put("sources", buildJsonArray { add(path("source.txt")) }); put("destination", path("new.zip"))
        }))
        assertEquals("Zip creation failed: $result", "true", result["success"]?.jsonPrimitive?.content)
        java.util.zip.ZipInputStream(ByteArrayInputStream(provider.bytes("new.zip"))).use {
            assertEquals("source.txt", it.nextEntry.name)
            assertArrayEquals(provider.bytes("source.txt"), it.readBytes())
        }
    } }

    @Test fun createsNewDownloadsProviderChildUsingProviderLookup(): Unit = runBlocking {
        val downloads = DocumentsContract.buildTreeDocumentUri("com.android.providers.downloads.documents", "downloads")
        withNewFileFixture(downloads) { _, access, provider, path ->
            val result = body(writeTextFileTool(access).execute(buildJsonObject { put("path", path("new.txt")); put("content", "Downloads") }))
            assertEquals("Downloads creation failed: $result", "true", result["success"]?.jsonPrimitive?.content)
            assertEquals("Downloads", provider.bytes("new.txt").toString(Charsets.UTF_8))
            val createdUri = Uri.parse(result["path"]!!.jsonPrimitive.content)
            assertEquals("downloads", DocumentsContract.getTreeDocumentId(createdUri))
            assertTrue("New Downloads IDs must come from the provider", DocumentsContract.getDocumentId(createdUri).startsWith("msf:"))
        }
    }

    @Test fun newDocumentPreflightPreservesBoundsFlagsAndExistingFiles(): Unit = runBlocking { withNewFileFixture { context, access, provider, path ->
        val tool = writeTextFileTool(access)
        suspend fun refused(reference: String, append: Boolean = false) {
            assertNotNull(body(tool.execute(buildJsonObject { put("path", reference); put("content", "blocked"); put("append", append) }))["error"])
        }
        refused(path("source.txt"))
        refused(path("new.txt"), append = true)
        refused(path("missing-parent/new.txt"))
        refused(path("../Pictures/new.txt"))
        refused(path("new.txt").replace("/Download/", "/Download2/"))
        val primary = File(path("new.txt")).parentFile!!.canonicalFile
        Files.createSymbolicLink(File(primary, "linked.txt").toPath(), File(temporary.root, "unrelated.txt").toPath())
        refused(path("linked.txt"))
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        refused(path("new.txt"))
        assertTrue("Rejected preflight must not create any document", provider.createdNames.isEmpty())
        assertTrue(provider.deletedNames.isEmpty())
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun refusesProviderAutoRenameAndDeletesOnlyTheRenamedNewDocument(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        provider.createdNameOverride = "new (1).txt"
        val result = body(writeTextFileTool(access).execute(buildJsonObject { put("path", path("new.txt")); put("content", "new") }))
        assertNotNull(result["error"])
        assertEquals(listOf("new (1).txt"), provider.createdNames)
        assertFalse(provider.contains("new (1).txt"))
        assertFalse(provider.contains("new.txt"))
        assertEquals(listOf("new (1).txt"), provider.deletedNames)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun failedNewDocumentCommitDeletesOnlyItsOwnedDocument(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        provider.afterOutputClose = { if (it == "failed.txt") throw java.io.IOException("provider commit failed") }
        val result = body(writeTextFileTool(access).execute(buildJsonObject { put("path", path("failed.txt")); put("content", "partial") }))
        assertNotNull(result["error"])
        assertTrue("Must reach the new-document commit", provider.createdNames.contains("failed.txt"))
        assertFalse(provider.contains("failed.txt"))
        assertEquals(listOf("failed.txt"), provider.deletedNames)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun cancelledNewDocumentCommitDeletesOnlyItsOwnedDocument(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        provider.afterOutputClose = { if (it == "cancelled.txt") throw kotlinx.coroutines.CancellationException("cancelled provider commit") }
        try {
            writeTextFileTool(access).execute(buildJsonObject { put("path", path("cancelled.txt")); put("content", "partial") })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue("Must reach the new-document commit", provider.createdNames.contains("cancelled.txt"))
        assertFalse(provider.contains("cancelled.txt"))
        assertEquals(listOf("cancelled.txt"), provider.deletedNames)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun providerCleanupRefusalReportsOnlyTheOwnedDocumentForRecovery(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        provider.refuseDelete = true
        provider.afterOutputClose = { if (it == "recover.txt") throw java.io.IOException("provider commit failed") }
        val result = body(writeTextFileTool(access).execute(buildJsonObject { put("path", path("recover.txt")); put("content", "partial") }))
        assertEquals("creation_cleanup_failed", result["error"]!!.jsonPrimitive.content)
        val created = Uri.parse(result["created_path"]!!.jsonPrimitive.content)
        assertEquals("primary:Download/recover.txt", DocumentsContract.getDocumentId(created))
        assertTrue(provider.contains("recover.txt"))
        assertTrue(provider.deletedNames.isEmpty())
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
        assertTrue(access.list(access.resolve("/scratch")).isEmpty())
    } }

    @Test fun cancellationAtCreationAndOutputCloseCleansOwnedDocuments(): Unit = runBlocking { withNewFileFixture { _, access, provider, path ->
        suspend fun cancelAt(name: String, creation: Boolean) {
            lateinit var job: Job
            val cancel: (String) -> Unit = { if (it == name) job.cancel() }
            provider.afterCreate = if (creation) cancel else null
            provider.afterOutputClose = if (creation) null else cancel
            job = launch(start = CoroutineStart.LAZY) {
                writeTextFileTool(access).execute(buildJsonObject { put("path", path(name)); put("content", "partial") })
                fail("Cancelled write returned normally")
            }
            job.start(); job.join()
            assertTrue(job.isCancelled)
            assertTrue(provider.createdNames.contains(name))
            assertFalse(provider.contains(name))
            assertTrue(provider.deletedNames.contains(name))
        }
        cancelAt("create-cancelled.txt", creation = true)
        cancelAt("close-cancelled.txt", creation = false)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
        assertTrue("New-document cancellation must leave no staging or empty-original backups", access.list(access.resolve("/scratch")).isEmpty())
    } }

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
        initial: Map<String, ByteArray>, private val opaqueIds: Boolean = false) : DocumentsProvider() {
        private val rootId = DocumentsContract.getTreeDocumentId(tree)
        private var nextId = 100 + initial.size
        private val idsByName = initial.keys.mapIndexed { index, name -> name to if (opaqueIds) "msf:${100 + index}" else "$rootId/$name" }.toMap().toMutableMap()
        private val contents = initial.mapKeys { (name, _) -> idsByName.getValue(name) }.toMutableMap()
        private fun displayName(id: String) = idsByName.entries.firstOrNull { it.value == id }?.key ?: id.substringAfterLast('/')
        val openedOutputs = mutableListOf<String>()
        val createdNames = mutableListOf<String>()
        val deletedNames = mutableListOf<String>()
        var refuseDelete: Boolean = false
        var afterCreate: ((String) -> Unit)? = null
        var createdNameOverride: String? = null
        var afterOutputClose: ((String) -> Unit)? = null
        init { contents.keys.toList().forEach(::registerStreams) }
        fun bytes(name: String) = contents.getValue(idsByName.getValue(name))
        fun contains(name: String) = idsByName[name] in contents
        private fun registerStreams(id: String) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            shadowOf(android.contentResolver).registerInputStreamSupplier(uri) {
                ByteArrayInputStream(contents[id] ?: throw FileNotFoundException(id))
            }
            shadowOf(android.contentResolver).registerOutputStreamSupplier(uri) {
                require(id in contents)
                openedOutputs += displayName(id)
                object : ByteArrayOutputStream() {
                    override fun close() { contents[id] = toByteArray(); super.close(); afterOutputClose?.invoke(displayName(id)) }
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
            val actualName = createdNameOverride ?: displayName
            require(actualName !in idsByName)
            val id = if (opaqueIds) "msf:${++nextId}" else "$rootId/$actualName"
            idsByName[actualName] = id
            contents[id] = byteArrayOf(); createdNames += actualName; registerStreams(id)
            afterCreate?.invoke(actualName)
            return id
        }
        override fun deleteDocument(documentId: String) {
            require(!refuseDelete) { "provider refuses delete" }
            require(contents.remove(documentId) != null)
            val name = displayName(documentId)
            deletedNames += name
            idsByName.remove(name)
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            throw FileNotFoundException(documentId)
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                ids.forEach { id -> addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName(id)
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
