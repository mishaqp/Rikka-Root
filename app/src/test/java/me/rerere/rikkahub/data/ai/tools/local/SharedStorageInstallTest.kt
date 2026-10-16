package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SharedStorageInstallTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failureAfterSharedPublicationKeepsTheUnverifiablePathAndReportsItForManualReview() = runBlocking {
        val staging = temporary.newFile().apply { writeText("complete data") }
        val target = File(temporary.root, "needs-review.txt")
        try {
            installNewSharedStorageFile(staging, target, verifyAccess = {}, write = { _, output ->
                output.write("partial".toByteArray()); throw IOException("disk full")
            })
            fail("A partial shared file must be reported")
        } catch (failure: FileCreationCleanupFailedException) {
            assertEquals(target, (failure.created as LocalFileSource.Local).file)
        }
        assertEquals("A shared pathname can be replaced by another app; cleanup must not unlink it", "partial", target.readText())
    }

    @Test fun exclusiveSharedInstallWorksWithoutHardLinksAndPreservesExistingTargets() = runBlocking {
        val staging = temporary.newFile().apply { writeText("complete data") }
        val target = File(temporary.root, "new.txt")
        installNewSharedStorageFile(staging, target, verifyAccess = {})
        assertEquals("complete data", target.readText())
        staging.writeText("must not replace")
        try { installNewSharedStorageFile(staging, target, verifyAccess = {}); fail("Existing file overwritten") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("complete data", target.readText())
    }

    @Test fun anExistingSymlinkIsNeverFollowedOrReplacedBySharedInstallation() = runBlocking {
        val staging = temporary.newFile().apply { writeText("new data") }
        val outside = temporary.newFile().apply { writeText("private") }
        val target = File(temporary.root, "link.txt")
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        try { installNewSharedStorageFile(staging, target, verifyAccess = {}); fail("Existing symlink was followed") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("private", outside.readText())
        assertTrue(Files.isSymbolicLink(target.toPath()))
    }

    @Test fun partialSharedFileResultKeepsTheMachineCodeAndPathWithAHumanExplanation() = runBlocking {
        val target = temporary.newFile()
        val tool = fileJsonTool("write_text_file", "test", fileSchema()) {
            throw FileCreationCleanupFailedException(LocalFileSource.Local(target), IOException("disk full"), sharedPathMayBePartial = true)
        }
        val result = Json.parseToJsonElement((tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("creation_cleanup_failed", result["error"]!!.jsonPrimitive.content)
        assertEquals(target.path, result["created_path"]!!.jsonPrimitive.content)
        val detail = result["detail"]!!.jsonPrimitive.content
        assertTrue(detail, detail.contains("частично"))
        assertTrue(detail, detail.contains("Проверьте созданный файл перед повтором"))
    }

    @Test fun failureAfterExclusivePublicationReportsPartialFileWithoutUnsafeAutomaticDeletion() = runBlocking {
        val staging = temporary.newFile().apply { writeText("complete data") }
        val target = File(temporary.root, "partial.txt")
        try {
            installNewSharedStorageFile(staging, target, verifyAccess = {}, write = { _, output ->
                output.write("partial".toByteArray()); throw IOException("disk full")
            })
            fail("Failure was swallowed")
        } catch (failure: FileCreationCleanupFailedException) {
            assertTrue(failure.sharedPathMayBePartial)
            assertTrue(failure.message.orEmpty().contains("Файл мог сохраниться частично"))
            assertEquals(target.path, failure.created.reference)
        }
        assertEquals("partial", target.readText())
        assertEquals("complete data", staging.readText())
    }

    @Test fun cancellationAndRevokedCapabilityAfterPublicationReportThePathWithoutDeletingIt() = runBlocking {
        for (cancel in listOf(false, true)) {
            val staging = temporary.newFile().apply { writeText("data") }
            val target = File(temporary.newFolder(), "partial.txt")
            var allowed = true
            val job = Job()
            var interruptedWrite: CancellationException? = null
            try {
                withContext(job) {
                    try {
                        installNewSharedStorageFile(staging, target, verifyAccess = { require(allowed) }, write = { input, output ->
                            output.write(input.readBytes())
                            if (cancel) job.cancel(CancellationException("stop")) else allowed = false
                        })
                    } catch (failure: CancellationException) { interruptedWrite = failure; throw failure }
                }
                fail("Interrupted commit was accepted")
            } catch (failure: CancellationException) {
                assertTrue(cancel)
                // Coroutine cancellation can replace the exception when leaving withContext;
                // verify the diagnostic at the producer boundary where it is attached.
                assertTrue(requireNotNull(interruptedWrite).suppressed.any { it is FileCreationCleanupFailedException && it.sharedPathMayBePartial })
            } catch (failure: FileCreationCleanupFailedException) {
                assertFalse(cancel)
                assertTrue(failure.sharedPathMayBePartial)
            }
            assertEquals("data", target.readText())
        }
    }

    @Test fun failureNeverDeletesAFileThatReplacedTheOwnedDestination() = runBlocking {
        val staging = temporary.newFile().apply { writeText("data") }
        val target = File(temporary.root, "destination.txt")
        try {
            installNewSharedStorageFile(staging, target, verifyAccess = {}, write = { _, output ->
                output.write(1)
                val replacement = temporary.newFile().apply { writeText("other owner") }
                Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                throw IOException("interrupted")
            })
            fail("Recovery warning was hidden")
        } catch (failure: FileCreationCleanupFailedException) { assertEquals(target, (failure.created as LocalFileSource.Local).file) }
        assertEquals("other owner", target.readText())
    }
}
