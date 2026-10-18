package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Supplemental tests of the new cache capability, not old-source RED evidence. */
class TransferStagingCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(cache: File) = mockk<Context> {
        every { cacheDir } returns cache
        every { externalCacheDirs } returns emptyArray()
    }

    @Test fun `startup removes owned interrupted stages only and never follows links or nested directories`() {
        val cache = temporary.newFolder("cache")
        val public = temporary.newFolder("public")
        val context = context(cache)
        val stage = TransferStaging.create(context)
        stage.openOutput().use { it.write("INTERRUPTED".toByteArray()) }
        val userFiles = listOf(".rikka-sftp-user.part", ".download-user.partial", "transfer-user.part").flatMap { name ->
            listOf(File(public, name), File(cache, name)).onEach { it.writeText("USER") }
        }
        val unrelated = File(stage.file.parentFile, "unrelated.txt").apply { writeText("KEEP") }
        val link = File(stage.file.parentFile, "transfer-link.part")
        Files.createSymbolicLink(link.toPath(), userFiles.first().toPath())
        val nested = File(stage.file.parentFile, "transfer-directory.part").apply { mkdir() }
        val inner = File(nested, "transfer-inner.part").apply { writeText("KEEP NESTED") }
        cleanupInterruptedTransferStaging(context)
        assertFalse(stage.file.exists())
        userFiles.forEach { assertEquals("USER", it.readText()) }
        assertEquals("KEEP", unrelated.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("KEEP NESTED", inner.readText())
    }

    @Test fun `startup rejects a dedicated cache directory replaced by an outside symlink`() {
        val cache = temporary.newFolder("cache")
        val outside = temporary.newFolder("outside")
        val context = context(cache)
        val stage = TransferStaging.create(context)
        stage.openOutput().use { it.write("OWNED".toByteArray()) }
        val parent = stage.file.parentFile!!
        val parked = File(cache, "parked")
        Files.move(parent.toPath(), parked.toPath())
        Files.createSymbolicLink(parent.toPath(), outside.toPath())
        val foreign = File(outside, stage.file.name).apply { writeText("FOREIGN") }
        cleanupInterruptedTransferStaging(context)
        assertEquals("FOREIGN", foreign.readText())
        assertEquals("OWNED", File(parked, stage.file.name).readText())
        assertFalse(stage.cleanup())
    }

    @Test fun `staging identity checks refuse publication or cleanup of a replacement inode`() {
        val cache = temporary.newFolder("cache")
        val stage = TransferStaging.create(context(cache))
        stage.openOutput().use { it.write("OWNED".toByteArray()) }
        val parked = File(stage.file.parentFile, "owned.kept")
        Files.move(stage.file.toPath(), parked.toPath())
        stage.file.writeText("FOREIGN")
        assertTrue(runCatching { stage.checkedFile() }.isFailure)
        assertFalse(stage.cleanup())
        assertEquals("FOREIGN", stage.file.readText())
        assertEquals("OWNED", parked.readText())
    }

    @Test fun `startup work is bounded and leaves excess interrupted files for a later launch`() {
        val cache = temporary.newFolder("cache")
        val context = context(cache)
        val first = TransferStaging.create(context)
        repeat(299) { index -> File(first.file.parentFile, "transfer-extra-$index.part").writeText("INTERRUPTED") }
        cleanupInterruptedTransferStaging(context)
        assertEquals(44, first.file.parentFile!!.listFiles()!!.count { it.isFile })
        cleanupInterruptedTransferStaging(context)
        assertTrue(first.file.parentFile!!.listFiles()!!.isEmpty())
    }
}
