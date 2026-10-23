package me.rerere.rikkahub.ui.components.message

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** Real intents and FileProvider; unavailable viewers are replaced at the Android launch boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU")
class ChatAttachmentOpenRuntimeTest {
    private val host = Robolectric.buildActivity(Activity::class.java)
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: Activity

    @Before fun prepare() {
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
        activity = host.setup().get()
        val provider = Robolectric.buildContentProvider(PdfProvider::class.java).create("chat.attachment.fixture").get()
        ShadowContentResolver.registerProviderInternal("chat.attachment.fixture", provider)
        while (shadowOf(app).nextStartedActivity != null) { }
        ShadowToast.reset()
    }

    @After fun close() { host.pause().stop().destroy() }

    @Test fun `content attachment keeps its URI and provider MIME instead of converting it to File`() {
        val uri = Uri.parse("content://chat.attachment.fixture/document/42")
        assertTrue("A content attachment must not crash the chat", runCatching { activity.openLocalFile(uri.toString()) }.isSuccess)
        val target = launchedView()
        assertEquals(uri, target.data)
        assertEquals("application/pdf", target.type)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `ordinary cached file opens through FileProvider with a useful MIME`() {
        val file = File(app.cacheDir, "chat-open.pdf").apply { writeText("fixture") }
        activity.openLocalFile(file.toUri().toString())
        val target = launchedView()
        assertEquals("content", target.data!!.scheme)
        assertEquals("${app.packageName}.fileprovider", target.data!!.authority)
        assertEquals("application/pdf", target.type)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `remote media URI reaches the viewer unchanged`() {
        val url = "https://example.invalid/audio/recording.mp3?name=a%20b"
        assertTrue("Remote attachments must not be treated as local files", runCatching { activity.openLocalFile(url) }.isSuccess)
        val target = launchedView()
        assertEquals(url, target.dataString)
        assertNull("Network URLs must remain compatible with ordinary browsers", target.type)
    }

    @Test fun `remote attachment intent remains compatible with ordinary browser filters`() {
        val browser = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        }
        for (url in listOf("https://example.invalid/recording.mp3", "http://example.invalid/document.pdf")) {
            activity.openLocalFile(url)
            val target = launchedView()
            assertTrue("A remote attachment must remain openable by a browser without a separate media viewer",
                browser.match(target.action, target.type, target.scheme, target.data, target.categories, "chat-attachment") >= 0)
        }
    }

    @Test fun `application context can launch the viewer in a new task`() {
        val file = File(app.cacheDir, "chat-from-application.pdf").apply { writeText("fixture") }
        app.openLocalFile(file.toUri().toString())
        val chooser = checkNotNull(shadowOf(app).nextStartedActivity)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `unknown file type still supplies a general binary MIME to the viewer`() {
        val file = File(app.cacheDir, "chat-unknown-type").apply { writeText("fixture") }
        activity.openLocalFile(file.toUri().toString())
        assertEquals("application/octet-stream", launchedView().type)
    }

    @Test fun `files outside the existing FileProvider roots are not exposed`() {
        val file = File(app.noBackupFilesDir, "chat-private.pdf").apply { parentFile!!.mkdirs(); writeText("fixture") }
        assertTrue(runCatching { activity.openLocalFile(file.toUri().toString()) }.isSuccess)
        assertNull(shadowOf(app).nextStartedActivity)
        assertAttachmentExplanation()
    }

    @Test fun `deleted file explains the failure without launching a nonexistent attachment`() {
        val file = File(app.cacheDir, "chat-missing.pdf").apply { delete() }
        assertTrue(runCatching { activity.openLocalFile(file.toUri().toString()) }.isSuccess)
        assertNull("An absent file must not be handed to another application", shadowOf(app).nextStartedActivity)
        assertAttachmentExplanation()
    }

    @Test fun `missing viewer does not throw from the click handler`() = assertLaunchFailure(
        ActivityNotFoundException("fixture: no handler"),
    )

    @Test fun `denied Android launch permission does not throw from the click handler`() = assertLaunchFailure(
        SecurityException("fixture: access denied"),
    )

    @Test fun `unsupported attachment URI produces an explanation without a launch`() {
        assertTrue(runCatching { activity.openLocalFile("invalid-attachment:missing") }.isSuccess)
        assertNull(shadowOf(app).nextStartedActivity)
        assertAttachmentExplanation()
    }

    private fun assertLaunchFailure(error: RuntimeException) {
        val file = File(app.cacheDir, "chat-launch.pdf").apply { writeText("fixture") }
        val denied = object : ContextWrapper(activity) {
            override fun startActivity(intent: Intent) { throw error }
        }
        assertTrue("Viewer failures must be shown inside the UI", runCatching { denied.openLocalFile(file.toUri().toString()) }.isSuccess)
        assertAttachmentExplanation()
    }

    private fun launchedView(): Intent {
        val chooser = checkNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)).also {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertEquals(0, it.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun assertAttachmentExplanation() {
        assertTrue("The user needs an attachment-specific explanation", ShadowToast.getTextOfLatestToast()
            ?.contains("вложен", ignoreCase = true) == true)
    }

    class PdfProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
