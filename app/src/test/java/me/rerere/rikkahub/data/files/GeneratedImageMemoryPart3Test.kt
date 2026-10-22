package me.rerere.rikkahub.data.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toFile
import androidx.core.net.toUri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneratedImageMemoryPart3Test {
    @Test fun pngKeepsOriginalBytesAndMetadataWithoutAllocatingBitmap() = preserveImage("image/png", pngWithMetadata())

    @Test fun jpegKeepsOriginalBytesAndFormatWithoutAllocatingBitmap() = preserveImage("image/jpeg", encoded(Bitmap.CompressFormat.JPEG))

    @Test fun existingLocalImageAndOtherMessagePartsStayUnchanged() = runBlocking {
        val scope = AppScope()
        try {
            val manager = manager(scope)
            val input = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("Original text"), UIMessagePart.Image("file:///unchanged/photo.jpg")))
            assertEquals(input, manager.convertBase64ImagePartToLocalFile(input))
        } finally { scope.cancel() }
    }

    @Test fun decodableUnknownSignatureKeepsBytesWithoutAllocatingBitmap() = preserveImage("image/bmp", bmp())

    @Test fun malformedEncodedImageStaysInlineWithoutCreatingAnAttachment() = runBlocking {
        val scope = AppScope()
        try {
            val upload = java.io.File(RuntimeEnvironment.getApplication().filesDir, "upload")
            val existing = upload.listFiles().orEmpty().map { it.name }.toSet()
            val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString("Некорректное изображение".toByteArray())}"
            val input = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image(dataUrl)))
            assertEquals("Не удалось декодировать — исходная часть остаётся доступной", input, manager(scope).convertBase64ImagePartToLocalFile(input))
            assertEquals(existing, upload.listFiles().orEmpty().map { it.name }.toSet())
        } finally { scope.cancel() }
    }

    private fun bmp(): ByteArray {
        // A real 2x2 24-bit BMP, including the required four-byte row alignment.
        val pixels = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0, 0, 0x70, 0x20, 0x30, 0x40, 0x50, 0x60, 0, 0)
        return java.nio.ByteBuffer.allocate(54 + pixels.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + pixels.size).putInt(0).putInt(54)
            .putInt(40).putInt(2).putInt(2).putShort(1).putShort(24).putInt(0).putInt(pixels.size)
            .putInt(2835).putInt(2835).putInt(0).putInt(0).put(pixels).array()
    }

    private fun preserveImage(mime: String, original: ByteArray) = runBlocking {
        val scope = AppScope()
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } answers { callOriginal() }
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            assertTrue("Разрешено только чтение размеров без Bitmap", arg<BitmapFactory.Options>(3).inJustDecodeBounds)
            callOriginal()
        }
        try {
            val dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(original)}"
            val input = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("Keep this"), UIMessagePart.Image(dataUrl)))
            val result = manager(scope).convertBase64ImagePartToLocalFile(input)
            val image = result.parts.filterIsInstance<UIMessagePart.Image>().single()
            assertEquals(input.id, result.id)
            assertEquals(input.parts.first(), result.parts.first())
            assertEquals(dataUrl, (input.parts.last() as UIMessagePart.Image).url)
            assertTrue(image.url.startsWith("file:"))
            val file = image.url.toUri().toFile()
            assertArrayEquals("Normalization must retain encoded image bytes, including metadata", original, file.readBytes())
            assertEquals(when (mime) { "image/jpeg" -> "jpeg"; "image/bmp" -> "bmp"; else -> "png" }, file.extension)
            verify(exactly = 0) { BitmapFactory.decodeByteArray(any(), any(), any()) }
        } finally {
            unmockkStatic(BitmapFactory::class)
            scope.cancel()
        }
    }

    private fun manager(scope: AppScope): FilesManager {
        val dao = mockk<ManagedFileDAO>()
        coEvery { dao.getByPath(any()) } returns null
        coEvery { dao.insert(any()) } returns 1L
        return FilesManager(RuntimeEnvironment.getApplication(), FilesRepository(dao), scope)
    }

    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff1267ab.toInt())
        return try { ByteArrayOutputStream().use { bytes ->
            assertTrue(bitmap.compress(format, 92, bytes)); bytes.toByteArray()
        } } finally { bitmap.recycle() }
    }

    private fun pngWithMetadata(): ByteArray {
        val png = encoded(Bitmap.CompressFormat.PNG)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val payload = "Comment\u0000Original metadata must survive".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(payload) }
        val chunk = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out -> out.writeInt(payload.size); out.write(type); out.write(payload); out.writeInt(crc.value.toInt()) }
            bytes.toByteArray()
        }
        return png.copyOfRange(0, png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size)
    }
}
