package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
import java.io.IOException
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

/** File-backed provider operations, not pre-registered resolver streams.
 * Android 16 FileSystemProvider has no copyDocument override/COPY flag and opens
 * streams with ParcelFileDescriptor.parseMode(mode), including the "wt" mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, manifest = Config.NONE)
class SafExternalStorageProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download")
    private val rootId = "primary:Download"

    private fun body(parts: List<UIMessagePart>): JsonObject = Json.parseToJsonElement(
        parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    private suspend fun fixture(action: suspend (Context, LocalFileAccess, ExternalStorageFixture, (String) -> String) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val primary = temporary.newFolder("primary")
        File(primary, "Download").mkdirs()
        ShadowEnvironment.setExternalStorageDirectory(primary.toPath())
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("primary", primary, "Primary", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build())
        val provider = ExternalStorageFixture(temporary.newFolder("provider"))
        val info = ProviderInfo().apply {
            authority = tree.authority; name = provider.javaClass.name; packageName = context.packageName
            exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }
        provider.attachInfo(context, info)
        val transport = DocumentsTransport(provider)
        transport.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(tree.authority!!, transport)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { providerInfo = info })
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"), context = context,
            primaryStorageAliases = listOf(primary))
        action(context, access, provider) { File(primary, "Download/$it").path }
        assertFalse("No raw shared-storage writes", File(primary, "Download").listFiles()!!.isNotEmpty())
        assertEquals("No unsupported native-copy optimization", 0, provider.nativeCopies)
        assertEquals("No unsupported native-move optimization", 0, provider.nativeMoves)
    }

    private fun success(result: JsonObject) = assertEquals("Operation failed: $result", "true", result["success"]?.jsonPrimitive?.content)

    @Test fun newCopyUsesCreateAndRealStreamsWithoutCopyFlag(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("rikka_new.txt", "Данные SAF")
        success(body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("rikka_new.txt")); put("dst", path("rikka_copy.txt"))
        })))
        assertArrayEquals(provider.bytes("rikka_new.txt"), provider.bytes("rikka_copy.txt"))
        assertEquals(listOf("rikka_copy.txt"), provider.created)
        assertEquals(listOf("r", "wt"), provider.openModes)
        assertEquals("application/octet-stream", provider.createdMimes.single())
    } }

    @Test fun copyUsesProviderReturnedDocumentIdInsteadOfGuessingTargetUri(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Возвращённый URI")
        provider.createdIdOverride = "$rootId/provider-assigned-id"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        success(result)
        assertEquals(provider.createdIdOverride, DocumentsContract.getDocumentId(Uri.parse(result["to"]!!.jsonPrimitive.content)))
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("copy.txt"))
        assertEquals(listOf("r", "wt"), provider.openModes)
    } }

    @Test fun moveCreatesNewSafDestinationBeforeDeletingSource(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("rikka_new.txt", "Перемещение SAF")
        success(body(fileManagerTools(access).single { it.name == "move_file" }.execute(buildJsonObject {
            put("src", path("rikka_new.txt")); put("dst", path("rikka_move.txt"))
        })))
        assertEquals("Перемещение SAF", provider.bytes("rikka_move.txt").toString(Charsets.UTF_8))
        assertFalse(provider.contains("rikka_new.txt"))
        assertEquals(listOf("rikka_new.txt"), provider.deleted)
        assertEquals(listOf("rikka_move.txt"), provider.created)
    } }

    @Test fun moveKeepsCommittedCopyWhenProviderRefusesSourceDeletion(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Сохранённая копия")
        provider.refuseDeleteName = "source.txt"
        val result = body(fileManagerTools(access).single { it.name == "move_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("moved.txt"))
        }))
        assertEquals("source_delete_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("Сохранённая копия", provider.bytes("source.txt").toString(Charsets.UTF_8))
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("moved.txt"))
        assertTrue("The recovery copy must not be cleaned up", provider.deleted.isEmpty())
    } }

    @Test fun batchCopyCommitsOwnedNewDocumentsWithoutReadingEmptyOriginals(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.refuseReadNewDocument = true
        access.writePath("/scratch/a.txt", "Первый".toByteArray())
        access.writePath("/scratch/b.txt", "Второй".toByteArray())
        val result = body(fileManagerTools(access).single { it.name == "batch_copy" }.execute(buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/a.txt"); add("/scratch/b.txt") })
            put("dst_dir", File(path("a.txt")).parent!!)
        }))
        assertEquals("Batch failed: $result", 2, result["success"]?.jsonPrimitive?.int)
        assertTrue(result["failed"]!!.jsonArray.isEmpty())
        assertEquals("Первый", provider.bytes("a.txt").toString(Charsets.UTF_8))
        assertEquals("Второй", provider.bytes("b.txt").toString(Charsets.UTF_8))
        assertEquals(listOf("wt", "wt"), provider.openModes)
    } }

    @Test fun zipSingleSourceCreatesNewDocumentThroughWtStream(): Unit = runBlocking { fixture { context, access, provider, path ->
        provider.seed("rikka_new.txt", "Только существующий источник")
        success(body(zipFilesTool(context, access).execute(buildJsonObject {
            put("sources", buildJsonArray { add(path("rikka_new.txt")) }); put("destination", path("rikka_test.zip"))
        })))
        ZipInputStream(ByteArrayInputStream(provider.bytes("rikka_test.zip"))).use { zip ->
            assertEquals("rikka_new.txt", zip.nextEntry.name)
            assertArrayEquals(provider.bytes("rikka_new.txt"), zip.readBytes())
            assertNull(zip.nextEntry)
        }
        assertTrue(provider.openModes.contains("wt"))
    } }

    @Test fun zipMissingSourceDoesNotCreateDestination(): Unit = runBlocking { fixture { context, access, provider, path ->
        provider.seed("rikka_new.txt", "Источник")
        val result = body(zipFilesTool(context, access).execute(buildJsonObject {
            put("sources", buildJsonArray { add(path("rikka_new.txt")); add(path("rikka_copy.txt")) })
            put("destination", path("rikka_test.zip"))
        }))
        assertNotNull(result["error"])
        assertTrue(provider.created.isEmpty())
        assertFalse(provider.contains("rikka_test.zip"))
    } }

    @Test fun copyRefusesExistingDestinationUnlessOverwriteTrue(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник"); provider.seed("existing.txt", "Старое")
        val tool = fileManagerTools(access).single { it.name == "copy_file" }
        val arguments = buildJsonObject { put("src", path("source.txt")); put("dst", path("existing.txt")) }
        assertNotNull(body(tool.execute(arguments))["error"])
        assertEquals("Старое", provider.bytes("existing.txt").toString(Charsets.UTF_8))
        success(body(tool.execute(JsonObject(arguments + ("overwrite" to JsonPrimitive(true))))))
        assertArrayEquals(provider.bytes("source.txt"), provider.bytes("existing.txt"))
        assertTrue(provider.created.isEmpty())
    } }

    @Test fun autoRenamedCreationIsRejectedAndOnlyOwnedCopyRemoved(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник")
        provider.createdNameOverride = "copy (1).txt"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertNotNull(result["error"])
        assertEquals(listOf("copy (1).txt"), provider.deleted)
        assertFalse(provider.contains("copy.txt"))
        assertFalse(provider.contains("copy (1).txt"))
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    private fun assertPrivateCopyFailure(result: JsonObject, exceptionClass: String) {
        assertEquals("Копирование файла в выбранной папке: Android отклонил запрос; тип ошибки указан в отчёте.",
            result["error"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("Секрет"))
        assertFalse(result.toString().contains("token=private"))
        val diagnostics = Class.forName("me.rerere.rikkahub.data.ai.tools.local.FileOperationDiagnostics")
        val report = diagnostics.methods.single { it.name.startsWith("report") && it.parameterCount == 0 }
            .invoke(diagnostics.getField("INSTANCE").get(null)) as String
        assertTrue(report.contains("COPY"))
        assertTrue(report.contains(exceptionClass))
        assertFalse(report.contains("SafFileOperationException"))
        assertFalse(report.contains("Секрет"))
        assertFalse(report.contains("token=private"))
    }

    @Test fun providerInputOpenFailureIsPrivateAndRemovesOnlyOwnedCopy(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник")
        provider.failOpenMode = "r"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertPrivateCopyFailure(result, IllegalStateException::class.java.name)
        assertFalse(provider.contains("copy.txt"))
        assertEquals(listOf("copy.txt"), provider.deleted)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun providerOutputOpenFailureIsPrivateAndRemovesOnlyOwnedCopy(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник")
        provider.failOpenMode = "wt"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertPrivateCopyFailure(result, IllegalStateException::class.java.name)
        assertFalse(provider.contains("copy.txt"))
        assertEquals(listOf("copy.txt"), provider.deleted)
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun streamReadFailureIsPrivateAndRemovesOnlyOwnedCopy(): Unit = runBlocking { fixture { context, access, provider, path ->
        provider.seed("source.txt", "Источник")
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/source.txt")
        shadowOf(context.contentResolver).registerInputStreamSupplier(sourceUri) {
            object : ByteArrayInputStream(provider.bytes("source.txt")) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IllegalStateException("Ошибка /sdcard/Секрет.txt token=private")
            }
        }
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertPrivateCopyFailure(result, IllegalStateException::class.java.name)
        assertFalse(provider.contains("copy.txt"))
        assertEquals(listOf("copy.txt"), provider.deleted)
    } }

    @Test fun streamCloseFailureNamesSafOperationAndDoesNotLeakProviderMessage(): Unit = runBlocking { fixture { context, access, provider, path ->
        provider.seed("source.txt", "Источник")
        val targetUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/copy.txt")
        shadowOf(context.contentResolver).registerOutputStreamSupplier(targetUri) {
            object : ByteArrayOutputStream() {
                override fun close() { throw IOException("Ошибка /sdcard/Секрет.txt token=private") }
            }
        }
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertEquals("Копирование файла в выбранной папке: ошибка чтения или записи.", result["error"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("Секрет"))
        assertFalse(provider.contains("copy.txt"))
        assertEquals(listOf("copy.txt"), provider.deleted)
    } }

    @Test fun cancelledMoveAfterSourceDeletionKeepsTheOnlyCommittedCopy(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Единственная копия")
        lateinit var job: Job
        provider.afterDelete = { if (it == "source.txt") job.cancel() }
        job = launch(start = CoroutineStart.LAZY) {
            fileManagerTools(access).single { it.name == "move_file" }.execute(buildJsonObject {
                put("src", path("source.txt")); put("dst", path("moved.txt"))
            })
        }
        job.start(); job.join()
        assertTrue("The provider must reach source deletion and cancel the operation", job.isCancelled)
        assertFalse(provider.contains("source.txt"))
        assertEquals("Единственная копия", provider.bytes("moved.txt").toString(Charsets.UTF_8))
        assertEquals(listOf("source.txt"), provider.deleted)
    } }

    @Test fun batchMoveUsesOwnedDestinationAndDoesNotReadEmptyOriginal(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.refuseReadNewDocument = true
        access.writePath("/scratch/a.txt", "Переместить".toByteArray())
        val result = body(fileManagerTools(access).single { it.name == "batch_move" }.execute(buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/a.txt") }); put("dst_dir", File(path("a.txt")).parent!!)
        }))
        assertEquals("Batch move failed: $result", 1, result["success"]?.jsonPrimitive?.int)
        assertTrue(result["failed"]!!.jsonArray.isEmpty())
        assertFalse(access.exists(access.resolve("/scratch/a.txt")))
        assertEquals("Переместить", provider.bytes("a.txt").toString(Charsets.UTF_8))
        assertEquals(listOf("wt"), provider.openModes)
    } }

    @Test fun batchCopyRefusesAutoRenamedNewDocumentAndCleansOnlyOwnedTarget(): Unit = runBlocking { fixture { _, access, provider, path ->
        access.writePath("/scratch/a.txt", "Источник".toByteArray())
        provider.createdNameOverride = "a (1).txt"
        val result = body(fileManagerTools(access).single { it.name == "batch_copy" }.execute(buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/a.txt") }); put("dst_dir", File(path("a.txt")).parent!!)
        }))
        assertEquals(0, result["success"]?.jsonPrimitive?.int)
        assertEquals(1, result["failed"]!!.jsonArray.size)
        assertFalse(provider.contains("a (1).txt"))
        assertEquals(listOf("a (1).txt"), provider.deleted)
        assertTrue(access.exists(access.resolve("/scratch/a.txt")))
    } }

    @Test fun recursiveCopyRefusesAutoRenamedNewDocumentAndCleansOnlyOwnedTarget(): Unit = runBlocking { fixture { _, access, provider, path ->
        access.writePath("/scratch/source/a.txt", "Источник".toByteArray())
        provider.createdNameOverride = "a (1).txt"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", "/scratch/source"); put("dst", File(path("a.txt")).parent!!); put("overwrite", true)
        }))
        assertNotNull(result["error"])
        assertFalse(provider.contains("a (1).txt"))
        assertEquals(listOf("a (1).txt"), provider.deleted)
        assertTrue(access.exists(access.resolve("/scratch/source/a.txt")))
    } }

    @Test fun batchCopyReportsOwnedDocumentWhenRollbackDeletionIsRefused(): Unit = runBlocking { fixture { _, access, provider, path ->
        access.writePath("/scratch/a.txt", "Источник".toByteArray())
        provider.failOpenMode = "wt"; provider.refuseDeleteName = "a.txt"
        val result = body(fileManagerTools(access).single { it.name == "batch_copy" }.execute(buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/a.txt") }); put("dst_dir", File(path("a.txt")).parent!!)
        }))
        assertEquals(0, result["success"]?.jsonPrimitive?.int)
        val failure = result["failed"]!!.jsonArray.single().jsonObject
        assertNotNull("Failed cleanup must report its owned document", failure["created_path"])
        assertEquals("$rootId/a.txt", DocumentsContract.getDocumentId(Uri.parse(failure["created_path"]!!.jsonPrimitive.content)))
        assertTrue(provider.contains("a.txt"))
        assertTrue(access.exists(access.resolve("/scratch/a.txt")))
    } }

    @Test fun recursiveCopyReportsOwnedDocumentWhenRollbackDeletionIsRefused(): Unit = runBlocking { fixture { _, access, provider, path ->
        access.writePath("/scratch/source/a.txt", "Источник".toByteArray())
        provider.failOpenMode = "wt"; provider.refuseDeleteName = "a.txt"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", "/scratch/source"); put("dst", File(path("a.txt")).parent!!); put("overwrite", true)
        }))
        assertEquals("creation_cleanup_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("$rootId/a.txt", DocumentsContract.getDocumentId(Uri.parse(result["created_path"]!!.jsonPrimitive.content)))
        assertTrue(provider.contains("a.txt"))
        assertTrue(access.exists(access.resolve("/scratch/source/a.txt")))
    } }

    @Test fun providerCursorCloseFailureIsPrivateBeforeCreatingTheCopy(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник")
        provider.failChildCursorClose = true
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertPrivateCopyFailure(result, IllegalStateException::class.java.name)
        assertTrue("Failed preflight must not create any document", provider.created.isEmpty())
        assertTrue(provider.deleted.isEmpty())
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    @Test fun providerMetadataNameCloseFailureIsPrivateAndRemovesOnlyOwnedCopy(): Unit = runBlocking { fixture { _, access, provider, path ->
        provider.seed("source.txt", "Источник")
        provider.failNameCursorCloseFor = "copy.txt"
        val result = body(fileManagerTools(access).single { it.name == "copy_file" }.execute(buildJsonObject {
            put("src", path("source.txt")); put("dst", path("copy.txt"))
        }))
        assertPrivateCopyFailure(result, IllegalStateException::class.java.name)
        assertEquals(listOf("copy.txt"), provider.created)
        assertEquals(listOf("copy.txt"), provider.deleted)
        assertFalse(provider.contains("copy.txt"))
        assertEquals("Источник", provider.bytes("source.txt").toString(Charsets.UTF_8))
    } }

    private inner class ExternalStorageFixture(private val storage: File) : DocumentsProvider() {
        private val documents = mutableMapOf<String, File>()
        val created = mutableListOf<String>(); val deleted = mutableListOf<String>()
        val createdMimes = mutableListOf<String>(); val openModes = mutableListOf<String>()
        var nativeCopies = 0; var nativeMoves = 0
        var createdNameOverride: String? = null
        var createdIdOverride: String? = null
        var refuseReadNewDocument = false
        var refuseDeleteName: String? = null
        var failOpenMode: String? = null
        var afterDelete: ((String) -> Unit)? = null
        var failChildCursorClose = false
        var failNameCursorCloseFor: String? = null
        fun seed(name: String, text: String) { File(storage, name).apply { writeText(text); documents["$rootId/$name"] = this } }
        fun bytes(name: String) = documents.values.single { it.name == name }.readBytes()
        fun contains(name: String) = documents.values.any { it.name == name }
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(
            listOf(documentId).filter { it == rootId || it in documents }, projection,
            failNameCursorCloseFor != null && documents[documentId]?.name == failNameCursorCloseFor &&
                projection?.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME) == true)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            rows(if (parentDocumentId == rootId) documents.keys.toList() else emptyList(), projection, failChildCursorClose)
        override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == rootId && documentId in documents
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(parentDocumentId == rootId)
            val actualName = createdNameOverride ?: displayName
            val file = File(storage, actualName)
            check(file.createNewFile())
            val id = createdIdOverride ?: "$rootId/$actualName"
            documents[id] = file; created += actualName; createdMimes += mimeType
            return id
        }
        override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
            nativeCopies++; throw UnsupportedOperationException("Copy not supported")
        }
        override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
            nativeMoves++; throw UnsupportedOperationException("Native move deliberately unavailable")
        }
        override fun deleteDocument(documentId: String) {
            if (documents[documentId]?.name == refuseDeleteName) throw SecurityException("Refused source deletion")
            val file = documents.remove(documentId) ?: throw FileNotFoundException(documentId)
            check(file.delete()); deleted += file.name; afterDelete?.invoke(file.name)
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            val file = documents[documentId] ?: throw FileNotFoundException(documentId)
            if (mode == failOpenMode) throw IllegalStateException("Ошибка /sdcard/Секрет.txt token=private")
            if (mode == "r" && refuseReadNewDocument && file.name in created) throw FileNotFoundException("New document is write-only")
            openModes += mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        private fun rows(ids: List<String>, projection: Array<out String>?, failClose: Boolean = false): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS)
            return object : MatrixCursor(columns) {
                override fun close() {
                    super.close()
                    if (failClose) throw IllegalStateException("Ошибка /sdcard/Секрет.txt token=private")
                }
            }.apply { ids.forEach { id -> addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (id == rootId) "Download" else documents.getValue(id).name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == rootId) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> documents[id]?.length() ?: 0L
                DocumentsContract.Document.COLUMN_FLAGS -> if (id == rootId) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> documents[id]?.lastModified() ?: 0L
                else -> null
            } }) } }
        }
    }

    /** Robolectric directly calls deprecated query instead of the system Bundle transport. */
    private class DocumentsTransport(private val documents: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            documents.query(uri, projection, Bundle(), null)
        override fun getType(uri: Uri): String? = documents.getType(uri)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = documents.call(method, arg, extras)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = documents.openDocument(DocumentsContract.getDocumentId(uri), mode, null)
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? =
            AssetFileDescriptor(requireNotNull(openFile(uri, mode)), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = documents.insert(uri, values)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = documents.delete(uri, selection, selectionArgs)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = documents.update(uri, values, selection, selectionArgs)
    }
}
