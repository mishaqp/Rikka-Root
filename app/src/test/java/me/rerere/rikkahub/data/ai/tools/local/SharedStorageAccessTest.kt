package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class SharedStorageAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val primary = temporary.newFolder()
        val card = temporary.newFolder()
        val alias = File(temporary.newFolder(), "sdcard").also { Files.createSymbolicLink(it.toPath(), primary.toPath()) }
        var enabled = true
        var granted = true
        var roots: Map<String, File> = mapOf("primary" to primary, "abcd-1234" to card)
        var beforeVolumeLookup: () -> Unit = {}
        val policy = SharedStorageAccess({ enabled }, { granted }, { beforeVolumeLookup(); roots }, listOf(alias))
        val access = LocalFileAccess(temporary.newFolder(), temporary.newFolder(), sharedStorageAccess = policy)
    }

    @Test fun directReadsAndWritesRequireBothIndependentLiveSwitches(): Unit = runBlocking {
        val f = Fixture()
        val target = File(f.primary, "Download/Rikka/test.txt")
        val source = f.access.resolve(target.path, true)
        f.access.write(source, "проверка".toByteArray())
        assertEquals("проверка", f.access.openInput(source).use { it.reader().readText() })
        f.enabled = false
        assertThrows(IllegalArgumentException::class.java) { f.access.checkFile(target) }
        try { f.access.openInput(source); fail("A retained source bypassed the disabled switch") } catch (_: IllegalArgumentException) { }
        f.enabled = true; f.granted = false
        assertThrows(IllegalArgumentException::class.java) { f.access.checkFile(target, true) }
        try { f.access.openOutput(source); fail("A retained source bypassed revoked OS access") } catch (_: IllegalArgumentException) { }
        assertEquals("проверка", target.readText())
        f.granted = true
        assertEquals(target.canonicalFile, f.access.checkFile(target))
    }

    @Test fun failureCleanupCannotDeleteASharedPathReplacedByAnotherApplication(): Unit = runBlocking {
        val f = Fixture()
        val root = f.access.resolve(f.primary.path)
        val target = f.access.createFile(root, "pending.txt") as LocalFileSource.Local
        target.file.delete()
        target.file.writeText("another app's data")
        try {
            f.access.cleanupOwnedTarget(target, java.io.IOException("interrupted"))
            fail("Unverifiable shared cleanup must report the path for manual review")
        } catch (partial: FileCreationCleanupFailedException) {
            assertEquals(target.reference, partial.created.reference)
            assertTrue(partial.sharedPathMayBePartial)
        }
        assertEquals("another app's data", target.file.readText())
    }

    @Test fun sharedFailureCleanupRemainsConservativeAfterPermissionRevocation(): Unit = runBlocking {
        val f = Fixture()
        val target = File(f.primary, "already-published.txt").apply { writeText("keep") }
        val source = f.access.resolve(target.path)
        f.enabled = false; f.granted = false
        try { f.access.cleanupOwnedTarget(source, java.io.IOException("interrupted")); fail("Revocation lost shared provenance") }
        catch (partial: FileCreationCleanupFailedException) { assertTrue(partial.sharedPathMayBePartial) }
        assertEquals("keep", target.readText())
    }

    @Test fun alreadyOpenSharedInputStopsReadingImmediatelyAfterTheAppSwitchIsDisabled(): Unit = runBlocking {
        val f = Fixture()
        val file = File(f.primary, "read.txt").apply { writeText("must stop") }
        val source = f.access.resolve(file.path)
        f.access.openInput(source).use { input ->
            f.enabled = false
            assertThrows(IllegalArgumentException::class.java) { input.read(ByteArray(64)) }
        }
    }

    @Test fun alreadyOpenSharedOutputStopsWritingImmediatelyAfterAndroidAccessIsRevoked(): Unit = runBlocking {
        val f = Fixture()
        val file = File(f.primary, "write.txt")
        val source = f.access.resolve(file.path, true)
        f.access.openOutput(source).use { output ->
            f.granted = false
            assertThrows(IllegalArgumentException::class.java) { output.write("must not write".toByteArray()) }
        }
        assertEquals(0L, file.length())
    }

    @Test fun defaultBatchCopyDoesNotOverwriteAFileReplacingItsPublishedReservation(): Unit = runBlocking {
        val f = Fixture()
        f.access.write(f.access.resolve("copy.txt", true), "our data".toByteArray())
        val target = File(f.primary, "copy.txt")
        var replaced = false
        f.beforeVolumeLookup = {
            if (!replaced && target.isFile && target.length() == 0L) {
                replaced = true
                target.delete(); target.writeText("another app's data")
            }
        }
        val output = Json.parseToJsonElement((fileManagerTools(f.access).single { it.name == "batch_copy" }.execute(buildJsonObject {
            put("paths", buildJsonArray { add("/scratch/copy.txt") }); put("dst_dir", f.primary.path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertTrue("The test must replace the actually published destination", replaced)
        assertEquals("another app's data", target.readText())
        assertEquals("A raced copy must not claim success", 0, output["success"]!!.jsonPrimitive.int)
    }

    @Test fun defaultUnzipDoesNotOverwriteAFileReplacingItsPublishedReservation(): Unit = runBlocking {
        val f = Fixture()
        val bytes = ByteArrayOutputStream().also { stream -> ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry("unzip.txt")); zip.write("our data".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        f.access.write(f.access.resolve("race.zip", true), bytes)
        val target = File(f.primary, "unzip.txt")
        var replaced = false
        f.beforeVolumeLookup = {
            if (!replaced && target.isFile && target.length() == 0L) {
                replaced = true
                target.delete(); target.writeText("another app's data")
            }
        }
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val output = Json.parseToJsonElement((unzipFileTool(context, f.access).execute(buildJsonObject {
            put("source", "/scratch/race.zip"); put("destination_dir", f.primary.path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(replaced)
        assertEquals("another app's data", target.readText())
        assertNotNull("A raced extraction must not claim success", output["error"])
    }

    @Test fun downloadDoesNotOverwriteAFileReplacingItsPublishedReservation(): Unit = runBlocking {
        val f = Fixture()
        val target = File(f.primary, "download.txt")
        var replaced = false
        f.beforeVolumeLookup = {
            if (!replaced && target.isFile && target.length() == 0L) {
                replaced = true
                target.delete(); target.writeText("another app's data")
            }
        }
        val cache = temporary.newFolder()
        val context = object : ContextWrapper(null) { override fun getCacheDir(): File = cache }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "our data".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val output = Json.parseToJsonElement((downloadTool(context, f.access).execute(buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/file")
                put("filename", target.name); put("directory", f.primary.path)
            }).single() as UIMessagePart.Text).text).jsonObject
            assertTrue(replaced)
            assertEquals("another app's data", target.readText())
            assertNotNull("A raced download must not claim success", output["error"])
        } finally { server.stop(0) }
    }

    @Test fun sharedStagingCannotBeReplacedWithASymlinkToExportPrivateData(): Unit = runBlocking {
        val f = Fixture()
        val secret = temporary.newFile().apply { writeText("private application credentials") }
        val target = File(f.primary, "public-note.txt")
        var attacked = false
        f.beforeVolumeLookup = {
            if (!attacked) {
                val stage = f.primary.listFiles()?.firstOrNull { it.name.startsWith(".rk-write-") && it.isFile && it.length() > 0 }
                if (stage != null) {
                    attacked = true
                    stage.delete(); Files.createSymbolicLink(stage.toPath(), secret.toPath())
                }
            }
        }
        try {
            f.access.write(f.access.resolve(target.path, true), "public note".toByteArray())
        } catch (_: Exception) { /* Refusing the altered stage is a safe outcome too. */ }
        assertEquals("private application credentials", secret.readText())
        assertFalse("Private bytes escaped through the replaced shared stage (attack injected=$attacked)",
            target.isFile && target.readText() == secret.readText())
    }

    @Test fun sharedOverwriteAndAppendKeepCompleteDataAndConsumeTheirSameVolumeStages(): Unit = runBlocking {
        val f = Fixture()
        val file = f.access.resolve(File(f.primary, "replace.txt").path, true)
        f.access.write(file, "first".toByteArray())
        f.access.write(file, "replacement".toByteArray(), overwrite = true)
        f.access.write(file, "+append".toByteArray(), append = true)
        assertEquals("replacement+append", f.access.openInput(file).use { it.reader().readText() })
        assertTrue(f.primary.listFiles().orEmpty().none { it.name.startsWith(".rk-write-") })
    }

    @Test fun batchDirectoryCopyAndMovePreserveNestedFilesWithDefaultNonOverwrite(): Unit = runBlocking {
        for (operation in listOf("copy", "move")) {
            val f = Fixture()
            val directory = f.access.resolve("tree/nested", true)
            f.access.createDirectory(directory)
            f.access.write(f.access.resolve("tree/nested/note.txt", true), "nested data".toByteArray())
            val output = Json.parseToJsonElement((fileManagerTools(f.access).single { it.name == "batch_$operation" }.execute(buildJsonObject {
                put("paths", buildJsonArray { add("/scratch/tree") }); put("dst_dir", f.primary.path)
            }).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(output.toString(), 1, output["success"]!!.jsonPrimitive.int)
            assertEquals("nested data", File(f.primary, "tree/nested/note.txt").readText())
            assertEquals(operation == "copy", f.access.exists(f.access.resolve("tree")))
        }
    }

    @Test fun systemPrivateSiblingVolumesAndLexicalTraversalAreRejectedEvenWhenEnabled(): Unit = runBlocking {
        val f = Fixture()
        val blocked = listOf("/data/local/tmp/secret", "/system/build.prop", "/proc/self/environ", "/storage/emulated/10/file",
            File(f.primary.parentFile, f.primary.name + "-other/secret").path,
            "${f.primary.path}/Download/../Documents/file", "${f.alias.path}/Download/../../file", "${f.primary.path}/Download\\..\\secret")
        blocked.forEach { path ->
            try { f.access.resolve(path, true); fail("Allowed $path") } catch (_: IllegalArgumentException) { }
            assertThrows(path, IllegalArgumentException::class.java) { f.access.checkFile(File(path), true) }
        }
        assertThrows(IllegalArgumentException::class.java) { f.access.checkFile(f.primary, true) }
        assertEquals(f.primary.canonicalFile, f.access.checkFile(f.primary))
    }

    @Test fun androidPrivateTreesAreBlockedThroughEveryAliasAndCapitalization(): Unit = runBlocking {
        val f = Fixture()
        for (root in listOf(f.primary, f.alias, f.card)) for (privatePath in listOf("Android/data/other.package/file", "Android/obb/other.package/file", "android/DATA/other.package/file", "ANDROID/ObB/file")) {
            val path = File(root, privatePath)
            try { f.access.resolve(path.path, true); fail("Allowed private path $path") } catch (_: IllegalArgumentException) { }
            assertThrows(path.path, IllegalArgumentException::class.java) { f.access.checkFile(path, true) }
        }
        val safe = File(f.primary, "Android/media/own.package/photo.jpg")
        assertEquals(safe.canonicalFile, (f.access.resolve(safe.path, true) as LocalFileSource.Local).file)
    }

    @Test fun trustedPrimaryAliasWorksButAllDescendantSymlinkEscapesStayBlocked(): Unit = runBlocking {
        val f = Fixture()
        val outside = temporary.newFolder()
        val documents = File(f.primary, "Documents").apply { mkdir() }
        Files.createSymbolicLink(File(f.primary, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(f.primary, "cross-volume").toPath(), f.card.toPath())
        Files.createSymbolicLink(File(f.primary, "same-volume").toPath(), documents.toPath())
        Files.createSymbolicLink(File(f.primary, "private").toPath(), File(f.primary, "Android/data").toPath())
        assertEquals(File(f.primary, "Download/a.txt").canonicalFile,
            (f.access.resolve(File(f.alias, "Download/a.txt").path, true) as LocalFileSource.Local).file)
        for (name in listOf("escape", "cross-volume", "same-volume", "private")) for (root in listOf(f.primary, f.alias)) {
            try { f.access.resolve(File(root, "$name/file.txt").path, true); fail("Allowed symlink $root/$name") } catch (_: IllegalArgumentException) { }
        }
        val fakeAlias = File(temporary.newFolder(), "sdcard").also { Files.createSymbolicLink(it.toPath(), outside.toPath()) }
        val policy = SharedStorageAccess({ true }, { true }, { f.roots }, listOf(fakeAlias))
        assertNull("An alias is trusted only when its canonical target is the real primary volume", policy.resolve(File(fakeAlias, "secret").path))
    }

    @Test fun removableCardUnmountAndRootModificationAreCheckedAgainAtOperationTime(): Unit = runBlocking {
        val f = Fixture()
        val path = File(f.card, "Documents/card.txt")
        val source = f.access.resolve(path.path, true)
        f.access.write(source, "карта".toByteArray())
        assertEquals("карта", path.readText())
        f.roots = mapOf("primary" to f.primary)
        try { f.access.openInput(source); fail("Unmounted volume retained permission") } catch (_: IllegalArgumentException) { }
        assertThrows(IllegalArgumentException::class.java) { f.access.checkFile(f.card, true) }
    }

    @Test fun disabledOrDeniedPolicyDoesNotQueryVolumesOrEnableAnyRawFileAccess() {
        var lookups = 0
        val unavailable = { lookups++; error("Should not query Android volumes") }
        val disabled = SharedStorageAccess({ false }, { true }, unavailable)
        val denied = SharedStorageAccess({ true }, { false }, unavailable)
        assertNull(disabled.resolve("/sdcard/Download/x"))
        assertNull(denied.resolve("/sdcard/Download/x"))
        assertEquals(0, lookups)
    }

    @Test fun sharedModeUsesTheSameReadWriteCopyMoveFindAndBatchTools(): Unit = runBlocking {
        val f = Fixture()
        val dir = File(f.primary, "Download/Rikka").apply { mkdirs() }
        val tools = fileManagerTools(f.access) + writeTextFileTool(f.access)
        suspend fun execute(name: String, obj: JsonObject): JsonObject = Json.parseToJsonElement(
            (tools.single { it.name == name }.execute(obj).single() as UIMessagePart.Text).text).jsonObject
        val first = File(dir, "first.txt")
        assertTrue(execute("write_text_file", buildJsonObject { put("path", first.path); put("content", "text") })["success"]?.jsonPrimitive?.boolean == true)
        val copied = File(f.card, "copy.txt")
        assertTrue(execute("copy_file", buildJsonObject { put("src", first.path); put("dst", copied.path) })["error"] == null)
        val moved = File(dir, "moved.txt")
        assertTrue(execute("move_file", buildJsonObject { put("src", copied.path); put("dst", moved.path) })["error"] == null)
        assertEquals("text", moved.readText())
        assertFalse(copied.exists())
        val found = execute("find_files", buildJsonObject { put("root", dir.path); put("query", "*.txt") })
        assertEquals(2, found["files"]!!.jsonArray.size)
        val bad = File(f.primary, "Android/data/other/private.txt")
        val batch = execute("batch_delete", buildJsonObject { put("paths", buildJsonArray { add(moved.path); add(bad.path) }) })
        assertEquals(1, batch["success"]!!.jsonPrimitive.int)
        assertEquals(1, batch["failed"]!!.jsonArray.size)
        assertTrue(first.exists())
        assertFalse(moved.exists())
    }

    @Test fun archivesRoundTripAcrossDirectSharedStorageAndScratchWithOriginalApproval(): Unit = runBlocking {
        val f = Fixture()
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val source = File(f.primary, "Download/Rikka/source.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("архив") }
        val archive = File(f.card, "backup.zip")
        val zip = zipFilesTool(context, f.access)
        val unzip = unzipFileTool(context, f.access)
        val list = listZipContentsTool(context, f.access)
        val zipInput = buildJsonObject { put("sources", buildJsonArray { add(source.path) }); put("destination", archive.path) }
        assertTrue(zip.needsApproval(zipInput))
        val zipped = Json.parseToJsonElement((zip.execute(zipInput).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(zipped.toString(), true, zipped["success"]?.jsonPrimitive?.boolean)
        assertTrue(archive.isFile)
        val listed = Json.parseToJsonElement((list.execute(buildJsonObject { put("source", archive.path) }).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(listed.toString(), listed["error"] == null)
        val destination = File(f.primary, "Download/Rikka/unpacked")
        val extracted = Json.parseToJsonElement((unzip.execute(buildJsonObject {
            put("source", archive.path); put("destination_dir", destination.path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(extracted.toString(), true, extracted["success"]?.jsonPrimitive?.boolean)
        assertEquals("архив", File(destination, source.name).readText())
    }

    @Test fun readOnlyListingsSkipPrivateAndroidTreesWithWarningWhileArchivePreflightRejectsThem(): Unit = runBlocking {
        val f = Fixture()
        File(f.primary, "Android/data/other.package").mkdirs()
        File(f.primary, "Android/obb/other.package").mkdirs()
        File(f.primary, "Android/media/photo.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("visible") }
        val tools = fileManagerTools(f.access)
        val listed = Json.parseToJsonElement((tools.single { it.name == "list_files" }.execute(buildJsonObject {
            put("path", File(f.primary, "Android").path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(1, listed["files"]!!.jsonArray.size)
        assertEquals(2, listed["skipped_restricted_count"]!!.jsonPrimitive.int)
        assertTrue(listed["warnings"].toString().contains("Android/data"))
        val found = Json.parseToJsonElement((tools.single { it.name == "find_files" }.execute(buildJsonObject {
            put("root", f.primary.path); put("query", "*.txt")
        }).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(1, found["files"]!!.jsonArray.size)
        assertEquals(2, found["skipped_restricted_count"]!!.jsonPrimitive.int)
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val destination = File(f.card, "must-not-exist.zip")
        val zipped = Json.parseToJsonElement((zipFilesTool(context, f.access).execute(buildJsonObject {
            put("sources", buildJsonArray { add(f.primary.path) }); put("destination", destination.path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertNotNull(zipped.toString(), zipped["error"])
        assertFalse("Private descendants must fail before creating the destination", destination.exists())
    }

    @Test fun privateArchiveExtractionIsRejectedBeforeAnySharedFileIsCreated(): Unit = runBlocking {
        val f = Fixture()
        val bytes = ByteArrayOutputStream().also { stream -> ZipOutputStream(stream).use { zip ->
            listOf("innocent.txt", "Android/data/other.package/forbidden.txt").forEach { path ->
                zip.putNextEntry(ZipEntry(path)); zip.write("data".toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val archive = f.access.resolve("private.zip", true)
        f.access.write(archive, bytes)
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val result = Json.parseToJsonElement((unzipFileTool(context, f.access).execute(buildJsonObject {
            put("source", "/scratch/private.zip"); put("destination_dir", f.primary.path)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertNotNull(result["error"])
        assertFalse("All shared target paths must be validated before the first publication", File(f.primary, "innocent.txt").exists())
        assertFalse(File(f.primary, "Android").exists())
    }

    @Test fun androidViewerGetsABoundedAppCacheCopyOnlyForApprovedSharedFiles(): Unit = runBlocking {
        val f = Fixture()
        val cache = temporary.newFolder()
        val context = object : ContextWrapper(null) { override fun getCacheDir(): File = cache }
        val original = File(f.primary, "note.txt").apply { writeText("view me") }
        val source = f.access.resolve(original.path) as LocalFileSource.Local
        val old = File(cache, "tool_file_viewer/old.txt").apply {
            requireNotNull(parentFile).mkdirs(); writeText("expired"); setLastModified(1)
        }
        val snapshot = localFileForAndroidViewer(context, f.access, source)
        assertTrue(snapshot.toPath().startsWith(cache.toPath()))
        assertEquals("view me", snapshot.readText())
        assertEquals("view me", original.readText())
        assertFalse(old.exists())
        val local = f.access.resolve("own.txt", true) as LocalFileSource.Local
        f.access.write(local, "local".toByteArray())
        assertEquals(local.file, localFileForAndroidViewer(context, f.access, local))
        f.enabled = false
        try { localFileForAndroidViewer(context, f.access, source); fail("Viewer bypassed a disabled setting") } catch (_: IllegalArgumentException) { }
        assertEquals(1, requireNotNull(snapshot.parentFile).listFiles()!!.size)
        f.enabled = true
        original.delete()
        Files.createSymbolicLink(original.toPath(), File(temporary.newFolder(), "secret").toPath())
        try { localFileForAndroidViewer(context, f.access, source); fail("Viewer followed a replaced source symlink") } catch (_: IllegalArgumentException) { }
    }

    @Test fun sharedViewerRejectsOversizedFilesBeforeCreatingACacheCopy(): Unit = runBlocking {
        val f = Fixture()
        val cache = temporary.newFolder()
        val context = object : ContextWrapper(null) { override fun getCacheDir(): File = cache }
        val original = File(f.primary, "large.bin")
        java.io.RandomAccessFile(original, "rw").use { it.setLength(MAX_SHARED_VIEWER_BYTES + 1) }
        try {
            localFileForAndroidViewer(context, f.access, f.access.resolve(original.path) as LocalFileSource.Local)
            fail("Unbounded viewer cache copy")
        } catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("128 МиБ")) }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun defaultAssistantFolderIsASoftHintAndDoesNotRewriteRelativeOrExplicitPaths(): Unit = runBlocking {
        val f = Fixture()
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val tools = fileManagerTools(f.access) + listOf(writeTextFileTool(f.access), downloadTool(context, f.access),
            zipFilesTool(context, f.access), unzipFileTool(context, f.access))
        listOf("write_text_file", "write_binary_file", "copy_file", "move_file", "zip_files", "unzip_file", "download_file").forEach { name ->
            val tool = tools.single { it.name == name }
            assertTrue(name, tool.description.contains("/sdcard/Download/Rikka"))
            assertTrue(name, tool.description.contains("путь имеет приоритет"))
            assertTrue(name, tool.needsApproval(buildJsonObject {}))
        }
        assertEquals("/scratch", f.access.defaultPath)
        val explicit = File(f.card, "Chosen/note.txt")
        assertEquals(explicit.canonicalFile, (f.access.resolve(explicit.path, true) as LocalFileSource.Local).file)
        val relative = f.access.resolve("note.txt", true) as LocalFileSource.Local
        assertFalse(relative.file.toPath().startsWith(f.primary.toPath()))
    }
}
