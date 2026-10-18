package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AccessDeniedException

/** On a phone, SELinux answers link(2) in app storage with EACCES; new files must still be created. */
class AndroidHardLinkDeniedTest {
    @get:Rule val temp = TemporaryFolder()
    private val original = privateHardLink

    @Before fun denyHardLinks() {
        privateHardLink = { target, _ -> throw AccessDeniedException(target.toString()) }
    }

    @After fun restore() { privateHardLink = original }

    @Test fun `new text file, copy and move into a new folder work without hard links`() = runBlocking {
        val scratch = temp.newFolder("scratch")
        val access = LocalFileAccess(scratch, temp.newFolder("upload"))
        access.writePath("/scratch/test/a.txt".also { File(scratch, "test").mkdirs() }, "один".toByteArray())
        assertEquals("один", File(scratch, "test/a.txt").readText())

        access.copyPath(access.resolve("/scratch/test/a.txt"), "/scratch/test/b.txt")
        assertEquals("один", File(scratch, "test/b.txt").readText())

        access.movePath(access.resolve("/scratch/test/b.txt"), "/scratch/test/moved/b.txt")
        assertEquals("один", File(scratch, "test/moved/b.txt").readText())
        assertFalse(File(scratch, "test/b.txt").exists())
        assertTrue(scratch.walk().none { it.name.startsWith(".rk-") })
    }

    @Test fun `fallback never replaces an existing file`() = runBlocking {
        val scratch = temp.newFolder("scratch")
        val access = LocalFileAccess(scratch, temp.newFolder("upload"))
        File(scratch, "keep.txt").writeText("старое")
        val failed = runCatching { access.writePath("/scratch/keep.txt", "новое".toByteArray()) }
        assertTrue(failed.isFailure)
        assertEquals("старое", File(scratch, "keep.txt").readText())
    }
}
