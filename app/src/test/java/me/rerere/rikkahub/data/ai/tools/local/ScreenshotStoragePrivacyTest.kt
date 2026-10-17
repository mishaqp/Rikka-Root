package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.root.RootProcessResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ScreenshotStoragePrivacyTest {
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val uri = Uri.parse("content://media/external/images/media/42")
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    private fun capture(input: String = "{}"): Pair<List<UIMessagePart>, JsonObject> = runBlocking {
        val service = RootScreenService(context, { true }, executeRoot = { command, _, _, _ ->
            when {
                command == "wm size" -> RootProcessResult(exitCode = 0, stdout = "Physical size: 1080x2400")
                command.startsWith("screencap -p") -> {
                    val path = command.substringAfter("screencap -p ").removeSurrounding("'")
                    val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
                    File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    RootProcessResult(exitCode = 0)
                }
                else -> RootProcessResult(exitCode = 0)
            }
        })
        val parts = takeScreenshotTool(context, service).execute(Json.parseToJsonElement(input))
        parts to Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }

    @Test fun missingOrFalseGalleryOptionKeepsScreenshotInPrivateCache() {
        for (input in listOf("{}", """{"save_to_gallery":false}""")) {
            val (parts, result) = capture(input)
            assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
            val file = File(result.getValue("file_path").jsonPrimitive.content)
            assertTrue(file.isFile)
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            assertEquals("file://${file.absolutePath}", parts.filterIsInstance<UIMessagePart.Image>().single().url)
            assertFalse(result.containsKey("gallery_path"))
            assertFalse(result.containsKey("saved_to"))
            assertFalse(result.containsKey("warning"))
        }
        verify(exactly = 0) { resolver.insert(any(), any<ContentValues>()) }
    }

    @Test fun explicitGalleryOptionPublishesTheImageAndRetainsThePrivateAttachment() {
        val bytes = ByteArrayOutputStream()
        every { resolver.insert(any(), any<ContentValues>()) } returns uri
        every { resolver.openOutputStream(uri) } returns bytes
        every { resolver.update(uri, any(), null, null) } returns 1
        every { resolver.query(uri, any(), null, null, null) } returns null
        val (parts, result) = capture("""{"save_to_gallery":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertNotNull(result["gallery_path"])
        assertTrue(bytes.size() > 0)
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        verify(exactly = 1) { resolver.insert(any(), match<ContentValues> { it.getAsInteger("is_pending") == 1 }) }
        verify(exactly = 1) { resolver.update(uri, match { it.getAsInteger("is_pending") == 0 }, null, null) }
        verify(exactly = 0) { resolver.delete(uri, null, null) }
    }

    @Test fun galleryWriteFailureDeletesThePendingRowAndKeepsTheChatImage() {
        every { resolver.insert(any(), any<ContentValues>()) } returns uri
        every { resolver.openOutputStream(uri) } throws IOException("private storage diagnostic")
        val (parts, result) = capture("""{"save_to_gallery":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(result.getValue("warning").jsonPrimitive.content.contains("галере"))
        assertFalse(result.toString().contains("private storage diagnostic"))
        verify(exactly = 1) { resolver.delete(uri, null, null) }
        verify(exactly = 0) { resolver.update(uri, any(), null, null) }
    }
    @Test fun galleryStreamCloseFailureAlsoDeletesThePendingRow() {
        every { resolver.insert(any(), any<ContentValues>()) } returns uri
        every { resolver.openOutputStream(uri) } returns object : ByteArrayOutputStream() {
            override fun close() { throw IOException("private close diagnostic") }
        }
        val (_, result) = capture("""{"save_to_gallery":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(result.containsKey("warning"))
        assertFalse(result.toString().contains("private close diagnostic"))
        verify(exactly = 1) { resolver.delete(uri, null, null) }
        verify(exactly = 0) { resolver.update(uri, any(), null, null) }
    }

    @Test fun privateScreenshotMovesToChatUploadsSoItOutlivesTheHourlyCachePrune() = runBlocking {
        val cache = File(context.cacheDir, "screenshots").apply { mkdirs() }
        val cacheFile = File(cache, "screen-test.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = File(context.filesDir, "upload/screen.png").apply { parentFile?.mkdirs() }
        val entity = mockk<me.rerere.rikkahub.data.db.entity.ManagedFileEntity>()
        val files = mockk<me.rerere.rikkahub.data.files.FilesManager>()
        mockkStatic("me.rerere.rikkahub.data.files.FilesManagerKt")
        try {
            coEvery { files.saveUploadFromBytes(any(), "Screenshot.png", "image/png") } answers {
                stored.writeBytes(secondArg()); entity
            }
            every { files.getFile(entity) } returns stored
            assertEquals(stored, keepWithChat(cacheFile, "Screenshot.png", files))
            assertFalse("The cache copy is removed after the move", cacheFile.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), stored.readBytes())
        } finally {
            unmockkStatic("me.rerere.rikkahub.data.files.FilesManagerKt")
        }
    }

    @Test fun failedMoveKeepsTheCacheCopy() = runBlocking {
        val cacheFile = File(File(context.cacheDir, "screenshots").apply { mkdirs() }, "screen-fail.png")
            .apply { writeBytes(byteArrayOf(4)) }
        val files = mockk<me.rerere.rikkahub.data.files.FilesManager>()
        mockkStatic("me.rerere.rikkahub.data.files.FilesManagerKt")
        try {
            coEvery { files.saveUploadFromBytes(any(), any(), any()) } throws IOException("disk full")
            assertEquals(cacheFile, keepWithChat(cacheFile, "Screenshot.png", files))
            assertTrue(cacheFile.exists())
        } finally {
            unmockkStatic("me.rerere.rikkahub.data.files.FilesManagerKt")
        }
    }
}
