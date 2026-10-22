package me.rerere.rikkahub.ui.screenshot

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toFile
import com.dokar.sonner.rememberToasterState
import com.yalantis.ucrop.UCrop
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi", shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentImportIoPart3Test {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var scope: AppScope
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var input: ChatInputState
    private lateinit var actions: ChatAttachmentPickerActions
    private val publications = CopyOnWriteArrayList<Boolean>()
    private val publicationThreads = CopyOnWriteArrayList<String>()
    private val callbacks = CopyOnWriteArrayList<Boolean>()
    private val registry = RecordingRegistry()
    private val visible = mutableStateOf(true)

    @Before fun setUp() {
        JLatexMathAndroid.init(context)
        AttachmentIoProvider.reset()
        Robolectric.buildContentProvider(AttachmentIoProvider::class.java).create(AUTHORITY).get()
        scope = AppScope()
        val dao = mockk<ManagedFileDAO>()
        coEvery { dao.getByPath(any()) } returns null
        coEvery { dao.insert(any()) } returns 1L
        every { dao.listByFolder(any()) } returns flowOf(emptyList())
        val manager = FilesManager(context, FilesRepository(dao), scope)
        val image = File(context.cacheDir, "fixture.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        fixture = ChatBeforeFixture(image, filesManager = manager)
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = emptyList())
        input = spyk(ChatInputState())
        every { input.messageContent = any() } answers { publications.add(onMain()); callOriginal() }
        fixture.install()
        every { fixture.vm.inputState } returns input
    }

    @After fun tearDown() {
        AttachmentIoProvider.release.countDown()
        if (::fixture.isInitialized) fixture.close()
        if (::scope.isInitialized) scope.cancel()
    }

    @Test fun galleryVideoAudioAndDocumentCallbacksCopyOffMainAndKeepOrderMimeAndBytes() {
        picker(skipCrop = true)
        val selections = listOf(
            Triple({ actions.onPickImage() }, "one.png", "image/png"),
            Triple({ actions.onPickVideo() }, "two.mp4", "video/mp4"),
            Triple({ actions.onPickAudio() }, "three.wav", "audio/wav"),
            Triple({ actions.onPickFile() }, "four.txt", "text/plain"),
        )
        selections.forEachIndexed { index, (launch, name, mime) ->
            val source = source(name, "bytes-$index", mime)
            compose.runOnIdle { launch(); registry.dispatchResult(registry.code, listOf(source.second)) }
            awaitUi { input.messageContent.size == index + 1 }
            assertArrayEquals(source.first.readBytes(), fileOf(input.messageContent[index]).readBytes())
        }
        assertTrue(input.messageContent[0] is UIMessagePart.Image)
        assertTrue(input.messageContent[1] is UIMessagePart.Video)
        assertTrue(input.messageContent[2] is UIMessagePart.Audio)
        val document = input.messageContent[3] as UIMessagePart.Document
        assertEquals("four.txt", document.fileName)
        assertEquals("text/plain", document.mime)
        assertOffMainCopy()
        assertTrue("Публикации: $publications; потоки: $publicationThreads", publications.isNotEmpty())
        assertTrue("Публикации: $publications; потоки: $publicationThreads", publications.all { it })
        assertEquals(4, callbacks.size); assertTrue(callbacks.all { it })
    }

    @Test fun galleryCropCopiesOffMainAndKeepsCropFileUntilAttachmentImportCompletes() {
        picker(skipCrop = false)
        val original = source("crop.png", "gallery-source", "image/png")
        compose.runOnIdle { actions.onPickImage(); registry.dispatchResult(registry.code, listOf(original.second)) }
        awaitUi { registry.input is Intent }
        val cropIntent = registry.input as Intent
        val cropOutput = requireNotNull(cropIntent.getParcelableExtra(UCrop.EXTRA_OUTPUT_URI, Uri::class.java)).toFile()
        val cropInput = requireNotNull(cropIntent.getParcelableExtra(UCrop.EXTRA_INPUT_URI, Uri::class.java)).toFile()
        assertEquals("gallery-source", cropInput.readText())
        cropOutput.writeText("crop-result-must-survive-async-copy")
        compose.runOnIdle { registry.dispatchResult(registry.code, Activity.RESULT_OK, Intent()) }
        awaitUi { input.messageContent.size == 1 }
        assertEquals("crop-result-must-survive-async-copy", fileOf(input.messageContent.single()).readText())
        awaitUi { !cropOutput.exists() && !cropInput.exists() }
        assertEquals("gallery-source", original.first.readText())
        assertOffMainCopy()
        assertTrue("Публикации: $publications; потоки: $publicationThreads", publications.all { it })
        assertTrue("Callbacks: $callbacks; потоки: $publicationThreads", callbacks.all { it })
    }

    @Test fun realShareEntryImportsOffMainAndPublishesAllFilesInOriginalOrder() {
        val first = source("share.txt", "shared-text", "text/plain")
        val second = source("share.wav", "shared-audio", "audio/wav")
        compose.setContent { ScreenshotTheme { fixture.Content(files = listOf(first.second, second.second)) } }
        awaitUi { input.messageContent.size == 2 }
        assertEquals("share.txt", (input.messageContent.first() as UIMessagePart.Document).fileName)
        assertTrue(input.messageContent.last() is UIMessagePart.Audio)
        assertEquals("shared-text", fileOf(input.messageContent.first()).readText())
        assertEquals("shared-audio", fileOf(input.messageContent.last()).readText())
        assertOffMainCopy()
        assertTrue("Публикации: $publications; потоки: $publicationThreads", publications.isNotEmpty())
        assertTrue("Публикации: $publications; потоки: $publicationThreads", publications.all { it })
    }

    @Test fun leavingPickerDuringProviderOpenCancelsWithoutPublishingOrLeavingPartialCopy() {
        picker(skipCrop = true)
        val original = source("held.txt", "original-retained", "text/plain")
        AttachmentIoProvider.hold = true
        compose.runOnIdle { actions.onPickFile(); registry.dispatchResult(registry.code, listOf(original.second)) }
        awaitUi { AttachmentIoProvider.opened.count == 0L }
        assertTrue("Provider was reached", AttachmentIoProvider.opened.await(5, TimeUnit.SECONDS))
        try {
            assertOffMainCopy() // Baseline fails here without blocking Main on a test gate.
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        } finally { AttachmentIoProvider.release.countDown() }
        assertTrue(AttachmentIoProvider.returned.await(5, TimeUnit.SECONDS))
        awaitUi { File(context.filesDir, "upload").listFiles().orEmpty().isEmpty() }
        compose.runOnIdle {
            assertTrue(input.messageContent.isEmpty())
            assertTrue(callbacks.isEmpty())
        }
        assertEquals("original-retained", original.first.readText())
    }

    private fun awaitUi(condition: () -> Boolean) {
        compose.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun picker(skipCrop: Boolean) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            ScreenshotTheme {
                val toaster = rememberToasterState()
                CompositionLocalProvider(LocalToaster provides toaster, LocalActivityResultRegistryOwner provides owner) {
                    if (visible.value) actions = rememberChatAttachmentPickerActions(input, Settings(displaySetting = DisplaySetting(skipCropImage = skipCrop))) {
                        callbacks.add(onMain())
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun source(name: String, value: String, mime: String): Pair<File, Uri> {
        val file = File(context.cacheDir, name).apply { writeText(value) }
        AttachmentIoProvider.mimes[name] = mime
        return file to Uri.parse("content://$AUTHORITY/$name")
    }
    private fun assertOffMainCopy() {
        assertTrue("Real provider open must be observed", AttachmentIoProvider.mainOpens.isNotEmpty())
        assertTrue("Attachment provider/copy must run off Main", AttachmentIoProvider.mainOpens.none { it })
    }
    private fun fileOf(part: UIMessagePart): File = Uri.parse(when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Document -> part.url
        else -> error("Expected attachment")
    }).toFile()
    private fun onMain(): Boolean {
        publicationThreads.add("${Thread.currentThread().name}: looper=${Looper.myLooper()}, main=${Looper.getMainLooper().thread.name}")
        return Looper.myLooper() == Looper.getMainLooper()
    }
    private class RecordingRegistry : ActivityResultRegistry() {
        var code = -1
        var input: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode; this.input = input
        }
    }
    companion object { private const val AUTHORITY = "part3.attachment.io" }
}

class AttachmentIoProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = mimes[uri.lastPathSegment]
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(requireNotNull(uri.lastPathSegment))) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val main = Looper.myLooper() == Looper.getMainLooper()
        mainOpens.add(main)
        opened.countDown()
        // A baseline main-thread call must fail an assertion, never hang the UI fixture.
        if (hold && !main) check(release.await(5, TimeUnit.SECONDS)) { "Provider fixture gate was not released" }
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, requireNotNull(uri.lastPathSegment)), ParcelFileDescriptor.MODE_READ_ONLY)
            .also { returned.countDown() }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object {
        val mainOpens = CopyOnWriteArrayList<Boolean>()
        val mimes = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var hold = false
        var opened = CountDownLatch(1)
        var release = CountDownLatch(1)
        var returned = CountDownLatch(1)
        fun reset() { mainOpens.clear(); mimes.clear(); hold = false; opened = CountDownLatch(1); release = CountDownLatch(1); returned = CountDownLatch(1) }
    }
}
