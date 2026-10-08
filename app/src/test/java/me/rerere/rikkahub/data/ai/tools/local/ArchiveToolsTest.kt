package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveToolsTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun traversalNamesNeverReachTheDestination() = runBlocking {
        val unsafe = listOf("../x", "/x", "C:/x", "a/../x", "a\\..\\x", "./x", "a//x", "a\u0000x")
        for (name in unsafe) {
            var visited = false
            try {
                readZipArchive(ByteArrayInputStream(zip(name to byteArrayOf(1)))) { _, _, _ -> visited = true }
                fail("Accepted $name")
            } catch (_: ArchiveSafetyException) { assertFalse(visited) }
        }
        assertEquals(listOf("folder", "file.txt"), archiveEntryParts("folder/file.txt"))
    }

    @Test fun unknownEntrySizesStillEnforceBothByteLimits() = runBlocking {
        val data = zip("a" to ByteArray(8), "b" to ByteArray(8))
        try {
            readZipArchive(ByteArrayInputStream(data), ArchiveLimits(maxEntryBytes = 7, maxTotalBytes = 20)) { _, input, budget ->
                copyArchiveEntry(input, ByteArrayOutputStream(), budget)
            }
            fail("Per-entry limit was ignored")
        } catch (_: ArchiveSafetyException) { }
        try {
            readZipArchive(ByteArrayInputStream(data), ArchiveLimits(maxEntryBytes = 10, maxTotalBytes = 12)) { _, input, budget ->
                copyArchiveEntry(input, ByteArrayOutputStream(), budget)
            }
            fail("Total limit was ignored")
        } catch (_: ArchiveSafetyException) { }
    }

    @Test fun listingDrainsEntriesWithTheSameLimitsAndCountsDirectories() = runBlocking {
        try {
            readZipArchive(ByteArrayInputStream(zip("dir/" to byteArrayOf(), "dir/a" to byteArrayOf(1))), ArchiveLimits(maxEntries = 1)) { _, _, _ -> }
            fail("Directory entries must count toward the ceiling")
        } catch (_: ArchiveSafetyException) { }
        try {
            readZipArchive(ByteArrayInputStream(zip("a" to ByteArray(20))), ArchiveLimits(maxEntryBytes = 10)) { _, _, _ -> }
            fail("Unread listing entries bypassed the byte ceiling")
        } catch (_: ArchiveSafetyException) { }
    }

    @Test fun cancelledAndFailedReadsCloseTheArchiveSource() = runBlocking {
        var closed = false
        val input = object : ByteArrayInputStream(zip("a" to ByteArray(8))) {
            override fun close() { closed = true; super.close() }
        }
        try {
            readZipArchive(input) { _, _, _ -> throw CancellationException("generation cancelled") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertTrue(closed)
    }

    @Test fun failedZipSourcesCloseTheDestinationAndEarlierInputs() = runBlocking {
        var inputClosed = false
        var outputClosed = false
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        try {
            writeZipArchive(listOf(
                ArchiveSource("a") { object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { inputClosed = true; super.close() } } },
                ArchiveSource("b") { throw IllegalStateException("source unavailable") }
            ), output, 6)
            fail("Missing source was ignored")
        } catch (_: IllegalStateException) { }
        assertTrue(inputClosed)
        assertTrue(outputClosed)
    }

    @Test fun recursiveSourcesRejectSymlinksBeforeOpeningTheirTargets() = runBlocking {
        val temp = Files.createTempDirectory("archive-links").toFile()
        try {
            val scratch = File(temp, "scratch")
            val access = LocalFileAccess(scratch, File(temp, "upload"))
            val root = File(scratch, "source").apply { mkdir() }
            val outside = File(temp, "private.txt").apply { writeText("private") }
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            try {
                collectArchiveSources(access, listOf(access.resolve("/scratch/source")), null)
                fail("Recursive traversal accepted a symlink")
            } catch (_: ArchiveSafetyException) { }
        } finally { temp.deleteRecursively() }
    }

    @Test fun outputLimitFailuresStillCloseTheRawArchiveDestination() = runBlocking {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        try {
            writeZipArchive(listOf(ArchiveSource("a") { ByteArrayInputStream(byteArrayOf(1)) }), output, 6,
                ArchiveLimits(maxArchiveBytes = 10))
            fail("Compressed output limit was ignored")
        } catch (_: ArchiveSafetyException) { }
        assertTrue(closed)
    }

    @Test fun cancellationRecordsANewDocumentBeforePropagating() = runBlocking {
        val creating = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var recorded: String? = null
        var returned = false
        val job = launch {
            recordArchiveCreation(create = { creating.complete(Unit); released.await(); "created-document" },
                record = { recorded = it })
            returned = true
        }
        creating.await()
        job.cancel()
        released.complete(Unit)
        job.join()
        assertEquals("created-document", recorded)
        assertFalse(returned)
    }

    @Test fun aNameCollisionNeverGivesTheArchiveOwnershipOfExistingFilesOrDirectories() = runBlocking {
        val temp = Files.createTempDirectory("archive-ownership").toFile()
        try {
            val scratch = File(temp, "scratch")
            val access = LocalFileAccess(scratch, File(temp, "upload"))
            val destination = File(scratch, "destination").apply { mkdir() }
            val file = File(destination, "existing.txt").apply { writeText("keep") }
            val directory = File(destination, "existing-dir").apply { mkdir() }
            val sentinel = File(directory, "keep.txt").apply { writeText("keep directory") }
            val parent = access.resolve("/scratch/destination")
            var recorded = false
            for ((name, isDirectory) in listOf("existing.txt" to false, "existing-dir" to true)) {
                try {
                    createOwnedArchiveChild(access, parent, name, isDirectory) { recorded = true }
                    fail("A preexisting target became archive-owned")
                } catch (_: IllegalArgumentException) { }
            }
            assertFalse(recorded)
            assertEquals("keep", file.readText())
            assertEquals("keep directory", sentinel.readText())
        } finally { temp.deleteRecursively() }
    }
}
