package me.rerere.rikkahub.miniapps

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

/** Behavioral reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_check.dart. */
data class MiniAppCheckReport(
    val loaded: Boolean,
    val pageErrors: List<String> = emptyList(),
    val console: List<String> = emptyList(),
    val failedCalls: List<String> = emptyList(),
    val visibleContent: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", loaded && pageErrors.isEmpty() && failedCalls.isEmpty())
        put("loaded", loaded)
        if (pageErrors.isNotEmpty()) {
            val details = console.filter { it.startsWith("error: ") }.iterator()
            put("page_errors", JsonArray(pageErrors.map { error ->
                JsonPrimitive(if (error == "error: Script error." && details.hasNext()) details.next() else error)
            }))
        }
        if (failedCalls.isNotEmpty()) put("failed_moru_calls", JsonArray(failedCalls.map(::JsonPrimitive)))
        if (console.isNotEmpty()) put("console", JsonArray(console.map(::JsonPrimitive)))
        if (visibleContent == 0) put("blank_page", true)
    }
}

class MiniAppChecker(
    private val context: Context,
    private val store: MiniAppStore,
    private val fetch: MiniAppFetch = MiniAppFetch(),
) {
    suspend fun run(app: MiniApp): MiniAppCheckReport {
        val current = store.byId(app.id) ?: throw MiniAppException("not_found", "The app was not installed.")
        return withCheckDirectory(context.cacheDir) { root ->
            var session: MiniAppWebSession? = null
            try {
                withContext(Dispatchers.IO) { copyInstallation(current.directory, File(root, app.id)) }
                val sandbox = MiniAppStore(root)
                sandbox.load()
                val installed = sandbox.byId(app.id) ?: throw MiniAppException("not_found", "The app was not installed.")
                sandbox.refreshBridge(installed)
                val host = MiniAppHost(
                    ask = { _, _ -> "Test answer from Moru." },
                    notify = { _, _ -> },
                    calendar = { method, _ -> if (method == "queryCalendar") buildJsonObject { put("events", JsonArray(emptyList())) } else buildJsonObject { put("id", 0) } },
                    reminders = MiniAppReminders(sandbox, schedule = {}, cancel = {}),
                    fetch = fetch,
                )
                val bridge = MiniAppBridge(sandbox, app.id, host)
                val finished = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main.immediate) {
                    val runtime = MiniAppWebSession(context, installed, bridge, onLoading = { if (!it) finished.complete(Unit) })
                    session = runtime
                    // Give an off-screen page a viewport so layout/visible-content checks are meaningful.
                    runtime.webView.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                    runtime.webView.layout(0, 0, 1080, 1920)
                    runtime.load()
                    val loaded = withTimeoutOrNull(15_000) { finished.await(); true } ?: false
                    if (loaded) delay(2_000)
                    val visible = withTimeoutOrNull(1_000) {
                        val result = CompletableDeferred<Int?>()
                        runtime.webView.evaluateJavascript("""(function(){var b=document.body;if(!b)return 0;return (b.innerText||'').trim().length+b.querySelectorAll('img,svg,canvas,video').length;})()""") {
                            result.complete(it.toIntOrNull())
                        }
                        result.await()
                    }
                    MiniAppCheckReport(loaded, (bridge.pageErrors.toList() + runtime.resourceErrors.toList()).take(MiniAppBridge.MAX_PROBLEMS), runtime.console.toList(), bridge.failedCalls.toList(), visible)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { session?.close() }
            }
        }
    }

    internal fun copyInstallation(source: File, target: File) {
        val sourcePath = source.canonicalFile.toPath()
        source.walkTopDown().forEach { file ->
            // Never follow a link into another app or arbitrary private files.
            check(!Files.isSymbolicLink(file.toPath())) { "Mini app contains a symbolic link" }
            check(file.canonicalFile.toPath().startsWith(sourcePath))
            val destination = File(target, file.relativeTo(source).path)
            if (file.isDirectory) destination.mkdirs() else if (file.isFile) {
                destination.parentFile?.mkdirs()
                file.copyTo(destination)
            }
        }
    }
}

/** Establish ownership before dispatching allocation: prompt cancellation cannot lose its result. */
internal suspend fun <T> withCheckDirectory(
    parent: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend (File) -> T,
): T {
    var owned: File? = null
    try {
        val directory = withContext(dispatcher) {
            Files.createTempDirectory(parent.toPath(), "mini-app-check-").toFile().also { owned = it }
        }
        return block(directory)
    } finally {
        withContext(NonCancellable + dispatcher) { owned?.deleteRecursively() }
    }
}
