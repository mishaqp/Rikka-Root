package me.rerere.rikkahub.miniapps

// Behavioral reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_store/archive_test.dart.
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MiniAppStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(manifest: String = """{"id":"water","name":"Water"}"""): File = temp.newFolder().apply {
        resolve("moru-app.json").writeText(manifest)
        resolve("index.html").writeText("<head><title>Water</title></head>")
    }
    @Test fun installUpdateReloadAndConcurrentStorage() = runBlocking {
        val root = temp.newFolder()
        val store = MiniAppStore(root)
        val source = source()
        source.resolve("node_modules").mkdir()
        source.resolve("node_modules/skip").writeText("skip")
        val first = store.install(source)
        assertEquals(1, first.files)
        assertTrue(first.app.entryFile.readText().contains("<head><script src=\"moru.js\"></script>"))
        assertFalse(first.app.codeDirectory.resolve("node_modules").exists())
        (0..19).map { async { store.storageSet("water", "k$it", JsonPrimitive(it)) } }.awaitAll()
        assertEquals(20, store.storageKeys("water").size)
        source.resolve("index.html").writeText("v2")
        assertTrue(store.install(source).updated)
        assertEquals(JsonPrimitive(3), store.storageGet("water", "k3"))
        val reopened = MiniAppStore(root)
        reopened.load()
        assertEquals("Water", reopened.byId("water")?.name)
    }
    @Test fun invalidUpdateAndSymlinkPreserveInstallation() = runBlocking {
        val store = MiniAppStore(temp.newFolder())
        val src = source()
        val app = store.install(src).app
        val old = app.entryFile.readText()
        src.resolve("moru-app.json").writeText("""{"id":"water","name":"Water","entry":"../escape"}""")
        try { store.install(src); fail() } catch (e: MiniAppException) { assertEquals("invalid_path", e.code) }
        assertEquals(old, app.entryFile.readText())
        src.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
        Files.createSymbolicLink(src.resolve("leak").toPath(), temp.newFile().toPath())
        try { store.install(src); fail() } catch (_: MiniAppException) { }
        assertEquals(old, app.entryFile.readText())
    }
    @Test fun archiveRestoresOnlyEmptyDataAndExcludesReminders() = runBlocking {
        val store = MiniAppStore(temp.newFolder())
        store.install(source())
        store.storageSet("water", "log", JsonPrimitive(250))
        val archive = store.exportArchive("water", true, temp.newFolder())
        val other = MiniAppStore(temp.newFolder())
        assertTrue(other.importArchive(archive).dataRestored)
        assertEquals(JsonPrimitive(250), other.storageGet("water", "log"))
        other.storageSet("water", "log", JsonPrimitive(1))
        assertFalse(other.importArchive(archive).dataRestored)
        assertEquals(JsonPrimitive(1), other.storageGet("water", "log"))
        assertTrue(other.readReminders("water").isEmpty())
    }
    @Test fun rejectsTraversalAndInflatedArchiveAndCleansStaging() = runBlocking {
        val root = temp.newFolder()
        val store = MiniAppStore(root)
        for (name in listOf("../escape", "index.html")) {
            val zip = temp.newFile()
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("moru-app.json")); out.write("""{"id":"evil","name":"Evil"}""".toByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry(name))
                if (name == "index.html") repeat(21 * 1024) { out.write(ByteArray(1024)) } else out.write(1)
                out.closeEntry()
            }
            try { store.importArchive(zip); fail() } catch (_: MiniAppException) { }
            assertNull(store.byId("evil"))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun validatesManifestAndStorageLimitsWithoutLosingPreviousData() = runBlocking {
        val store = MiniAppStore(temp.newFolder())
        store.install(source())
        store.storageSet("water", "safe", JsonPrimitive(1))
        try { store.storageSet("water", "huge", JsonPrimitive("x".repeat(MiniAppStore.maxDataBytes))); fail() }
        catch (e: MiniAppException) { assertEquals("storage_full", e.code) }
        assertEquals(listOf("safe"), store.storageKeys("water"))
        store.storageSet("water", "later", JsonPrimitive(2))
        for ((manifest, expected) in listOf(
            """{"id":"Bad","name":"A"}""" to "invalid_id",
            """{"id":"a","name":""}""" to "invalid_name",
            """{"id":"a","name":"A","network":["https://example.com"]}""" to "invalid_network",
            """{"id":"a","name":"A","permissions":["root"]}""" to "invalid_permissions"
        )) {
            try { store.install(source(manifest)); fail() }
            catch (e: MiniAppException) { assertEquals(expected, e.code) }
        }
        val crowded = source()
        repeat(MiniAppStore.maxFiles) { crowded.resolve("f$it").writeText("x") }
        try { store.install(crowded); fail() }
        catch (e: MiniAppException) { assertEquals("too_many_files", e.code) }
        assertEquals(JsonPrimitive(2), store.storageGet("water", "later"))
    }
    @Test fun deleteCallsHooksAndRemovesPrivateState() = runBlocking {
        val store = MiniAppStore(temp.newFolder())
        val app = store.install(source()).app
        var called = false
        store.addDeleteHook { id ->
            assertEquals("water", id)
            assertTrue(store.storageKeys(id).isEmpty())
            called = true
        }
        store.delete("water")
        assertTrue(called)
        assertNull(store.byId("water"))
        assertFalse(app.directory.exists())
    }
    @Test fun exactCodeLimitRoundTripsWithoutGeneratedBridgeGrowth() = runBlocking {
        val source = source()
        val original = "<head></head>" + "x".repeat(MiniAppStore.maxBytes - "<head></head>".length)
        source.resolve("index.html").writeText(original)
        var store = MiniAppStore(temp.newFolder())
        var installed = store.install(source).app
        assertEquals(MiniAppStore.maxBytes.toLong(), source.resolve("index.html").length())
        val installedLength = installed.entryFile.length()
        assertTrue(installedLength > MiniAppStore.maxBytes)
        repeat(2) {
            val archive = store.exportArchive("water", false, temp.newFolder())
            java.util.zip.ZipFile(archive).use { zip ->
                val entry = zip.getEntry("index.html")
                assertEquals(MiniAppStore.maxBytes.toLong(), entry.size)
                zip.getInputStream(entry).use { assertEquals(original, it.bufferedReader().readText()) }
            }
            store = MiniAppStore(temp.newFolder())
            installed = store.importArchive(archive).app
            assertEquals(installedLength, installed.entryFile.length())
        }
    }
    @Test fun exportKeepsAuthoredBridgeReferencesAndNestedEntryPaths() = runBlocking {
        val source = source("""{"id":"water","name":"Water","entry":"pages/main.html"}""")
        source.resolve("index.html").delete()
        source.resolve("pages").mkdir()
        val authored = "<head><script src=\"../moru.js\"></script></head>"
        source.resolve("pages/main.html").writeText(authored)
        val store = MiniAppStore(temp.newFolder())
        val app = store.install(source).app
        assertEquals(authored, app.entryFile.readText())
        val archive = store.exportArchive("water", false, temp.newFolder())
        java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("pages/main.html")).use { assertEquals(authored, it.bufferedReader().readText()) }
        }
        val generated = MiniAppStore.withBridgeScript("<head></head>", "pages/main.html")
        assertTrue(generated.contains("src=\"../moru.js\""))
        assertEquals(generated, MiniAppStore.withBridgeScript(generated, "pages/main.html"))
    }
    @Test fun absentStorageAndKeyLengthBoundaries() = runBlocking {
        val store = MiniAppStore(temp.newFolder())
        store.install(source())
        assertEquals(kotlinx.serialization.json.JsonNull, store.storageGet("water", "absent"))
        val key = "k".repeat(MiniAppStore.maxKeyLength)
        store.storageSet("water", key, JsonPrimitive(1))
        assertEquals(JsonPrimitive(1), store.storageGet("water", key))
        for (invalid in listOf("", key + "k")) {
            try { store.storageSet("water", invalid, JsonPrimitive(2)); fail() }
            catch (e: MiniAppException) { assertEquals("invalid_key", e.code) }
        }
        assertEquals(listOf(key), store.storageKeys("water"))
    }
    @Test fun linksAndHostScopes() {
        assertEquals("water", MiniAppStore.idFromLink("RIKKAHUB://app/water/"))
        assertNull(MiniAppStore.idFromLink("rikkahub://app/../escape"))
        val app = MiniApp("a", "A", File("."), network = listOf("*.example.com"))
        assertTrue(MiniAppStore.allowsHost(app, "example.com"))
        assertFalse(MiniAppStore.allowsHost(app, "evil-example.com"))
    }
}
