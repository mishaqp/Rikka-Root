package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Remaining audit 46: same-volume overwrite must preserve data at each transaction boundary. */
class FileMoveOverwriteRemainingRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun largeSameVolumeOverwriteMovesTheSourceWithoutAllocatingACopy(): Unit = runBlocking {
        val scratch = temporary.newFolder()
        val access = LocalFileAccess(scratch, temporary.newFolder())
        val source = File(scratch, "large.bin")
        val target = File(scratch, "target.bin").apply { writeText("previous target") }
        val length = LocalFileAccess.MAX_COPY_BYTES + 1
        RandomAccessFile(source, "rw").use {
            it.setLength(length); it.write(73); it.seek(length - 1); it.write(91)
        }
        val sourceKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val result = try { access.movePath(access.resolve(source.path), target.path, overwrite = true) }
        catch (failure: Exception) {
            assertEquals("Failed overwrite must preserve the old target", "previous target", target.readText())
            assertEquals("Failed overwrite must preserve the source", length, source.length())
            throw AssertionError("A same-volume overwrite must not copy 512 MiB and then hit the copy limit", failure)
        }
        assertEquals(length, result.second)
        assertFalse(source.exists())
        assertEquals(sourceKey, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        RandomAccessFile(target, "r").use {
            assertEquals(73, it.read()); it.seek(length - 1); assertEquals(91, it.read())
        }
        assertEquals("A completed overwrite leaves no backup or copy stage", listOf("target.bin"), scratch.list()!!.toList())
    }

    @Test fun failureAfterPreservingOldTargetRestoresItAndLeavesSourceIntact(): Unit = runBlocking {
        interruptedOverwrite(foreignTarget = false)
    }

    @Test fun racedTargetIsNeverReplacedDuringRollbackAndOldBytesRemainRecoverable(): Unit = runBlocking {
        interruptedOverwrite(foreignTarget = true)
    }

    @Test fun largeSameVolumeMoveCreatesMissingParentsWithoutAllocatingACopy(): Unit = runBlocking {
        val scratch = temporary.newFolder()
        val access = LocalFileAccess(scratch, temporary.newFolder())
        val source = File(scratch, "large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(LocalFileAccess.MAX_COPY_BYTES + 1) }
        val sourceKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val target = File(scratch, "new/nested/large.bin")
        access.movePath(access.resolve(source.path), target.path)
        assertFalse(source.exists())
        assertEquals(sourceKey, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(LocalFileAccess.MAX_COPY_BYTES + 1, target.length())
        assertFalse(scratch.walkTopDown().any { it.name.startsWith(".rk-write-") })
    }

    @Test fun cancellationAfterTargetBackupRestoresOriginalAndPropagates(): Unit = runBlocking {
        val primary = temporary.newFolder()
        val source = File(primary, "source.bin").apply { writeText("new source") }
        val target = File(primary, "target.bin").apply { writeText("previous target") }
        var cancelled = false
        var returned = false
        lateinit var operation: Job
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess =
            SharedStorageAccess({ true }, { true }, {
                if (!cancelled && !target.exists() && source.exists()) {
                    cancelled = true
                    operation.cancel()
                }
                mapOf("primary" to primary)
            }, emptyList()))
        operation = launch(start = CoroutineStart.LAZY) {
            access.movePath(access.resolve(source.path), target.path, overwrite = true)
            returned = true
        }
        operation.start(); operation.join()
        assertTrue("Cancellation must reach the transaction after its target backup", cancelled)
        assertTrue(operation.isCancelled)
        assertFalse(returned)
        assertEquals("new source", source.readText())
        assertEquals("previous target", target.readText())
        assertEquals(setOf("source.bin", "target.bin"), primary.list()!!.toSet())
    }

    /** The live storage boundary injects an I/O failure after the target has been reserved as backup. */
    private suspend fun interruptedOverwrite(foreignTarget: Boolean) {
        val primary = temporary.newFolder()
        val source = File(primary, "source.bin").apply { writeText("new source") }
        val target = File(primary, "target.bin").apply { writeText("previous target") }
        var interrupted = false
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess =
            SharedStorageAccess({ true }, { true }, {
                if (!interrupted && !target.exists() && source.exists()) {
                    interrupted = true
                    if (foreignTarget) target.writeText("raced foreign target")
                    throw IOException("Injected failure between target backup and source installation")
                }
                mapOf("primary" to primary)
            }, emptyList()))
        var failure: Exception? = null
        try { access.movePath(access.resolve(source.path), target.path, overwrite = true) }
        catch (error: Exception) { failure = error }
        assertTrue("Overwrite must offer a guarded rollback boundary after preserving the old target", interrupted)
        assertNotNull("The injected failure must not report success", failure)
        assertEquals("new source", source.readText())
        if (foreignTarget) {
            assertEquals("Rollback must not replace a raced file", "raced foreign target", target.readText())
            assertTrue("Previous bytes must remain recoverable when rollback cannot restore their name",
                primary.listFiles().orEmpty().any { it != source && it != target && it.isFile && it.readText() == "previous target" })
            val recovery = failure as? FileMoveRecoveryException
            assertNotNull("A blocked rollback must expose its recovery path", recovery)
            assertEquals("previous target", File(recovery!!.backupPath).readText())
        } else {
            assertEquals("previous target", target.readText())
        }
    }
}
