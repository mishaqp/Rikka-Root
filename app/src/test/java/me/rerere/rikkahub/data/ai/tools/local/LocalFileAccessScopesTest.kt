package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentResolver
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Local scopes must retain their resolver even when Android SAF services are unavailable. */
class LocalFileAccessScopesTest {
    @get:Rule val temporary = TemporaryFolder()

    private class NoSafContext : ContextWrapper(null) {
        var lookups = 0
        override fun getContentResolver(): ContentResolver { lookups++; error("Local resolution must not query SAF grants") }
        override fun getSystemService(name: String): Any? { lookups++; error("Local resolution must not query Android volumes") }
    }

    @Test fun virtualRelativeAndPhysicalLocalScopesNeverAccessAndroidSaf(): Unit = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val upload = temporary.newFolder("upload")
        val workspace = temporary.newFolder("selected-workspace")
        val android = NoSafContext()
        val local = LocalFileAccess(scratch, upload, context = android)
        val selected = LocalFileAccess(scratch, upload, workspaceCwd = "/workspace/project", context = android,
            resolveWorkspacePath = { File(workspace, it.removePrefix("/workspace").trimStart('/')) })
        val paths = listOf("note.txt" to File(scratch, "note.txt"), "~/note.txt" to File(scratch, "note.txt"),
            "/scratch/note.txt" to File(scratch, "note.txt"), "/upload/photo.jpg" to File(upload, "photo.jpg"),
            File(scratch, "physical.txt").path to File(scratch, "physical.txt"),
            File(upload, "physical.jpg").toURI().toString() to File(upload, "physical.jpg"))
        paths.forEach { (path, target) ->
            assertEquals(target.canonicalFile, (local.resolve(path, write = true) as LocalFileSource.Local).file)
        }
        listOf("note.txt" to File(workspace, "project/note.txt"), "/workspace/note.txt" to File(workspace, "note.txt"),
            File(workspace, "physical.txt").path to File(workspace, "physical.txt")).forEach { (path, target) ->
            assertEquals(target.canonicalFile, (selected.resolve(path, write = true) as LocalFileSource.Local).file)
        }
        assertEquals(workspace, (selected.resolve("/workspace") as LocalFileSource.Local).file)
        assertEquals(scratch, (local.resolve("/scratch") as LocalFileSource.Local).file)
        assertEquals(upload, (local.resolve("/upload") as LocalFileSource.Local).file)
        assertEquals("Neither ContentResolver nor StorageManager participates in local paths", 0, android.lookups)
    }

    @Test fun privatePathsAndWorkspaceSymlinksKeepTheirOriginalGuardWithoutSaf(): Unit = runBlocking {
        val android = NoSafContext()
        val workspace = temporary.newFolder("workspace")
        val privateDirectory = temporary.newFolder("private")
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"), context = android,
            resolveWorkspacePath = { File(workspace, it.removePrefix("/workspace").trimStart('/')) })
        for (path in listOf(File(privateDirectory, "credentials.enc").path, "/system/build.prop")) {
            try { access.resolve(path, write = true); fail("Private path reached") }
            catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("Путь вне")) }
        }
        Files.createSymbolicLink(File(workspace, "escape").toPath(), privateDirectory.toPath())
        for (path in listOf("/workspace/escape/credentials.enc", File(workspace, "escape/credentials.enc").path)) {
            try { access.resolve(path, write = true); fail("Workspace symlink reached") }
            catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("Символические")) }
        }
        assertEquals("Rejected local paths must not query SAF as a fallback", 0, android.lookups)
    }
}
