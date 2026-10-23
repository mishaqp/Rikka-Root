package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import kotlin.uuid.Uuid

/** Click the real cards and SAF callback; repository, path resolution and file bytes stay real. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatProducedFileActionsRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val workspaceId = Uuid.parse("60000000-0000-0000-0000-000000000021")
    private val registry = RecordingRegistry()
    private val restoration = StateRestorationTester(compose)
    private lateinit var manager: WorkspaceManager
    private lateinit var repository: WorkspaceRepository
    private lateinit var mount: File

    @Before fun install() {
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
        val base = File(app.filesDir, "produced-workspaces").apply { deleteRecursively(); mkdirs() }
        mount = File(app.filesDir, "produced-mounted-files").apply { deleteRecursively(); mkdirs() }
        manager = WorkspaceManager(base, bindMounts = listOf(WorkspaceBindMount(mount, "/skills")))
        manager.ensureWorkspace(workspaceId.toString())
        val dao = mockk<WorkspaceDAO>()
        coEvery { dao.getById(workspaceId.toString()) } returns WorkspaceEntity(
            workspaceId.toString(), "Тестовая среда", workspaceId.toString(), createdAt = 1, updatedAt = 1,
        )
        repository = WorkspaceRepository(dao, manager, mockk(), mockk())
        startKoin { modules(module { single { repository } }) }
        val provider = Robolectric.buildContentProvider(ProducedDocumentProvider::class.java).create(AUTHORITY).get()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        ProducedDocumentProvider.opensOnMain.clear()
        ShadowToast.reset()
        drainLaunches()
    }

    @After fun close() { stopKoin() }

    @Test fun `deleted generated document explains failure without launching a missing file`() {
        val file = File(app.cacheDir, "produced-missing.pdf").apply { delete() }
        render(document(file.toUri().toString(), "Удалённый.pdf", "application/pdf"))
        select("Удалённый.pdf")
        compose.onNodeWithTag("chat_produced_file_open").performClick()

        assertNull("Do not send a nonexistent file to another app", awaitLaunchOrExplanation())
        assertFailureExplanation()
    }

    @Test fun `generated content document opens with its actual URI MIME and read grant`() {
        File(app.cacheDir, "document.pdf").writeText("document fixture")
        val uri = "content://$AUTHORITY/document.pdf"
        render(document(uri, "Отчёт.pdf", "application/pdf"))
        select("Отчёт.pdf")
        compose.onNodeWithTag("chat_produced_file_open").performClick()

        val launched = checkNotNull(awaitLaunchOrExplanation())
        val view = if (launched.action == Intent.ACTION_CHOOSER) {
            checkNotNull(launched.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        } else launched
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.dataString)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `deleted generated FileProvider report cannot be opened as an available attachment`() {
        val uri = generatedReport("produced-deleted-open.zip", "archive bytes")
        File(app.cacheDir, "produced-deleted-open.zip").delete()
        render(document(uri.toString(), "Отчёт.zip", "application/zip"))
        select("Отчёт.zip")
        compose.onNodeWithTag("chat_produced_file_open").performClick()

        assertNull("Missing content URI backing file must not reach the viewer", awaitLaunchOrExplanation())
        assertFailureExplanation()
    }

    @Test fun `deleted generated FileProvider report cannot be shared as an available attachment`() {
        val uri = generatedReport("produced-deleted-share.zip", "archive bytes")
        File(app.cacheDir, "produced-deleted-share.zip").delete()
        render(document(uri.toString(), "Отчёт.zip", "application/zip"))
        select("Отчёт.zip")
        compose.onNodeWithTag("chat_produced_file_share").performClick()

        assertNull("Missing content URI backing file must not reach the receiver", awaitLaunchOrExplanation())
        assertFailureExplanation()
    }

    @Test fun `readable generated FileProvider report shares its own bytes with read access`() {
        val uri = generatedReport("produced-readable-share.zip", "original report bytes\n")
        render(document(uri.toString(), "Отчёт.zip", "application/zip"))
        select("Отчёт.zip")
        compose.onNodeWithTag("chat_produced_file_share").performClick()
        val send = awaitShare()

        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("application/zip", send.type)
        assertEquals("original report bytes\n", read(uri))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `workspace export keeps bytes under workspace absolute path`() {
        manager.writeText(workspaceId.toString(), "sub/report.txt", "workspace bytes\n")
        exportAndAssert("/workspace/sub/report.txt", "workspace bytes\n")
    }

    @Test fun `rootfs export keeps bytes under ordinary absolute path`() {
        File(manager.linuxDir(workspaceId.toString()), "tmp/report.txt").apply {
            parentFile!!.mkdirs(); writeText("rootfs bytes\n")
        }
        exportAndAssert("/tmp/report.txt", "rootfs bytes\n")
    }

    @Test fun `bind mounted file exports the same bytes the workspace tool reads`() {
        File(mount, "demo/report.txt").apply { parentFile!!.mkdirs(); writeText("mounted bytes\n") }
        exportAndAssert("/skills/demo/report.txt", "mounted bytes\n")
    }

    @Test fun `a second share with the same basename does not replace the first shared bytes`() {
        manager.writeText(workspaceId.toString(), "first/report.txt", "FIRST\n")
        manager.writeText(workspaceId.toString(), "second/report.txt", "SECOND\n")
        render(workspace("/workspace/first/report.txt"), workspace("/workspace/second/report.txt"))
        // Identical chip names: the action sheet's full path identifies each source.
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("chat_produced_file_chip"))[0].performClick()
        compose.onNodeWithTag("chat_produced_file_share").performClick()
        val first = awaitShare()
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("chat_produced_file_chip"))[1].performClick()
        compose.onNodeWithTag("chat_produced_file_share").performClick()
        val second = awaitShare()

        val firstUri = checkNotNull(first.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        val secondUri = checkNotNull(second.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("FIRST\n", read(firstUri))
        assertEquals("SECOND\n", read(secondUri))
        assertNotEquals("Each granted URI must retain its own snapshot", firstUri, secondUri)
    }

    @Test fun `workspace share provides useful MIME and grants recipient read access`() {
        manager.writeText(workspaceId.toString(), "report.txt", "shared text\n")
        render(workspace("/workspace/report.txt"))
        select("report.txt")
        compose.onNodeWithTag("chat_produced_file_share").performClick()
        val send = awaitShare()

        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertEquals("shared text\n", read(uri))
    }

    @Test fun `export failure from a deleted document is explained`() {
        val source = File(app.cacheDir, "produced-export-deleted.txt").apply { delete() }
        render(document(source.toUri().toString(), "Удалённый.txt", "text/plain"))
        select("Удалённый.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        val target = File(app.cacheDir, "produced-failed-export.txt").apply { delete() }
        deliver(target.toUri())

        awaitFailureExplanation()
        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test fun `null input stream is reported as a failed export`() {
        render(document("content://$AUTHORITY/null-source", "Недоступный.txt", "text/plain"))
        select("Недоступный.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        deliver(File(app.cacheDir, "produced-null-source-export.txt").toUri())
        awaitFailureExplanation()
    }

    @Test fun `null destination stream is reported as a failed export`() {
        manager.writeText(workspaceId.toString(), "report.txt", "must not disappear")
        render(workspace("/workspace/report.txt"))
        select("report.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        deliver(Uri.parse("content://$AUTHORITY/null-destination"))
        awaitFailureExplanation()
    }

    @Test fun `document provider and byte copy are not opened on the UI thread`() {
        val source = File(app.cacheDir, "produced-provider-source.txt").apply { writeText("A\n  B\n") }
        render(document("content://$AUTHORITY/${source.name}", "Источник.txt", "text/plain"))
        select("Источник.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        val target = File(app.cacheDir, "produced-provider-destination.txt").apply { delete() }
        deliver(target.toUri())
        await { target.exists() && target.readText() == "A\n  B\n" }

        assertTrue("Observe a real ContentProvider call", ProducedDocumentProvider.opensOnMain.isNotEmpty())
        assertTrue("Opening/copying document bytes must not block Main", ProducedDocumentProvider.opensOnMain.none { it })
    }

    @Test fun `pending export survives restored composition while Android picker is open`() {
        manager.writeText(workspaceId.toString(), "report.txt", "after recreation\n")
        render(workspace("/workspace/report.txt"))
        select("report.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        restoration.emulateSavedInstanceStateRestore()
        val target = File(app.cacheDir, "produced-restored-export.txt").apply { delete() }
        deliver(target.toUri())
        await { target.exists() && target.readText() == "after recreation\n" }
    }

    @Test fun `document export keeps its source URI after composition is restored`() {
        val source = generatedReport("produced-restore-source.zip", "restored report\n")
        render(document(source.toString(), "Отчёт.zip", "application/zip"))
        select("Отчёт.zip")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        restoration.emulateSavedInstanceStateRestore()
        val target = File(app.cacheDir, "produced-restore-target.zip").apply { delete() }
        deliver(target.toUri())
        await { target.exists() && target.readText() == "restored report\n" }
    }

    @Test fun `cancelled export picker creates no file and reports no failure`() {
        manager.writeText(workspaceId.toString(), "report.txt", "not exported")
        render(workspace("/workspace/report.txt"))
        select("report.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        deliver(null)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `missing workspace share is explained and does not launch a receiver`() {
        render(workspace("/workspace/deleted.txt"))
        select("deleted.txt")
        compose.onNodeWithTag("chat_produced_file_share").performClick()
        awaitFailureExplanation()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun render(vararg parts: UIMessagePart) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        restoration.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    EditedFilesList(parts.toList(), ScreenshotFixtures.assistant.copy(workspaceId = workspaceId))
                }
            }
        }
        compose.waitForIdle()
        drainLaunches()
    }

    private fun select(name: String) { compose.onNodeWithText(name).performClick() }
    private fun deliver(uri: Uri?) { compose.runOnIdle { registry.dispatchResult(registry.code, uri) } }
    private fun read(uri: Uri): String = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    private fun generatedReport(name: String, bytes: String): Uri {
        val file = File(app.cacheDir, name).apply { writeText(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }
    private fun drainLaunches() { while (shadowOf(app).nextStartedActivity != null) { } }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun assertFailureExplanation() {
        assertTrue("The file action must explain its failure", !ShadowToast.getTextOfLatestToast().isNullOrBlank())
    }
    private fun awaitFailureExplanation() { await { !ShadowToast.getTextOfLatestToast().isNullOrBlank() }; assertFailureExplanation() }
    private fun awaitLaunchOrExplanation(): Intent? {
        var launched: Intent? = null
        await { launched = shadowOf(app).nextStartedActivity; launched != null || !ShadowToast.getTextOfLatestToast().isNullOrBlank() }
        return launched
    }
    private fun exportAndAssert(path: String, expected: String) {
        render(workspace(path))
        select("report.txt")
        compose.onNodeWithTag("chat_produced_file_export").performClick()
        assertEquals("report.txt", registry.input)
        val target = File(app.cacheDir, "produced-export-result.txt").apply { delete() }
        deliver(target.toUri())
        await { target.exists() && target.readText() == expected }
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    private fun awaitShare(): Intent {
        var chooser: Intent? = null
        await { chooser = shadowOf(app).nextStartedActivity; chooser != null }
        val launched = checkNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, launched.action)
        return checkNotNull(launched.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)).also {
            assertEquals(Intent.ACTION_SEND, it.action)
        }
    }
    private fun workspace(path: String) = UIMessagePart.Tool("write:$path", "workspace_write_file", """{"path":"$path"}""",
        listOf(UIMessagePart.Text("""{"success":true,"path":"$path"}""")))
    private fun document(uri: String, name: String, mime: String) = UIMessagePart.Tool("report:$uri", "generate_bug_report", "{}",
        listOf(UIMessagePart.Text("""{"ok":true}"""), UIMessagePart.Document(uri, name, mime)))
    private class RecordingRegistry : ActivityResultRegistry() {
        var code = -1
        var input: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode; this.input = input
        }
    }
    companion object { private const val AUTHORITY = "chat.produced.document" }
}

/** Only the Android document-provider boundary is simulated; bytes travel through real streams. */
class ProducedDocumentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/pdf"
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? =
        if (uri.lastPathSegment?.startsWith("null-") == true) null else super.openAssetFile(uri, mode)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        opensOnMain.add(Looper.myLooper() == Looper.getMainLooper())
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, requireNotNull(uri.lastPathSegment)),
            ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object { val opensOnMain = CopyOnWriteArrayList<Boolean>() }
}
