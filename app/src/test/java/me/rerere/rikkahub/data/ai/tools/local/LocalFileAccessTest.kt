package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.OutputStream

class LocalFileAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun scratchPathsResolveAndInternalAppFilesAreBlocked(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val upload = temporary.newFolder("upload")
        val access = LocalFileAccess(scratch, upload)
        assertEquals(File(scratch, "report.txt"), (access.resolve("~/report.txt") as LocalFileSource.Local).file)
        assertEquals(File(upload, "photo.jpg"), (access.resolve("/upload/photo.jpg") as LocalFileSource.Local).file)
        assertThrows(IllegalArgumentException::class.java) { access.checkFile(File(temporary.root, "shared_prefs/tokens.xml")) }
        assertThrows(IllegalArgumentException::class.java) { access.checkFile(File("/system/build.prop")) }
    }

    @Test fun symlinkEscapesAndParentTraversalCannotReachOutsideTheGrantedRoot() = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val outside = temporary.newFolder("private")
        val access = LocalFileAccess(scratch, temporary.newFolder("upload"))
        Files.createSymbolicLink(File(scratch, "escape").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { access.checkFile(File(scratch, "escape/secret")) }
        try { access.resolve("../private/secret"); fail("traversal allowed") } catch (_: IllegalArgumentException) { }
        try { access.list(access.resolve("/scratch")); fail("recursive child guard bypassed") } catch (_: IllegalArgumentException) { }
    }

    @Test fun writesRefuseExistingFilesAndFailedCopyPreservesDestination() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        val source = access.resolve("source.txt", write = true)
        val destination = access.resolve("destination.txt", write = true)
        access.write(source, "new".toByteArray())
        access.write(destination, "old".toByteArray())
        try { access.copy(source, destination); fail("overwrite allowed") } catch (_: IllegalArgumentException) { }
        assertEquals("old", access.openInput(destination).use { it.reader().readText() })
        access.copy(source, destination, overwrite = true)
        assertEquals("new", access.openInput(destination).use { it.reader().readText() })
    }

    @Test fun treeGrantRequiresMatchingAuthorityTreeAndDocumentBoundaries() {
        val grant = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertTrue(contentTreeGrantCovers(grant, "$grant/document/primary%3ADocuments%2Freport.txt"))
        assertFalse(contentTreeGrantCovers(grant, "$grant/document/primary%3ADocuments2%2Fsecret.txt"))
        assertFalse(contentTreeGrantCovers(grant, "content://evil/tree/primary%3ADocuments"))
        assertFalse(contentTreeGrantCovers(grant, "content://com.android.externalstorage.documents/tree/primary%3ADocuments2"))
    }

    @Test fun exclusiveCreationDoesNotReturnOrTruncateAnExistingFile() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        val parent = access.resolve("/scratch")
        val first = access.createFile(parent, "reserved.txt")
        access.write(first, "keep".toByteArray(), overwrite = true)
        try { access.createFile(parent, "reserved.txt"); fail("existing file returned") } catch (_: IllegalArgumentException) { }
        assertEquals("keep", access.openInput(first).use { it.reader().readText() })
    }

    @Test fun contentIdentityIgnoresTreeAliasesAndPercentEncodingCaseButNotAuthority() {
        val original = "content://documents/tree/rootA/document/primary%3AFolder%2Fnote.txt"
        val alias = "content://documents/tree/rootB/document/primary%3aFolder%2fnote.txt"
        assertTrue(sameDocumentIdentity(original, alias))
        assertFalse(sameDocumentIdentity(original, alias.replace("//documents/", "//other/")))
        assertTrue(contentDocumentIsWithin("content://documents/tree/root/document/primary%3AFolder", alias))
        assertFalse(contentDocumentIsWithin("content://other/tree/root/document/primary%3AFolder", alias))
    }

    @Test fun movingCanonicalAliasesCannotDeleteTheSource() = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val access = LocalFileAccess(scratch, temporary.newFolder("upload"))
        val original = access.resolve("keep.txt", true)
        access.write(original, "keep".toByteArray())
        try { access.move(original, LocalFileSource.Local(File(scratch, "./keep.txt")), overwrite = true); fail("self move allowed") } catch (_: IllegalArgumentException) { }
        assertEquals("keep", access.openInput(original).use { it.reader().readText() })
    }

    @Test fun cancelledProviderWriteRestoresOriginalBytesAndDeletesBackupAfterRecovery() = runBlocking {
        val replacement = temporary.newFile("replacement").apply { writeText("replacement") }
        val backup = temporary.newFile("backup")
        val destination = temporary.newFile("destination").apply { writeText("original") }
        var openings = 0
        try {
            val job = Job()
            withContext(job) {
                commitStreamWithRollback(replacement, backup, { destination.inputStream() }, {
                    openings++
                    val output = destination.outputStream()
                    if (openings == 1) object : OutputStream() {
                        override fun write(value: Int) { output.write(value); job.cancel(CancellationException("cancel")) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, minOf(length, 2)); job.cancel(CancellationException("cancel")) }
                        override fun close() = output.close()
                    } else output
                })
            }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals("original", destination.readText())
        assertFalse(backup.exists())
    }

    @Test fun cancellationDuringProviderCloseAlsoRestoresTheOriginal() = runBlocking {
        val replacement=temporary.newFile("close-replacement").apply { writeText("replacement") }
        val backup=temporary.newFile("close-backup")
        val destination=temporary.newFile("close-destination").apply { writeText("original") }
        val job=Job(); var openings=0
        try {
            withContext(job) {
                commitStreamWithRollback(replacement,backup,{ destination.inputStream() },{
                    val output=destination.outputStream()
                    if(++openings==1) object:OutputStream() {
                        override fun write(value:Int)=output.write(value)
                        override fun write(bytes:ByteArray,offset:Int,length:Int)=output.write(bytes,offset,length)
                        override fun close() { output.close(); job.cancel() }
                    } else output
                })
            }
            fail("cancellation swallowed")
        } catch(_:CancellationException) { }
        assertEquals("original",destination.readText()); assertFalse(backup.exists())
    }

    @Test fun failedProviderRestorationKeepsCompleteBackupForRecovery() = runBlocking {
        val replacement = temporary.newFile("replacement").apply { writeText("replacement") }
        val backup = temporary.newFile("backup")
        try {
            commitStreamWithRollback(replacement, backup, { ByteArrayInputStream("original".toByteArray()) }, { throw java.io.IOException("provider unavailable") })
            fail("restoration failure hidden")
        } catch (error: FileRestoreFailedException) { assertEquals(backup, error.backup) }
        assertEquals("original", backup.readText())
    }

    @Test fun exclusiveDirectoryCreationDoesNotClaimAnExistingDirectory() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        val parent = access.resolve("/scratch")
        val directory = access.createNewChild(parent, "folder", isDirectory = true)
        assertTrue(access.isDirectory(directory))
        try { access.createNewChild(parent, "folder", isDirectory = true); fail("existing directory returned") } catch (_: IllegalArgumentException) { }
        assertTrue(access.isDirectory(directory))
    }
}
