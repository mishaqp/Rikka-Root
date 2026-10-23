package me.rerere.rikkahub.miniapps

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest

/** Android adaptation of Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_page.dart.
 * Each installation has a separate HTTPS origin; only its main frame receives the bridge.
 */
class MiniAppWebSession(
    context: Context,
    private val app: MiniApp,
    private val bridge: MiniAppBridge,
    private val onLoading: (Boolean) -> Unit = {},
    private val onExternalLink: (String) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private var documentGeneration = 0L
    private val originHost = "app-" + MessageDigest.getInstance("SHA-256")
        .digest(app.directory.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) } + ".miniapps.invalid"
    internal val origin = "https://$originHost"
    val console = mutableListOf<String>()
    val resourceErrors = mutableListOf<String>()
    val webView = WebView(context)
    private val listenerInstalled: Boolean

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            // Moru permits ordinary HTTP assets; the synthetic HTTPS origin must not change that contract.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        listenerInstalled = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (listenerInstalled) {
            WebViewCompat.addWebMessageListener(webView, "MoruBridge", setOf(origin)) { _, message, source, mainFrame, _ ->
                if (!closed && mainFrame && owns(source)) {
                    val generation = documentGeneration
                    val payload = message.data
                    if (payload != null) scope.launch { answer(payload, generation) }
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, MiniAppBridgeScript.source, setOf(origin))
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    record(console, "${if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "error" else "warn"}: ${message.message()}")
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (owns(request.url)) return false
                if (!request.isForMainFrame && permitsRemoteResource(request.url)) return false
                if (request.isForMainFrame) onExternalLink(request.url.toString())
                return true
            }
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (owns(Uri.parse(url))) return false
                onExternalLink(url)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                documentGeneration++
                onLoading(true)
            }
            override fun onPageFinished(view: WebView, url: String) { if (owns(Uri.parse(url))) onLoading(false) }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                record(resourceErrors, "${request.url}: HTTP ${response.statusCode}")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                record(resourceErrors, "${request.url}: ${error.description}")
            }
        }
    }

    // Keep API side effects, but never deliver an old document's reply into a new pending-ID table.
    internal suspend fun answer(message: String, generation: Long = documentGeneration) {
        val script = bridge.handle(message)
        if (!closed && generation == documentGeneration && owns(Uri.parse(webView.url ?: "")) && script != null) {
            webView.evaluateJavascript(script, null)
        }
    }

    internal fun owns(uri: Uri): Boolean = uri.scheme == "https" && uri.host == originHost && (uri.port == -1 || uri.port == 443)

    internal fun permitsRemoteResource(uri: Uri): Boolean =
        (uri.scheme == "https" || uri.scheme == "http") &&
            uri.host?.endsWith(".miniapps.invalid") == false

    internal fun intercept(uri: Uri): WebResourceResponse? = when {
        owns(uri) -> serve(uri)
        permitsRemoteResource(uri) -> null // Ordinary WebView resources; moru.fetch has its own manifest policy.
        else -> denied()
    }

    /** Confines decoded and canonical paths, including symlinks, to this installation's code. */
    internal fun serve(uri: Uri): WebResourceResponse {
        if (!owns(uri)) return denied()
        val path = uri.path.orEmpty().removePrefix("/")
        val root = app.codeDirectory.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return denied()
        val mime = when (file.extension.lowercase()) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), file.inputStream())
        } catch (_: Exception) { denied() }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun record(list: MutableList<String>, value: String) { synchronized(list) { if (list.size < MiniAppBridge.MAX_PROBLEMS) list.add(value) } }

    fun load() {
        check(!closed)
        if (!listenerInstalled) throw MiniAppException("unavailable", "This Android System WebView does not support secure mini-app messaging. Update Android System WebView.")
        webView.onResume()
        onLoading(true)
        val path = app.entry.split('/').joinToString("/") { Uri.encode(it) }
        webView.loadUrl("$origin/$path")
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        webView.stopLoading()
        webView.onPause()
        if (listenerInstalled) WebViewCompat.removeWebMessageListener(webView, "MoruBridge")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}
