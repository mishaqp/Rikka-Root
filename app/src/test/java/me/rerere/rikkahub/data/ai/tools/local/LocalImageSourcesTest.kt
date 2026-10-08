package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalImageSourcesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun workspaceImageUsesTheSamePhysicalMountAsProot() = runBlocking {
        val manager = WorkspaceManager(folder.newFolder("workspaces"))
        manager.ensureWorkspace("chat")
        val image = File(manager.filesDir("chat"), "pictures/обои 1.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
        }
        val sources = LocalImageSources(folder.newFolder("upload"), workspaceCwd = "pictures",
            resolveWorkspacePath = { manager.resolveRootfsFile("chat", it) })
        listOf("/workspace/pictures/обои 1.png", "/workspace/pictures/обои%201.png".let { "file://$it" }, "обои 1.png")
            .forEach { assertEquals(image.canonicalFile, (sources.resolve(it) as LocalImageSource.LocalFile).file) }
    }

    @Test fun prootPathsOutsideWorkspaceAlsoUseTheExistingMapper() = runBlocking {
        val upload = folder.newFolder("upload")
        val manager = WorkspaceManager(folder.newFolder("workspaces"), bindMounts = listOf(WorkspaceBindMount(upload, "/upload")))
        manager.ensureWorkspace("chat")
        val image = File(manager.linuxDir("chat"), "tmp/generated.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val sources = LocalImageSources(upload, resolveWorkspacePath = { manager.resolveRootfsFile("chat", it) })
        assertEquals(image.canonicalFile, (sources.resolve("/tmp/generated.png") as LocalImageSource.LocalFile).file)
        val attached = File(upload, "photo.png").apply { writeBytes(byteArrayOf(2)) }
        assertEquals(attached.canonicalFile, (sources.resolve("/upload/photo.png") as LocalImageSource.LocalFile).file)
    }

    @Test fun plainProotPathNeverSelectsAnUnrelatedExistingHostFile() = runBlocking {
        val host = folder.newFile("host.png")
        val manager = WorkspaceManager(folder.newFolder("workspaces"))
        manager.ensureWorkspace("chat")
        val virtual = File(manager.linuxDir("chat"), host.path.trimStart('/')).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(3)) }
        val sources = LocalImageSources(folder.newFolder("upload"), resolveWorkspacePath = { manager.resolveRootfsFile("chat", it) })
        assertEquals(virtual.canonicalFile, (sources.resolve(host.path) as LocalImageSource.LocalFile).file)
        assertEquals(host.canonicalFile, (sources.resolve(host.toURI().toString()) as LocalImageSource.LocalFile).file)
    }

    @Test fun chatAttachmentAndContentUriAreSelectableWithoutStoragePermission() = runBlocking {
        val image = folder.newFile("user.jpg")
        val sources = LocalImageSources(folder.newFolder("upload"), chatImages = listOf(image.toURI().toString(), "content://photos/image/42"))
        assertEquals(image.canonicalFile, (sources.resolve("attachment:1") as LocalImageSource.LocalFile).file)
        assertEquals("content://photos/image/42", (sources.resolve("attachment:2") as LocalImageSource.ContentUri).uri)
        assertEquals("content://photos/image/42", (sources.resolve("content://photos/image/42") as LocalImageSource.ContentUri).uri)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { sources.resolve("attachment:3") } }
        Unit
    }

    @Test fun onlyImagesInTheLatestUserMessageWithImagesAreAttachments() {
        fun message(role: MessageRole, vararg urls: String) = UIMessage(role = role, parts = urls.map { UIMessagePart.Image(it) })
        val messages = listOf(message(MessageRole.USER, "file:///old.png"), message(MessageRole.USER, "file:///new.jpg", "content://photos/2"),
            message(MessageRole.ASSISTANT, "file:///generated.png"), UIMessage.user("Поставь вторую картинку"))
        assertEquals(listOf("file:///new.jpg", "content://photos/2"), wallpaperChatImages(messages))
    }

    @Test fun missingWorkspaceFileReportsBothRequestedAndMappedPath() = runBlocking {
        val manager = WorkspaceManager(folder.newFolder("workspaces"))
        manager.ensureWorkspace("chat")
        val sources = LocalImageSources(folder.newFolder("upload"), resolveWorkspacePath = { manager.resolveRootfsFile("chat", it) })
        val resolved = sources.resolve("/workspace/missing.png") as LocalImageSource.LocalFile
        val error = resolved.unavailableMessage()
        assertTrue(error.contains("/workspace/missing.png"))
        assertTrue(error.contains(File(manager.filesDir("chat"), "missing.png").path))
    }

    @Test fun traversalAndRemoteSourcesDoNotReachTheImageReader() = runBlocking {
        val sources = LocalImageSources(folder.newFolder("upload"))
        listOf("/upload/../../private.png", "file://remote/share.png", "https://example.com/image.jpg", "file:///tmp/a.png?token=x", "content://photos/image%XX")
            .forEach { source -> assertThrows(source, IllegalArgumentException::class.java) { runBlocking { sources.resolve(source) } } }
    }
}
