package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ArchiveAuditRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun body(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { archive -> names.forEach { name ->
            archive.putNextEntry(ZipEntry(name)); archive.write("new data".toByteArray()); archive.closeEntry()
        } }
    }.toByteArray()

    private fun access(): LocalFileAccess = LocalFileAccess(temporary.newFolder(), temporary.newFolder())

    @Test fun zipDefaultRefusesAnExistingDestinationWithoutChangingItsBytes(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("source.txt", true), "source".toByteArray())
        val destination = access.resolve("existing.zip", true)
        val original = zip("original.txt")
        access.write(destination, original)
        val result = body(zipFilesTool(context, access).execute(buildJsonObject {
            put("sources", buildJsonArray { add("source.txt") }); put("destination", "existing.zip")
        }))
        assertNotNull("Default zip must refuse replacement: $result", result["error"])
        assertArrayEquals(original, access.openInput(destination).use { it.readBytes() })
    }

    @Test fun zipExplicitOverwriteReplacesAnExistingDestination(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("source.txt", true), "source".toByteArray())
        val destination = access.resolve("existing.zip", true)
        access.write(destination, zip("original.txt"))
        val result = body(zipFilesTool(context, access).execute(buildJsonObject {
            put("sources", buildJsonArray { add("source.txt") }); put("destination", "existing.zip"); put("overwrite", true)
        }))
        assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.boolean)
        ZipInputStream(access.openInput(destination)).use { archive ->
            assertEquals("source.txt", archive.nextEntry.name)
            assertEquals("source", archive.readBytes().toString(Charsets.UTF_8))
            assertNull(archive.nextEntry)
        }
    }

    @Test fun listingLimitsReturnedEntriesAndReportsTruncation(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("input.zip", true), zip("first", "second", "third"))
        val tool = listZipContentsTool(context, access)
        val result = body(tool.execute(buildJsonObject { put("source", "input.zip"); put("limit", 1) }))
        assertEquals(result.toString(), 1, result["entries"]?.jsonArray?.size)
        assertEquals("first", result["entries"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.boolean)
    }

    @Test fun listingAtItsExactLimitIsNotTruncated(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("input.zip", true), zip("first", "second"))
        val result = body(listZipContentsTool(context, access).execute(buildJsonObject { put("source", "input.zip"); put("limit", 2) }))
        assertEquals(2, result["entries"]?.jsonArray?.size)
        assertEquals(false, result["truncated"]?.jsonPrimitive?.boolean)
    }

    @Test fun invalidListingLimitIsRejected(): Unit = runBlocking {
        val access = access()
        access.write(access.resolve("input.zip", true), zip("first"))
        val result = body(listZipContentsTool(context, access).execute(buildJsonObject { put("source", "input.zip"); put("limit", 0) }))
        assertNotNull("Zero must not silently disable the listing limit", result["error"])
    }

    @Test fun listingStillChecksEntryCountBeyondTheReturnedLimit(): Unit = runBlocking {
        val access = access()
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive ->
            repeat(10_001) { index -> archive.putNextEntry(ZipEntry("entry-$index")); archive.closeEntry() }
        } }.toByteArray()
        access.write(access.resolve("many.zip", true), bytes)
        val result = body(listZipContentsTool(context, access).execute(buildJsonObject { put("source", "many.zip"); put("limit", 1) }))
        assertNotNull("A response limit must not bypass the archive entry budget", result["error"])
    }

    @Test fun listingStillChecksExpandedBytesBeyondTheReturnedLimit(): Unit = runBlocking {
        val access = access()
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("first")); archive.closeEntry()
            archive.putNextEntry(ZipEntry("bomb"))
            val block = ByteArray(1024 * 1024)
            repeat(513) { archive.write(block) }
            archive.closeEntry()
        } }.toByteArray()
        access.write(access.resolve("bomb.zip", true), bytes)
        val result = body(listZipContentsTool(context, access).execute(buildJsonObject { put("source", "bomb.zip"); put("limit", 1) }))
        assertNotNull("Hidden entries must still consume the expanded-byte budget", result["error"])
    }

    /** Observe the real job installed by withTimeoutOrNull, without adding a production test seam. */
    private class ThreadJob : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ThreadJob>
        private val job = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? = job.get().also { job.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { job.set(oldState) }
        @OptIn(ExperimentalCoroutinesApi::class)
        fun fireOperationTimeout() {
            var current = job.get()
            while (current != null && current.javaClass.simpleName != "TimeoutCoroutine") current = current.parent
            checkNotNull(current) { "The shared-write hook must run within the operation's real timeout" }
            (current as Runnable).run()
        }
    }

    private inner class SharedFixture {
        val primary = temporary.newFolder()
        val scratch = temporary.newFolder()
        var beforeVolumeLookup: () -> Unit = {}
        val policy = SharedStorageAccess({ true }, { true }, { beforeVolumeLookup(); mapOf("primary" to primary) }, emptyList())
        val access = LocalFileAccess(scratch, temporary.newFolder(), sharedStorageAccess = policy)
        val target = File(primary, "target.bin").apply { writeText("original") }
        fun retainedScratch(): File = scratch.listFiles().orEmpty().single { it.name.startsWith(".archive-") }
    }

    private suspend fun sharedFailure(extract: Boolean, timeout: Boolean) {
        val f = SharedFixture()
        val observer = ThreadJob()
        val tool: Tool
        val input: JsonObject
        if (extract) {
            // The overwritten archive entry has a backup before the shared stage is published.
            f.access.write(f.access.resolve("input.zip", true), zip(f.target.name))
            tool = unzipFileTool(context, f.access)
            input = buildJsonObject { put("source", "input.zip"); put("destination_dir", f.primary.path); put("overwrite", true) }
        } else {
            f.access.write(f.access.resolve("source.txt", true), "source".toByteArray())
            tool = zipFilesTool(context, f.access)
            input = buildJsonObject {
                put("sources", buildJsonArray { add("source.txt") }); put("destination", f.target.path); put("overwrite", true)
            }
        }
        var fired = false
        f.beforeVolumeLookup = {
            val stageWritten = f.primary.listFiles().orEmpty().any { it.name.startsWith(".rk-write-") && it.length() > 0 }
            if (!fired && stageWritten) {
                fired = true
                if (timeout) observer.fireOperationTimeout() else throw IOException("shared publication interrupted")
            }
        }
        val result = withContext(observer) { body(tool.execute(input)) }
        assertTrue("The test must interrupt the actual shared publication", fired)
        val retained = f.retainedScratch()
        assertEquals("original", File(retained, "backup-0").readText())
        val warning = result["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("Recovery warning must identify the destination: $result", warning.contains(f.target.path))
        assertTrue("Recovery warning must identify retained scratch: $result", warning.contains("/scratch/${retained.name}"))
        assertTrue("Recovery warning must identify the backup: $result", warning.contains("/scratch/${retained.name}/backup-0"))
        assertFalse("A failed publication must not report success", result["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test fun zipOwnTimeoutRetainsRecoveryPaths(): Unit = runBlocking { sharedFailure(extract = false, timeout = true) }
    @Test fun extractionOwnTimeoutRetainsRecoveryPaths(): Unit = runBlocking { sharedFailure(extract = true, timeout = true) }
    @Test fun zipOrdinaryFailureRetainsRecoveryPaths(): Unit = runBlocking { sharedFailure(extract = false, timeout = false) }
    @Test fun extractionOrdinaryFailureRetainsRecoveryPaths(): Unit = runBlocking { sharedFailure(extract = true, timeout = false) }

    @Test fun parentCancellationDuringSharedPublicationStillPropagates(): Unit = runBlocking {
        val f = SharedFixture()
        f.access.write(f.access.resolve("source.txt", true), "source".toByteArray())
        var fired = false
        var returned = false
        lateinit var job: Job
        f.beforeVolumeLookup = {
            if (!fired && f.primary.listFiles().orEmpty().any { it.name.startsWith(".rk-write-") && it.length() > 0 }) {
                fired = true; job.cancel(CancellationException("parent cancelled"))
            }
        }
        job = launch(start = CoroutineStart.LAZY) {
            zipFilesTool(context, f.access).execute(buildJsonObject {
                put("sources", buildJsonArray { add("source.txt") }); put("destination", f.target.path); put("overwrite", true)
            })
            returned = true
        }
        job.start(); job.join()
        assertTrue("Must reach shared publication", fired)
        assertTrue(job.isCancelled)
        assertFalse("Parent cancellation must not become a JSON tool error", returned)
        assertTrue(File(f.retainedScratch(), "backup-0").isFile)
    }

    @Test fun extractionRefusesProviderRenamingAndDeletesOnlyTheCreatedDocument(): Unit = runBlocking {
        assertRenamedExtractionRejected(directory = false)
    }

    @Test fun extractionRefusesProviderDirectoryRenamingAndPreservesExistingDocuments(): Unit = runBlocking {
        assertRenamedExtractionRejected(directory = true)
    }

    private suspend fun assertRenamedExtractionRejected(directory: Boolean) {
        val tree = Uri.parse("content://archive.audit.documents/tree/root")
        val provider = RenamingProvider(context, tree)
        attachProvider(provider, tree.authority!!)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), context = context)
        val archiveBytes = zip(if (directory) "requested/" else "requested.txt")
        val source = access.resolve("/upload/input.zip", true)
        access.write(source, archiveBytes)
        val result = body(unzipFileTool(context, access).execute(buildJsonObject { put("source", "/upload/input.zip"); put("destination_dir", tree.toString()) }))
        assertNotNull("Provider renaming must not claim successful extraction: $result", result["error"])
        assertEquals(listOf("provider-renamed"), provider.createdNames)
        assertEquals("Cleanup must own only the newly returned URI", listOf("provider-renamed"), provider.deletedNames)
        assertEquals(setOf("keep.txt"), provider.names())
        assertEquals("keep", provider.bytes("keep.txt").toString(Charsets.UTF_8))
        assertArrayEquals("Extraction must preserve its source archive", archiveBytes, access.openInput(source).use { it.readBytes() })
        assertTrue("A safe provider rollback should remove scratch", access.list(access.resolve("/scratch")).isEmpty())
    }

    private fun attachProvider(provider: DocumentsProvider, authority: String) {
        val info = ProviderInfo().apply {
            this.authority = authority; name = provider.javaClass.name; packageName = context.packageName
            exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }
        provider.attachInfo(context, info)
        val transport = QueryTransport(provider).also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, transport)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE), ResolveInfo().apply { providerInfo = info })
    }

    private class QueryTransport(private val documents: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            documents.query(uri, projection, Bundle().apply {
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

    private class RenamingProvider(private val context: Context, private val tree: Uri) : DocumentsProvider() {
        private val contents = linkedMapOf("keep.txt" to "keep".toByteArray())
        private val directories = mutableSetOf("root")
        val createdNames = mutableListOf<String>()
        val deletedNames = mutableListOf<String>()
        fun names(): Set<String> = contents.keys + (directories - "root")
        fun bytes(name: String): ByteArray = contents.getValue(name)
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(documentId).filter { it == "root" || it in names() }, projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = rows(if (parentDocumentId == "root") names().toList() else emptyList(), projection)
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = parentDocumentId == "root" && documentId in names()
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(parentDocumentId == "root")
            val id = "provider-renamed"
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) directories += id else contents[id] = byteArrayOf()
            createdNames += id
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(contents[id] ?: throw FileNotFoundException(id)) }
            shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) { object : ByteArrayOutputStream() {
                override fun close() { contents[id] = toByteArray(); super.close() }
            } }
            return id
        }
        override fun deleteDocument(documentId: String) {
            require(documentId in names())
            deletedNames += documentId; contents.remove(documentId); directories.remove(documentId)
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = throw FileNotFoundException(documentId)
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply { ids.forEach { id -> addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id in directories) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> contents[id]?.size ?: 0
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0
                else -> null
            } }) } }
        }
    }
}
