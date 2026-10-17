package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Audit regressions use public/baseline APIs so their initial failure is behavioral. */
class FileAccessAuditRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val scratch = temporary.newFolder()
        val upload = temporary.newFolder()
        val primary = temporary.newFolder()
        var enabled = true
        var granted = true
        var volumeLookups = 0
        val policy = SharedStorageAccess({ enabled }, { granted }, {
            volumeLookups++
            mapOf("primary" to primary)
        })
        val access = LocalFileAccess(scratch, upload, sharedStorageAccess = policy)
    }

    @Test fun defaultListingStaysStrictWhenTheDirectoryCannotBeFullyRead(): Unit = runBlocking {
        val f = Fixture()
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(f.scratch, "entry-$it").createNewFile() }
        try { f.access.list(f.access.resolve("/scratch")); fail("Strict listing silently truncated") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun localChildLookupDoesNotEnumerateLargeDirectoryOrUnrelatedSymlinks(): Unit = runBlocking {
        val f = Fixture()
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(f.scratch, "entry-$it").createNewFile() }
        val wanted = File(f.scratch, "zz-wanted.txt").apply { writeText("wanted") }
        Files.createSymbolicLink(File(f.scratch, "unrelated-link").toPath(), temporary.newFolder().toPath())
        val found = try { f.access.findChild(f.access.resolve("/scratch"), wanted.name) }
        catch (failure: IllegalArgumentException) {
            throw AssertionError("A direct local child lookup must not list unrelated entries", failure)
        }
        assertEquals(wanted.canonicalPath, found?.reference)
        assertNull(f.access.findChild(f.access.resolve("/scratch"), "missing.txt"))
    }

    @Test fun sharedListingScansVolumeTopologyOnceAndStillChecksLiveAccess(): Unit = runBlocking {
        val f = Fixture()
        repeat(25) { File(f.primary, "entry-$it").writeText("data") }
        val directory = f.access.resolve(f.primary.path)
        f.volumeLookups = 0
        assertEquals(25, f.access.list(directory).size)
        assertTrue("Volume scans must be bounded per operation; actual=${f.volumeLookups}", f.volumeLookups <= 2)
        f.enabled = false
        try { f.access.list(directory); fail("Disabled shared mode retained access") }
        catch (_: IllegalArgumentException) { }
        f.enabled = true
        f.granted = false
        try { f.access.findChild(directory, "entry-0"); fail("Revoked Android grant retained access") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun sameVolumeLargeSparseMoveDoesNotCopyThroughScratch(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.primary, "large.bin")
        val destination = File(f.primary, "moved.bin")
        RandomAccessFile(source, "rw").use { it.setLength(LocalFileAccess.MAX_COPY_BYTES + 1); it.write(73) }
        val originalKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val result = try { f.access.move(f.access.resolve(source.path), f.access.resolve(destination.path, true)) }
        catch (failure: IllegalArgumentException) {
            throw AssertionError("A same-volume rename must not hit the bounded copy limit", failure)
        }
        assertEquals(LocalFileAccess.MAX_COPY_BYTES + 1, result)
        assertFalse(source.exists())
        assertEquals(originalKey, Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(LocalFileAccess.MAX_COPY_BYTES + 1, destination.length())
        assertEquals(73, destination.inputStream().use { it.read() })
        assertTrue("A rename leaves scratch empty", f.scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun movePathPreservesTheInodeAndNeverReplacesAnExistingTargetByDefault(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.scratch, "source.txt").apply { writeText("original") }
        val destination = File(f.scratch, "destination.txt")
        val sourceKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val moved = f.access.movePath(f.access.resolve("source.txt"), "destination.txt")
        assertEquals(8L, moved.second)
        assertFalse(source.exists())
        assertEquals(sourceKey, Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey())
        val blocked = File(f.scratch, "blocked.txt").apply { writeText("keep") }
        try { f.access.movePath(moved.first, "blocked.txt"); fail("Existing target replaced") }
        catch (_: IllegalArgumentException) { }
        assertEquals("original", destination.readText())
        assertEquals("keep", blocked.readText())
    }

    @Test fun oversizedTreeCopyAndDeleteFailBeforeAnyMutation(): Unit = runBlocking {
        val f = Fixture()
        val tree = File(f.scratch, "tree").apply { mkdir() }
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(tree, "entry-$it").createNewFile() }
        val source = f.access.resolve("tree")
        try { f.access.copy(source, f.access.resolve("copy", true)); fail("Incomplete copy reported success") }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(f.scratch, "copy").exists())
        try { f.access.delete(source, recursive = true); fail("Incomplete deletion reported success") }
        catch (_: IllegalArgumentException) { }
        assertEquals(LocalFileAccess.MAX_ENTRIES + 1, tree.listFiles()!!.size)
    }

    @Test fun boundedListingSignalsTruncationOnceAndLeavesExactLimitsComplete(): Unit = runBlocking {
        val f = Fixture()
        repeat(3) { File(f.scratch, "entry-$it").createNewFile() }
        val root = f.access.resolve("/scratch")
        var notices = 0
        assertEquals(2, f.access.list(root, limit = 2, onTruncated = { notices++ }).size)
        assertEquals(1, notices)
        notices = 0
        assertEquals(3, f.access.list(root, limit = 3, onTruncated = { notices++ }).size)
        assertEquals(0, notices)
        assertTrue(f.access.list(root, limit = 0, onTruncated = { notices++ }).isEmpty())
        assertEquals(1, notices)
        try { f.access.list(root, limit = 2); fail("A strict custom limit silently truncated") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun boundedWalkUsesOneGlobalBudgetAndKeepsTheStrictDefault(): Unit = runBlocking {
        val f = Fixture()
        val tree = File(f.scratch, "tree").apply { mkdir() }
        repeat(2) { directory ->
            val child = File(tree, "dir-$directory").apply { mkdir() }
            repeat(3) { File(child, "entry-$it").createNewFile() }
        }
        val root = f.access.resolve("tree")
        var notices = 0
        assertEquals(3, f.access.walk(root, limit = 3, onTruncated = { notices++ }).size)
        assertEquals(1, notices)
        notices = 0
        assertEquals(8, f.access.walk(root, limit = 8, onTruncated = { notices++ }).size)
        assertEquals(0, notices)
        try { f.access.walk(root, limit = 3); fail("Strict traversal silently truncated") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun topologySnapshotDoesNotHideRevocationDuringDirectoryEnumeration(): Unit = runBlocking {
        val primary = temporary.newFolder()
        repeat(5) { File(primary, "entry-$it").createNewFile() }
        var enabledChecks = 0
        var scans = 0
        val policy = SharedStorageAccess({ ++enabledChecks < 4 }, { true }, { scans++; mapOf("primary" to primary) })
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess = policy)
        try {
            access.list(LocalFileSource.Local(primary), onTruncated = {})
            fail("Revocation during a read returned a partial result")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, scans)
        assertTrue(enabledChecks >= 4)
    }

    @Test fun topologySnapshotExpiresBeforeTheNextOperation(): Unit = runBlocking {
        val primary = temporary.newFolder()
        File(primary, "entry").createNewFile()
        var roots: Map<String, File> = mapOf("primary" to primary)
        val policy = SharedStorageAccess({ true }, { true }, { roots })
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess = policy)
        val root = LocalFileSource.Local(primary)
        assertEquals(1, access.list(root).size)
        roots = emptyMap()
        try { access.list(root); fail("Unmounted volume survived the previous operation snapshot") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun directChildLookupStillRejectsTheRequestedPrivateTreeAndSymlink(): Unit = runBlocking {
        val f = Fixture()
        val android = File(f.primary, "Android").apply { mkdir() }
        File(android, "data").mkdir()
        val parent = f.access.resolve(android.path)
        try { f.access.findChild(parent, "data"); fail("Private child exposed by direct lookup") }
        catch (_: IllegalArgumentException) { }
        val secret = temporary.newFile().apply { writeText("private") }
        Files.createSymbolicLink(File(f.primary, "link").toPath(), secret.toPath())
        try { f.access.findChild(f.access.resolve(f.primary.path), "link"); fail("Requested symlink followed") }
        catch (_: IllegalArgumentException) { }
        try { f.access.findChild(parent, "../data"); fail("Invalid child name accepted") }
        catch (_: IllegalArgumentException) { }
    }


    @Test fun sameVolumeDirectoryMovePreservesInodesWithASparseChildAboveTheCopyLimit(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.primary, "large-tree").apply { mkdir() }
        val nested = File(source, "nested").apply { mkdir() }
        val child = File(nested, "large.bin")
        RandomAccessFile(child, "rw").use { it.setLength(LocalFileAccess.MAX_COPY_BYTES + 1); it.write(91) }
        val destination = File(f.primary, "moved-tree")
        val directoryKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val childKey = Files.readAttributes(child.toPath(), BasicFileAttributes::class.java).fileKey()
        val result = try { f.access.move(f.access.resolve(source.path), f.access.resolve(destination.path, true)) }
        catch (failure: IllegalArgumentException) {
            throw AssertionError("A same-volume directory rename must not copy its sparse descendants", failure)
        }
        assertEquals(LocalFileAccess.MAX_COPY_BYTES + 1, result)
        assertFalse(source.exists())
        assertEquals(directoryKey, Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey())
        val movedChild = File(destination, "nested/large.bin")
        assertEquals(childKey, Files.readAttributes(movedChild.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(LocalFileAccess.MAX_COPY_BYTES + 1, movedChild.length())
        assertEquals(91, movedChild.inputStream().use { it.read() })
        assertTrue(f.scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun directoryMovePreflightRejectsSymlinkDescendantsBeforeRenamingAnything(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.primary, "tree").apply { mkdir() }
        File(source, "ordinary.txt").writeText("keep")
        val outside = temporary.newFolder()
        val secret = File(outside, "secret.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(source, "nested-link").toPath(), outside.toPath())
        val destination = File(f.primary, "destination")
        try {
            f.access.move(f.access.resolve(source.path), f.access.resolve(destination.path, true))
            fail("A directory containing a forbidden symlink was renamed")
        } catch (_: IllegalArgumentException) { }
        assertTrue(source.isDirectory)
        assertEquals("keep", File(source, "ordinary.txt").readText())
        assertEquals("private", secret.readText())
        assertFalse("Preflight must finish before destination publication", destination.exists())
    }

    @Test fun directoryMovePreflightRejectsRestrictedAndroidDescendantsBeforeRenamingAnything(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.primary, "Android").apply { mkdir() }
        val data = File(source, "data").apply { mkdir() }
        val secret = File(data, "secret.txt").apply { writeText("private") }
        val destination = File(f.primary, "moved-android")
        try {
            f.access.move(f.access.resolve(source.path), f.access.resolve(destination.path, true))
            fail("A directory containing restricted Android/data was renamed")
        } catch (_: IllegalArgumentException) { }
        assertTrue(source.isDirectory)
        assertEquals("private", secret.readText())
        assertFalse("Restricted descendants must fail before destination publication", destination.exists())
    }


    @Test fun oversizedDirectoryMoveFailsBeforeRenamingAnIncompleteTree(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.scratch, "tree").apply { mkdir() }
        repeat(LocalFileAccess.MAX_ENTRIES + 1) { File(source, "entry-$it").createNewFile() }
        val destination = File(f.scratch, "destination")
        try {
            f.access.move(f.access.resolve("tree"), f.access.resolve("destination", true))
            fail("A rename bypassed the strict tree limit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(LocalFileAccess.MAX_ENTRIES + 1, source.listFiles()!!.size)
        assertFalse(destination.exists())
    }


    @Test fun directoryMoveRejectsRestrictedMappedDestinationBeforeRename(): Unit = runBlocking {
        val f = Fixture()
        val source = File(f.primary, "ordinary-tree").apply { mkdir() }
        val data = File(source, "data").apply { mkdir() }
        val secret = File(data, "secret.txt").apply { writeText("keep") }
        val destination = File(f.primary, "Android")
        try {
            f.access.move(f.access.resolve(source.path), f.access.resolve(destination.path, true))
            fail("A directory rename created restricted Android/data at its mapped destination")
        } catch (_: IllegalArgumentException) { }
        assertTrue(source.isDirectory)
        assertEquals("keep", secret.readText())
        assertFalse("Mapped destination policy must pass before rename", destination.exists())
    }


    @Test fun readOperationRechecksBothLiveCapabilitiesAfterBuildingTheResult(): Unit = runBlocking {
        for (revokeAndroidGrant in listOf(false, true)) {
            val f = Fixture()
            val file = File(f.primary, "entry.txt").apply { writeText("keep") }
            try {
                f.access.withReadOperation {
                    val entries = f.access.list(f.access.resolve(f.primary.path))
                    assertEquals(1, entries.size)
                    val result = f.access.name(entries.single())
                    if (revokeAndroidGrant) f.granted = false else f.enabled = false
                    result
                }
                fail("A completed read result escaped final live capability validation")
            } catch (_: IllegalArgumentException) { }
            assertEquals("keep", file.readText())
            f.granted = true
            f.enabled = true
            assertEquals(1, f.access.withReadOperation { f.access.list(f.access.resolve(f.primary.path)) }.size)
        }
    }

}
