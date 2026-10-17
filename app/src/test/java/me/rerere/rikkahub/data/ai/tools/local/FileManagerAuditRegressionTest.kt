package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.app.WallpaperManager
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/** These tests exercise the shipped tool API, without requiring new resolver signatures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class FileManagerAuditRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun enforceAndroidImageDecoding() { ShadowBitmapFactory.setAllowInvalidImageData(false) }

    private fun body(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    private fun access(scratch: File = temporary.newFolder("scratch")) =
        LocalFileAccess(scratch, temporary.newFolder("upload"))

    private suspend fun execute(access: LocalFileAccess, name: String, arguments: JsonObject): JsonObject =
        body(fileManagerTools(access).single { it.name == name }.execute(arguments))

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } } finally { bitmap.recycle() }
    }

    @Test fun absoluteSafDirectoryCreationPlansTheMissingFinalComponent(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val primary = temporary.newFolder("primary")
        ShadowEnvironment.setExternalStorageDirectory(primary.toPath())
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("primary", primary, "Primary", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build())
        File(primary, "Download").mkdirs()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val provider = DirectoryProvider()
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val target = File(primary, "Download/Новая папка")
        val result = execute(LocalFileAccess(context), "create_directory", buildJsonObject { put("path", target.path) })
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertTrue("The provider owns the new directory", provider.directories.contains("primary:Download/Новая папка"))
        assertFalse("SAF must not create a raw host directory", target.exists())
    }

    @Test fun remaining45SafChildLookupFindsTheExactNameBeyondTheListingBudget(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val provider = DirectoryProvider().apply {
            repeat(LocalFileAccess.MAX_ENTRIES + 1) { directories += "primary:Download/entry-$it" }
            directories += "primary:Download/wanted"
        }
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val access = LocalFileAccess(context)
        val found = access.findChild(access.resolve(tree.toString()), "wanted")
        assertNotNull("An exact SAF child lookup must not inherit the UI listing cap", found)
        assertEquals("wanted", access.name(found!!))
    }

    @Test fun remaining45SafChildLookupProvesAbsenceWithoutSilentlyTruncating(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val provider = DirectoryProvider().apply {
            repeat(LocalFileAccess.MAX_ENTRIES + 1) { directories += "primary:Download/entry-$it" }
        }
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val access = LocalFileAccess(context)
        assertNull(access.findChild(access.resolve(tree.toString()), "missing"))
    }

    @Test fun parentAndNameCanCreateChildrenAtEveryLocalScopeRoot(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val upload = temporary.newFolder("upload")
        val workspace = temporary.newFolder("workspace")
        val access = LocalFileAccess(scratch, upload, resolveWorkspacePath = { path ->
            File(workspace, path.removePrefix("/workspace").trimStart('/'))
        })
        for ((path, parent) in listOf("/scratch" to scratch, "/upload" to upload, "/workspace" to workspace)) {
            val result = execute(access, "create_directory", buildJsonObject {
                put("path", path); put("name", "Новая папка")
            })
            assertEquals("$path: $result", true, result["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(true, result["created"]?.jsonPrimitive?.booleanOrNull)
            assertTrue("$path: the child is created in the selected scope", File(parent, "Новая папка").isDirectory)
        }
    }

    @Test fun binaryWritesAcceptMimeBase64LineBreaks(): Unit = runBlocking {
        val access = access()
        val bytes = ByteArray(181) { it.toByte() }
        val result = execute(access, "write_binary_file", buildJsonObject {
            put("path", "mime.bin"); put("base64_content", Base64.getMimeEncoder().encodeToString(bytes))
        })
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertArrayEquals(bytes, access.openInput(access.resolve("mime.bin")).use { it.readBytes() })
    }

    @Test fun binaryWritesAcceptBase64DataUris(): Unit = runBlocking {
        val access = access()
        val bytes = "данные".toByteArray()
        val result = execute(access, "write_binary_file", buildJsonObject {
            put("path", "data.bin"); put("base64_content", "data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(bytes)}")
        })
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertArrayEquals(bytes, access.openInput(access.resolve("data.bin")).use { it.readBytes() })
    }

    @Test fun binaryWriteLimitAppliesToDecodedBytesDespiteMimeWrapping(): Unit = runBlocking {
        val access = access()
        val bytes = ByteArray(LocalFileAccess.MAX_WRITE_BYTES) { (it % 251).toByte() }
        val result = execute(access, "write_binary_file", buildJsonObject {
            put("path", "boundary.bin"); put("base64_content", Base64.getMimeEncoder().encodeToString(bytes))
        })
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(bytes.size.toLong(), access.size(access.resolve("boundary.bin")))
    }

    @Test fun binaryWriteAboveDecodedLimitLeavesNoDestination(): Unit = runBlocking {
        val access = access()
        val result = execute(access, "write_binary_file", buildJsonObject {
            put("path", "oversized.bin")
            put("base64_content", Base64.getEncoder().encodeToString(ByteArray(LocalFileAccess.MAX_WRITE_BYTES + 1)))
        })
        assertNotNull(result["error"])
        assertFalse(access.exists(access.resolve("oversized.bin")))
    }

    @Test fun readingDirectoryNamesTheListingTool(): Unit = runBlocking {
        val result = execute(access(), "read_file", buildJsonObject { put("path", "/scratch") })
        assertEquals("Это папка, используйте list_files", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun textByteLimitNeverEmitsHalfAUtf8CodePoint(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("utf8.txt", true), "А🙂Б".toByteArray())
        val result = execute(access, "read_file", buildJsonObject { put("path", "utf8.txt"); put("max_bytes", 4) })
        assertEquals("А", result["content"]?.jsonPrimitive?.content)
        assertEquals(2, result["bytes_read"]?.jsonPrimitive?.int)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun imageDisplayLeavesNoOwnedShownFilesAndKeepsReturnedImageUsable(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val access = access(scratch)
        val sentinel = File(scratch, ".shown-existing.img").apply { writeText("unrelated") }
        access.write(access.resolve("image.png", true), imageBytes())
        val parts = fileManagerTools(access, modelCanReadImages = true).single { it.name == "show_image" }
            .execute(buildJsonObject { put("path", "image.png") })
        assertEquals(body(parts).toString(), true, body(parts)["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Only this call's temporary files may be cleaned", listOf(sentinel.name),
            scratch.listFiles()!!.filter { it.name.startsWith(".shown-") }.map { it.name })
        val url = parts.filterIsInstance<UIMessagePart.Image>().single().url
        assertTrue("The model still receives a readable image", url.startsWith("data:image/") ||
            url.startsWith("file:") && File(java.net.URI(url)).isFile)
        assertEquals("unrelated", sentinel.readText())
    }

    @Test fun imageDisplayFailureDoesNotRemoveUnrelatedShownFiles(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val access = access(scratch)
        val sentinel = File(scratch, ".shown-existing.img").apply { writeText("unrelated") }
        access.write(access.resolve("bad.png", true), "not an image".toByteArray())
        val result = body(fileManagerTools(access, modelCanReadImages = true).single { it.name == "show_image" }
            .execute(buildJsonObject { put("path", "bad.png") }))
        assertNotNull(result["error"])
        assertEquals(listOf(sentinel.name), scratch.listFiles()!!.filter { it.name.startsWith(".shown-") }.map { it.name })
    }

    @Test fun wallpaperDefaultResolverUnderstandsTheSharedScratchScope(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "tool_scratch/wallpaper.png").apply {
            parentFile!!.mkdirs(); writeBytes(imageBytes())
        }
        val manager = WallpaperManager.getInstance(context)
        shadowOf(manager).setIsWallpaperSupported(true)
        shadowOf(manager).setIsSetWallpaperAllowed(true)
        val result = body(setWallpaperTool(context).execute(buildJsonObject {
            put("file_uri", "/scratch/wallpaper.png"); put("target", "home")
        }))
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(file.isFile)
    }

    @Test fun largeListingReturnsBoundedMatchesWithTruncationInsteadOfError(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(scratch, "entry-$it.txt").writeText("") }
        val result = execute(access(scratch), "list_files", buildJsonObject { put("path", "/scratch"); put("limit", 3) })
        assertNull(result.toString(), result["error"])
        assertEquals(3, result["files"]?.jsonArray?.size)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun largeFindReportsTraversalTruncationEvenWhenNothingMatches(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(scratch, "entry-$it.txt").writeText("") }
        val result = execute(access(scratch), "find_files", buildJsonObject {
            put("root", "/scratch"); put("query", "*.missing")
        })
        assertNull(result.toString(), result["error"])
        assertEquals(0, result["files"]?.jsonArray?.size)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun completeSharedStorageToolListingScansVolumesAtMostTwice(): Unit = runBlocking {
        val primary = temporary.newFolder("primary")
        repeat(25) { File(primary, "entry-$it.txt").writeText("data") }
        var volumeLookups = 0
        val policy = SharedStorageAccess({ true }, { true }, {
            volumeLookups++
            mapOf("primary" to primary)
        })
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"),
            sharedStorageAccess = policy)
        val result = execute(access, "list_files", buildJsonObject { put("path", primary.path); put("limit", 25) })
        assertNull(result.toString(), result["error"])
        assertEquals(25, result["files"]?.jsonArray?.size)
        assertEquals(false, result["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(setOf(4L), result.getValue("files").jsonArray.map {
            it.jsonObject.getValue("size_bytes").jsonPrimitive.long
        }.toSet())
        assertTrue("The complete JSON query must reuse volume topology; actual=$volumeLookups", volumeLookups <= 2)
    }

    @Test fun completeSafToolListingReadsChildMetadataInOneBulkCursor(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val provider = DirectoryProvider().apply {
            repeat(25) { directories += "primary:Download/dir-$it" }
        }
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = execute(LocalFileAccess(context), "list_files", buildJsonObject {
            put("path", tree.toString()); put("limit", 25)
        })
        assertNull(result.toString(), result["error"])
        val files = result.getValue("files").jsonArray.map { it.jsonObject }
        assertEquals((0 until 25).map { "dir-$it" }.toSet(), files.map { it.getValue("name").jsonPrimitive.content }.toSet())
        assertTrue(files.all { it.getValue("is_directory").jsonPrimitive.boolean })
        assertTrue(files.all { it.getValue("size_bytes").jsonPrimitive.long == 0L })
        assertEquals(1, provider.childQueries)
        assertTrue("Metadata must come from the child cursor, not repeated document lookups; documentQueries=${provider.documentQueries}",
            provider.documentQueries <= 3)
    }

    @Test fun safListingMetadataCacheExpiresBeforeTheNextToolCall(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val id = "primary:Download/stable-id"
        val provider = DirectoryProvider().apply {
            directories += id; displayNames[id] = "first"; modifiedTimes[id] = 10L
        }
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val access = LocalFileAccess(context)
        val arguments = buildJsonObject { put("path", tree.toString()) }
        val first = execute(access, "list_files", arguments).getValue("files").jsonArray.single().jsonObject
        assertEquals("first", first.getValue("name").jsonPrimitive.content)
        assertEquals(10L, first.getValue("modified_at_ms").jsonPrimitive.long)
        provider.displayNames[id] = "second"
        provider.modifiedTimes[id] = 20L
        val second = execute(access, "list_files", arguments).getValue("files").jsonArray.single().jsonObject
        assertEquals("second", second.getValue("name").jsonPrimitive.content)
        assertEquals(20L, second.getValue("modified_at_ms").jsonPrimitive.long)
    }

    @Test fun revokingSafAfterTheReadBodyFinishesReturnsAToolError(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val provider = DirectoryProvider().apply { directories += "primary:Download/child" }
        attachProvider(context, tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val access = LocalFileAccess(context)
        // Revoke after the real listing has produced its entire JSON body, at the
        // enclosing read operation's last boundary before returning to its caller.
        val operation = fileJsonTool("list_files", "Read boundary regression", fileSchema()) {
            access.withReadOperation {
                val listed = execute(access, "list_files", buildJsonObject { put("path", tree.toString()) })
                assertEquals(1, listed.getValue("files").jsonArray.size)
                context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                listed
            }
        }
        val result = body(operation.execute(buildJsonObject { }))
        assertNotNull("Revoked grants must invalidate the finished read body: $result", result["error"])
        assertFalse(result.containsKey("files"))
    }

    @Test fun batchMoveWithinScratchRenamesLargeSparseFilesWithoutCopyStages(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val access = access(scratch)
        val source = File(scratch, "source.bin")
        val destination = File(scratch, "destination").apply { mkdir() }
        val target = File(destination, source.name)
        val length = LocalFileAccess.MAX_COPY_BYTES + 1
        RandomAccessFile(source, "rw").use {
            it.setLength(length)
            it.write(73)
            it.seek(length - 1)
            it.write(91)
        }
        val inode = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        assertNotNull("This filesystem must expose file identity for the rename regression", inode)
        val result = execute(access, "batch_move", buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/source.bin") })
            put("dst_dir", "/scratch/destination")
        })
        assertEquals(result.toString(), 1, result["success"]?.jsonPrimitive?.intOrNull)
        assertEquals(0, result.getValue("failed").jsonArray.size)
        assertFalse(source.exists())
        assertEquals(inode, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(length, target.length())
        RandomAccessFile(target, "r").use {
            assertEquals(73, it.read())
            it.seek(length - 1)
            assertEquals(91, it.read())
        }
        assertEquals(setOf("destination", "destination/source.bin"),
            scratch.walkTopDown().drop(1).map { it.relativeTo(scratch).invariantSeparatorsPath }.toSet())
    }

    @Test fun failedCrossVolumeBatchDirectoryMoveRemovesItsOwnedPartialRoot(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val workspace = Files.createTempDirectory(File("/dev/shm").toPath(), "rikka-batch-move-").toFile()
        try {
            assertNotEquals("This fixture needs a real cross-volume fallback",
                Files.getFileStore(workspace.toPath()), Files.getFileStore(scratch.toPath()))
            val access = LocalFileAccess(scratch, temporary.newFolder("upload"), resolveWorkspacePath = { path ->
                File(workspace, path.removePrefix("/workspace").trimStart('/'))
            })
            val source = File(workspace, "source").apply { mkdir() }
            val small = File(source, "001-small.txt").apply { writeText("keep source") }
            val large = File(source, "zzz-large.bin")
            val length = LocalFileAccess.MAX_COPY_BYTES + 1
            RandomAccessFile(large, "rw").use {
                it.setLength(length)
                it.write(73)
                it.seek(length - 1)
                it.write(91)
            }
            val destination = File(scratch, "destination").apply { mkdir() }
            val result = execute(access, "batch_move", buildJsonObject {
                put("paths", buildJsonArray { add("/workspace/source") })
                put("dst_dir", "/scratch/destination")
            })
            assertEquals(result.toString(), 0, result["success"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, result.getValue("failed").jsonArray.size)
            assertEquals("keep source", small.readText())
            assertEquals(length, large.length())
            RandomAccessFile(large, "r").use {
                assertEquals(73, it.read())
                it.seek(length - 1)
                assertEquals(91, it.read())
            }
            assertFalse("The batch owns and must remove its failed directory target", File(destination, "source").exists())
            assertEquals(setOf("destination"),
                scratch.walkTopDown().drop(1).map { it.relativeTo(scratch).invariantSeparatorsPath }.toSet())
        } finally {
            check(workspace.deleteRecursively()) { "Cross-volume test workspace cleanup failed" }
        }
    }

    private fun attachProvider(context: Context, authority: String, provider: DocumentsProvider) {
        val info = ProviderInfo().apply {
            this.authority = authority; name = provider.javaClass.name; packageName = context.packageName
            exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider.attachInfo(context, info)
        val transport = DocumentsTransport(provider).apply { attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, transport)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { providerInfo = info })
    }

    private class DocumentsTransport(private val documents: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = documents.query(uri, projection, Bundle(), null)
        override fun getType(uri: Uri): String? = documents.getType(uri)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = documents.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = documents.insert(uri, values)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = documents.delete(uri, selection, selectionArgs)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            documents.update(uri, values, selection, selectionArgs)
    }

    private class DirectoryProvider : DocumentsProvider() {
        val directories = mutableSetOf("primary:Download")
        val displayNames = mutableMapOf<String, String>()
        val modifiedTimes = mutableMapOf<String, Long>()
        var documentQueries = 0
        var childQueries = 0
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            documentQueries++
            return rows(listOf(documentId).filter { it in directories }, projection)
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            childQueries++
            return rows(directories.filter { it != parentDocumentId && it.substringBeforeLast('/') == parentDocumentId }, projection)
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
            documentId in directories && documentId.startsWith("$parentDocumentId/")
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(parentDocumentId in directories && mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            return "$parentDocumentId/$displayName".also { require(directories.add(it)) }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            throw FileNotFoundException(documentId)
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply { ids.forEach { id -> addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayNames[id] ?: id.substringAfterLast(':').substringAfterLast('/')
                DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modifiedTimes[id] ?: 0L
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE -> 0
                else -> null
            } }) } }
        }
    }
}
