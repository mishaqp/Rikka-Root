package me.rerere.rikkahub.data.files

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.core.net.toFile
import androidx.core.net.toUri
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Base64ImageToLocalFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `known image formats are saved byte for byte with their real extension`() = runBlocking {
        val jpeg = image(Bitmap.CompressFormat.JPEG)
        val webp = image(Bitmap.CompressFormat.WEBP)
        // A provider may label the data with the wrong type; the bytes decide.
        val result = convert("data:image/png;base64," + Base64.encode(jpeg), "data:image/webp;base64," + Base64.encode(webp))

        val (jpegFile, webpFile) = result.map { it.toUri().toFile() }
        assertEquals("jpeg", jpegFile.extension)
        assertArrayEquals("Known images must not be decoded and re-encoded", jpeg, jpegFile.readBytes())
        assertEquals("webp", webpFile.extension)
        assertArrayEquals(webp, webpFile.readBytes())
    }

    @Test fun `undecodable image data stays inline instead of crashing`() = runBlocking {
        val garbage = "data:image/png;base64," + Base64.encode("not an image at all".toByteArray())
        val broken = "data:image/png;base64,%%%"
        assertEquals(listOf(garbage, broken), convert(garbage, broken))
        assertTrue(File(filesDir, FileFolders.UPLOAD).listFiles().isNullOrEmpty())
    }

    private val filesDir by lazy { temporary.newFolder("files") }

    private suspend fun convert(vararg urls: String): List<String> {
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = this@Base64ImageToLocalFileTest.filesDir
        }
        val files = FilesManager(context, mockk(relaxed = true), AppScope().apply { cancel() })
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = urls.map { UIMessagePart.Image(it) })
        return files.convertBase64ImagePartToLocalFile(message).parts.map { (it as UIMessagePart.Image).url }
    }

    private fun image(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().use { bitmap.compress(format, 90, it); it.toByteArray() }
    }
}
