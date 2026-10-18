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
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

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

    @Test fun failureBeforeAtomicReplacementPreservesBothFiles(): Unit = runBlocking {
        interruptedOverwrite(foreignTarget = false)
    }

    @Test fun changedTargetBeforeFinalVerificationPreservesBothFiles(): Unit = runBlocking {
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

    @Test fun cancellationBeforeAtomicReplacementPreservesBothFilesAndPropagates(): Unit = runBlocking {
        val primary = temporary.newFolder()
        val source = File(primary, "source.bin").apply { writeText("new source") }
        val target = File(primary, "target.bin").apply { writeText("previous target") }
        val sourceKey = fileKey(source)
        val targetKey = fileKey(target)
        var guardReads = 0
        var cancelled = false
        var returned = false
        lateinit var operation: Job
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess =
            SharedStorageAccess({ true }, { true }, {
                // The first two helper reads capture source/target keys. Inject at the
                // next live guard, before the repeated identity check and atomic syscall.
                if (++guardReads == 3) {
                    cancelled = true
                    assertTrue(source.exists())
                    assertTrue(target.exists())
                    operation.cancel()
                }
                mapOf("primary" to primary)
            }, emptyList()))
        operation = launch(start = CoroutineStart.LAZY) {
            invokeReplacement(access, source, target)
            returned = true
        }
        operation.start(); operation.join()
        assertTrue("Cancellation must reach the guarded pre-commit boundary", cancelled)
        assertTrue(operation.isCancelled)
        assertFalse(returned)
        assertEquals("new source", source.readText())
        assertEquals("previous target", target.readText())
        assertEquals(sourceKey, fileKey(source))
        assertEquals(targetKey, fileKey(target))
        assertEquals(setOf("source.bin", "target.bin"), primary.list()!!.toSet())
    }

    /** User's revised contract has no target-backup phase; inject before the atomic commit. */
    private suspend fun interruptedOverwrite(foreignTarget: Boolean) {
        val primary = temporary.newFolder()
        val source = File(primary, "source.bin").apply { writeText("new source") }
        val target = File(primary, "target.bin").apply { writeText("previous target") }
        val sourceKey = fileKey(source)
        var expectedTargetKey = fileKey(target)
        var guardReads = 0
        var interrupted = false
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess =
            SharedStorageAccess({ true }, { true }, {
                if (++guardReads == 3) {
                    interrupted = true
                    assertEquals("new source", source.readText())
                    assertEquals("previous target", target.readText())
                    if (foreignTarget) {
                        val competitor = File(primary, "competitor.bin").apply { writeText("raced foreign target") }
                        val previousKey = expectedTargetKey
                        Files.move(competitor.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        expectedTargetKey = fileKey(target)
                        assertNotEquals("The competing writer must replace the inode captured by preflight", previousKey, expectedTargetKey)
                    } else throw IOException("Injected failure before the atomic source installation")
                }
                mapOf("primary" to primary)
            }, emptyList()))
        var failure: Exception? = null
        try { invokeReplacement(access, source, target) }
        catch (error: Exception) { failure = error }
        assertTrue("The last live verification boundary must be exercised", interrupted)
        assertNotNull("Pre-commit refusal must not report success", failure)
        assertEquals("new source", source.readText())
        assertEquals(sourceKey, fileKey(source))
        if (foreignTarget) {
            assertEquals("Refusal must not replace the competing writer's file", "raced foreign target", target.readText())
            assertEquals(expectedTargetKey, fileKey(target))
            assertTrue("Identity change must be detected before committing", failure is IllegalArgumentException)
            assertEquals(setOf("source.bin", "target.bin"), primary.list()!!.toSet())
        } else {
            assertEquals("previous target", target.readText())
            assertEquals(expectedTargetKey, fileKey(target))
            assertTrue(failure is IOException)
            assertEquals(setOf("source.bin", "target.bin"), primary.list()!!.toSet())
        }
    }

    private fun fileKey(file: File): Any? = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    /** Call the real guarded helper; support only its original and revised known signatures. */
    private suspend fun invokeReplacement(access: LocalFileAccess, source: File, target: File): Long =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = LocalFileAccess::class.java.declaredMethods.single { it.name == "replaceLocalMove" }
                .apply { isAccessible = true }
            try {
                when (method.parameterTypes.toList()) {
                    listOf(File::class.java, File::class.java, java.lang.Long.TYPE, Continuation::class.java) ->
                        method.invoke(access, source, target, source.length(), continuation)
                    listOf(File::class.java, File::class.java, java.lang.Long.TYPE, java.lang.Boolean.TYPE, Continuation::class.java) ->
                        method.invoke(access, source, target, source.length(), false, continuation)
                    else -> error("Unexpected atomic move helper signature; no failure/cancellation was injected")
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
}
