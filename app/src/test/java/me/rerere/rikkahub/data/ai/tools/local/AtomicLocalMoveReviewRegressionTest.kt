package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class AtomicLocalMoveReviewRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `same-volume overwrite never hides the destination before committing the source`() = runBlocking {
        val primary = folder.newFolder("primary")
        val source = File(primary, "source.pdf").apply { writeText("new document") }
        val target = File(primary, "target.pdf").apply { writeText("previous document") }
        val sourceKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        var observing = false
        var exposedMissingDestination = false
        val access = LocalFileAccess(folder.newFolder("scratch"), folder.newFolder("upload"), sharedStorageAccess =
            SharedStorageAccess({ true }, { true }, {
                // Observe real filesystem state at the existing live guard boundaries.
                // A process death in this state would strand the old target under a hidden name.
                if (observing && source.exists() && !target.exists()) exposedMissingDestination = true
                mapOf("primary" to primary)
            }, emptyList()))
        val from = access.resolve(source.path)
        observing = true
        val result = access.movePath(from, target.path, overwrite = true)
        assertFalse("An overwrite must not expose source-present/target-missing between two renames", exposedMissingDestination)
        assertEquals("new document".toByteArray().size.toLong(), result.second)
        assertFalse(source.exists())
        assertEquals("new document", target.readText())
        assertEquals(sourceKey, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(setOf("target.pdf"), primary.list()!!.toSet())
    }

    @Test fun `case-only destination with the same file key succeeds without overwrite permission`() = runBlocking {
        val scratch = folder.newFolder("scratch")
        val source = File(scratch, "Report.pdf").apply { writeText("document bytes") }
        val target = File(scratch, "report.pdf")
        // Linux's two hard-link aliases are a deterministic same-key surrogate for
        // casefold lookup. This test claims successful reporting/data preservation,
        // not a physical Android filesystem spelling change.
        Files.createLink(target.toPath(), source.toPath())
        val key = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        assertNotNull(key)
        assertEquals(key, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        val access = LocalFileAccess(scratch, folder.newFolder("upload"))
        val result = runCatching { access.movePath(access.resolve(source.path), target.path) }
        assertTrue("A case-only same-inode rename is not a collision with another document: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("document bytes".toByteArray().size.toLong(), result.getOrThrow().second)
        assertEquals("document bytes", target.readText())
        assertEquals(key, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())
        assertTrue(scratch.listFiles()!!.all { it.name in setOf("Report.pdf", "report.pdf") })
    }

    @Test fun `same-inode aliases with unrelated names still require explicit overwrite`() = runBlocking {
        val scratch = folder.newFolder("scratch")
        val source = File(scratch, "first.pdf").apply { writeText("keep") }
        val target = File(scratch, "second.pdf")
        Files.createLink(target.toPath(), source.toPath())
        val access = LocalFileAccess(scratch, folder.newFolder("upload"))
        val result = runCatching { access.movePath(access.resolve(source.path), target.path) }
        assertTrue(result.isFailure)
        assertEquals("keep", source.readText())
        assertEquals("keep", target.readText())
    }
}
