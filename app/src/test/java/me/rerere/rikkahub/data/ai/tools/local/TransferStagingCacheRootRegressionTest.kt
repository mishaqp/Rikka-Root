package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Run after proposed staging02 exists: absence of the helper is not behavioral RED. */
class TransferStagingCacheRootRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(cache: File) = mockk<Context> {
        every { cacheDir } returns cache
        every { externalCacheDirs } returns emptyArray()
    }

    @Test fun startupCleanupRejectsACacheRootSymlinkInsteadOfDeletingOutsideUserBytes() {
        val cache = temporary.newFolder("cache")
        val context = context(cache)
        val owned = TransferStaging.create(context)
        owned.openOutput().use { it.write("OWNED".toByteArray()) }
        val parked = File(temporary.root, "parked-cache")
        Files.move(cache.toPath(), parked.toPath())
        val outside = temporary.newFolder("outside")
        val publicFolder = File(outside, "rikka-transfer-staging").apply { mkdir() }
        val userFile = File(publicFolder, "transfer-user.part").apply { writeText("USER BYTES") }
        Files.createSymbolicLink(cache.toPath(), outside.toPath())

        cleanupInterruptedTransferStaging(context)

        assertTrue("Canonicalization must not turn outside storage into an owned cache", userFile.exists())
        assertEquals("USER BYTES", userFile.readText())
        assertEquals("OWNED", File(File(parked, "rikka-transfer-staging"), owned.file.name).readText())
        assertFalse(owned.cleanup())
    }

    @Test fun transferCreationRejectsACacheRootSymlinkWithoutCreatingAnOutsideStage() {
        val cache = File(temporary.root, "cache-link")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(cache.toPath(), outside.toPath())

        val result = runCatching { TransferStaging.create(context(cache)) }

        assertTrue("A stage must not be created through a replaced root", result.isFailure)
        assertTrue("No outside path should be created before refusing the root", outside.listFiles()!!.isEmpty())
    }
}
