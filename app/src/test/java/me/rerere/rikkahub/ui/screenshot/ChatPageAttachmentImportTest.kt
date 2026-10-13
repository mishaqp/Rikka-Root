package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.serviceLoaderEnabled
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.base64Encode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

/** The real page imports real provider streams; only VM/services and the database DAO are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatPageAttachmentImportTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var appScope: AppScope
    private lateinit var filesManager: FilesManager
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var imageLoader: ImageLoader
    private lateinit var image: File

    @OptIn(DelicateCoilApi::class)
    @Before fun installBoundaries() {
        // AndroidX retains this cache across Robolectric's per-test application directories.
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
        JLatexMathAndroid.init(context)
        val dao = mockk<ManagedFileDAO>()
        coEvery { dao.getByPath(any()) } returns null
        coEvery { dao.insert(any()) } returns 1L
        every { dao.listByFolder(any()) } returns flowOf(emptyList())
        appScope = AppScope()
        filesManager = FilesManager(context, FilesRepository(dao), appScope)
        image = File(context.cacheDir, "photo.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val offlineClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Import fixture: offline").body(ByteArray(0).toResponseBody()).build()
        }.build()
        imageLoader = ImageLoader.Builder(context).serviceLoaderEnabled(false).diskCache(null).components {
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { offlineClient }))
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        fixture = ChatBeforeFixture(image, filesManager = filesManager)
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = emptyList())
        fixture.install()
        Robolectric.buildContentProvider(NullMimeAttachmentProvider::class.java)
            .create(NULL_MIME_AUTHORITY).get()
    }

    @OptIn(DelicateCoilApi::class)
    @After fun close() {
        if (::fixture.isInitialized) fixture.close()
        if (::appScope.isInitialized) appScope.cancel()
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        SingletonImageLoader.reset()
    }

    @Test fun `failed first WAV does not label the following PNG as audio`() {
        val missing = fileUri(File(context.cacheDir, "missing.wav"))
        val png = fileUri(image)
        assertTrue(filesManager.getFileMimeType(missing)?.startsWith("audio/") == true)
        assertEquals("image/png", filesManager.getFileMimeType(png))

        val parts = importDraft(listOf(missing, png))

        assertEquals(1, parts.size)
        assertTrue("The successfully copied PNG must stay Image, got ${parts.single()}", parts.single() is UIMessagePart.Image)
        assertCopied(parts.single(), image)
    }

    @Test fun `null MIME attachment keeps its fallback and the following PNG`() {
        val unknown = nullMimeFile("unknown", byteArrayOf(0, 1, 2, 3, 4))
        assertNull(filesManager.getFileMimeType(unknown.second))

        val parts = importDraft(listOf(unknown.second, fileUri(image)))

        assertEquals("Both successful copies must remain attached", 2, parts.size)
        assertDocument(parts[0], unknown.first, "application/octet-stream")
        assertTrue(parts[1] is UIMessagePart.Image)
        assertCopied(parts[1], image)
    }

    @Test fun `PDF DOCX TXT and shared text remain in the real draft`() {
        val pdf = sourceFile("report.pdf", "%PDF-1.4\nreport".toByteArray())
        val docx = sourceFile("notes.docx", byteArrayOf(0x50, 0x4b, 3, 4, 0, 1))
        val txt = sourceFile("message.txt", "Текст файла".toByteArray())
        val text = "Текст вместе с документами"

        val parts = importDraft(listOf(pdf, docx, txt).map(::fileUri), text)

        assertEquals(UIMessagePart.Text(text), parts.first())
        assertEquals("All three documents must remain beside shared text", 4, parts.size)
        assertDocument(parts[1], pdf, "application/pdf")
        assertDocument(parts[2], docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertDocument(parts[3], txt, "text/plain")
    }

    @Test fun `failed middle copy preserves video image audio and document order with text`() {
        val video = sourceFile("clip.mp4", byteArrayOf(0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70))
        val missing = fileUri(File(context.cacheDir, "missing-middle.wav"))
        val audio = sourceFile("voice.wav", "RIFF0000WAVE".toByteArray())
        val txt = sourceFile("last.txt", "Последний файл".toByteArray())
        val text = "Порядок сохраняется"

        val parts = importDraft(listOf(fileUri(video), missing, fileUri(image), fileUri(audio), fileUri(txt)), text)

        assertEquals(UIMessagePart.Text(text), parts.first())
        assertEquals(5, parts.size)
        assertTrue(parts[1] is UIMessagePart.Video)
        assertCopied(parts[1], video)
        assertTrue("A failure in the middle must not turn PNG into Audio", parts[2] is UIMessagePart.Image)
        assertCopied(parts[2], image)
        assertTrue(parts[3] is UIMessagePart.Audio)
        assertCopied(parts[3], audio)
        assertDocument(parts[4], txt, "text/plain")
    }

    @Test fun `null MIME PNG uses the copied file signature without shifting following audio`() {
        val png = nullMimeFile("signature", image.readBytes())
        val audio = sourceFile("after.wav", "RIFF0000WAVE".toByteArray())

        val parts = importDraft(listOf(png.second, fileUri(audio)))

        assertEquals(2, parts.size)
        assertTrue("Null provider MIME must allow the existing file signature fallback", parts[0] is UIMessagePart.Image)
        assertCopied(parts[0], png.first)
        assertTrue(parts[1] is UIMessagePart.Audio)
        assertCopied(parts[1], audio)
    }

    @Test fun `URI only import callers still receive only successful copied files`() {
        val missing = fileUri(File(context.cacheDir, "compat-missing.wav"))
        val copied = filesManager.createChatFilesByContents(listOf(missing, fileUri(image)))

        assertEquals(1, copied.size)
        assertFalse(copied.single() == fileUri(image))
        assertArrayEquals(image.readBytes(), copied.single().toFile().readBytes())
    }

    private fun importDraft(files: List<Uri>, text: String? = null): List<UIMessagePart> {
        compose.setContent { ScreenshotTheme { fixture.Content(text?.base64Encode(), files) } }
        compose.waitForIdle()
        var contents = emptyList<UIMessagePart>()
        compose.runOnIdle { contents = fixture.vm.inputState.getContents() }
        return contents
    }

    private fun assertDocument(part: UIMessagePart, source: File, mime: String) {
        assertTrue("${source.name} must remain Document, got $part", part is UIMessagePart.Document)
        part as UIMessagePart.Document
        assertEquals(source.name, part.fileName)
        assertEquals(mime, part.mime)
        assertCopied(part, source)
    }

    private fun assertCopied(part: UIMessagePart, source: File) {
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Document -> part.url
            else -> error("Not a file attachment: $part")
        }
        assertTrue("Draft attachment must point to a successful local copy", url.startsWith("file:"))
        val copied = url.toUri().toFile()
        assertFalse("Imported content must have its own local copy", copied.canonicalFile == source.canonicalFile)
        assertTrue(copied.canonicalPath.startsWith(File(context.filesDir, "upload").canonicalPath + File.separator))
        assertArrayEquals(source.readBytes(), copied.readBytes())
    }

    private fun sourceFile(name: String, bytes: ByteArray) = File(context.cacheDir, name).apply { writeBytes(bytes) }
    private fun fileUri(file: File) = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    private fun nullMimeFile(name: String, bytes: ByteArray): Pair<File, Uri> {
        val source = sourceFile(name, bytes)
        return source to Uri.parse("content://$NULL_MIME_AUTHORITY/$name")
    }

    companion object { private const val NULL_MIME_AUTHORITY = "chat.import.nullmime" }
}

/** A provider with genuinely absent MIME metadata and a real openFile stream. */
class NullMimeAttachmentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(requireNotNull(uri.lastPathSegment))) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, requireNotNull(uri.lastPathSegment)), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only provider")
}
