package me.rerere.rikkahub.miniapps

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MiniAppWebSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun runtimeServesRelativeAssetsAndRejectsTraversalAndOtherOrigins() {
        val root = Files.createTempDirectory("mini-runtime-").toFile()
        val app = MiniApp("sample", "Sample", File(root, "sample"))
        app.codeDirectory.mkdirs()
        File(app.codeDirectory, "index.html").writeText("<script src='assets/main.js'></script>")
        File(app.codeDirectory, "assets").mkdirs()
        File(app.codeDirectory, "assets/main.js").writeText("window.loaded=true")
        File(app.directory, "private.json").writeText("secret")
        val session = MiniAppWebSession(context, app, MiniAppBridge(MiniAppStore(root), app.id))
        val parent = android.widget.FrameLayout(context).apply { addView(session.webView) }
        try {
            val uri = Uri.parse("${session.origin}/assets/main.js")
            val response = session.serve(uri)
            assertEquals(200, response.statusCode)
            assertEquals("application/javascript", response.mimeType)
            assertEquals("window.loaded=true", response.data.bufferedReader().readText())
            assertEquals(404, session.serve(Uri.parse("${session.origin}/%2e%2e/private.json")).statusCode)
            assertEquals(404, session.serve(Uri.parse("https://example.com/assets/main.js")).statusCode)
            assertNull(session.intercept(Uri.parse("https://cdn.example.com/app.js")))
            assertNull(session.intercept(Uri.parse("http://cdn.example.com/style.css")))
            assertEquals(404, session.intercept(Uri.parse("https://other.miniapps.invalid/index.html"))?.statusCode)
            assertEquals(404, session.intercept(Uri.parse("file:///data/private.json"))?.statusCode)
            assertEquals(404, session.intercept(Uri.parse("content://provider/private"))?.statusCode)
            assertFalse(session.owns(Uri.parse("${session.origin}:444/index.html")))
            assertFalse(session.webView.settings.domStorageEnabled)
            assertFalse(session.webView.settings.allowFileAccess)
        } finally { session.close(); assertEquals(0, parent.childCount); root.deleteRecursively() }
    }

    @Test fun lateReplyFromPreviousDocumentCannotResolveReusedRequestId() = runBlocking {
        val root = Files.createTempDirectory("mini-reply-").toFile()
        var session: MiniAppWebSession? = null
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            File(source, "index.html").writeText("<body>Water</body>")
            val store = MiniAppStore(File(root, "installed"))
            val app = runBlocking { store.install(source).app }
            val oldAnswer = CompletableDeferred<String>()
            val bridge = MiniAppBridge(store, app.id, MiniAppHost(ask = { _, _ -> oldAnswer.await() }))
            val runtime = MiniAppWebSession(context, app, bridge)
            session = runtime
            val url = "${runtime.origin}/index.html"
            runtime.webView.loadUrl(url)
            runtime.webView.webViewClient.onPageStarted(runtime.webView, url, null)
            val oldCall = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.answer("""{"id":1,"method":"ai.ask","args":{"prompt":"old"}}""")
            }
            assertFalse(oldCall.isCompleted)
            runtime.webView.webViewClient.onPageStarted(runtime.webView, url, null)
            runtime.answer("""{"id":1,"method":"storage.get","args":{"key":"new"}}""")
            val shadow = org.robolectric.Shadows.shadowOf(runtime.webView)
            assertEquals("window.__moruReply(1, true, null);", shadow.lastEvaluatedJavascript)
            oldAnswer.complete("stale model result")
            oldCall.await()
            assertTrue(oldCall.isCompleted)
            assertEquals("window.__moruReply(1, true, null);", shadow.lastEvaluatedJavascript)
        } finally { session?.close(); root.deleteRecursively() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun promptCancellationAfterDirectoryAllocationStillDeletesDirectory() {
        val parent = Files.createTempDirectory("mini-cancel-").toFile()
        val callerScheduler = TestCoroutineScheduler()
        val allocationScheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(callerScheduler))
        var enteredBlock = false
        try {
            val job = scope.launch {
                withCheckDirectory(parent, StandardTestDispatcher(allocationScheduler)) {
                    enteredBlock = true
                }
            }
            callerScheduler.runCurrent() // Allocation is now queued on its separate dispatcher.
            allocationScheduler.runCurrent() // Real directory created; caller continuation remains queued.
            assertEquals(1, parent.listFiles().orEmpty().size)
            job.cancel()
            callerScheduler.runCurrent() // Prompt cancellation enters the already-established finally.
            allocationScheduler.runCurrent() // NonCancellable cleanup performs actual deletion.
            callerScheduler.runCurrent()
            assertTrue(job.isCompleted)
            assertFalse(enteredBlock)
            assertTrue(parent.listFiles().orEmpty().isEmpty())
        } finally { scope.cancel(); parent.deleteRecursively() }
    }

    @Test fun reportReplacesOpaqueErrorsWithConsoleDetailsAndKeepsBlankPageSignal() {
        val json = MiniAppCheckReport(
            loaded = true,
            pageErrors = listOf("error: Script error.", "resource: missing.png", "error: Script error."),
            console = listOf("warn: warning", "error: TypeError: broken", "error: ReferenceError: missing"),
            visibleContent = 0,
        ).toJson()
        assertEquals(listOf("error: TypeError: broken", "resource: missing.png", "error: ReferenceError: missing"),
            json.getValue("page_errors").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(json.getValue("ok").jsonPrimitive.boolean)
        assertTrue(json.getValue("blank_page").jsonPrimitive.boolean)
    }

    @Test fun sandboxCopiesDataWithoutChangingInstallationAndRejectsSymlinks() {
        val root = Files.createTempDirectory("mini-check-").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "data").mkdirs()
            File(source, "data/state.json").writeText("original")
            val checker = MiniAppChecker(context, MiniAppStore(root))
            val target = File(root, "copy")
            checker.copyInstallation(source, target)
            File(target, "data/state.json").writeText("changed")
            assertEquals("original", File(source, "data/state.json").readText())
            Files.createSymbolicLink(File(source, "escape").toPath(), root.toPath())
            assertThrows(IllegalStateException::class.java) { checker.copyInstallation(source, File(root, "unsafe")) }
        } finally { root.deleteRecursively() }
    }
}
