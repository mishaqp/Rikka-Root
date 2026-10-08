package me.rerere.rikkahub.skills.js

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

private const val TAG = "JsSkillRunner"

/**
 * Phase 18 — runs a skill's JavaScript inside a hidden WebView and returns the result to
 * the LLM. Mirrors Google AI Edge Gallery's contract exactly so any skill written for
 * Gallery's format (with `window['ai_edge_gallery_get_result'](data, secret)`) works
 * verbatim in this app.
 *
 * Lifecycle: create one WebView per invocation, load the entry document from the skill's
 * per-skill virtual https origin (see [skillOriginHost]),
 * wait for `onPageFinished`, evaluate the trigger script, await the result via the
 * `AiEdgeGallery.onResultReady(json)` JS-bridge callback, then destroy the WebView.
 *
 * Per-call timeout: 60s wall-clock — matches Gallery's safety net at AgentChatScreen:246.
 *
 * Thread model: WebView is touchable only on the main thread. The runner launches all
 * WebView ops on `Dispatchers.Main` and suspends the calling coroutine on a
 * CompletableDeferred until the JS bridge fires.
 *
 * Result JSON shape (matches Gallery):
 * ```
 * {
 *   "result": "string text the LLM should see",
 *   "image": { "base64": "..." },                      // optional
 *   "webview": { "iframe": true, "url": "...", "aspectRatio": 1.333 }, // optional
 *   "error": "string, if anything failed"              // optional
 * }
 * ```
 */
class JsSkillRunner(private val context: Context) {

    sealed class Result {
        data class Ok(val parsed: ParsedResult) : Result()
        data class Err(val code: String, val detail: String) : Result()
    }

    /**
     * Decoded shape of the JS skill's return value. Any combination of [text], [imageBase64],
     * and [webviewUrl] may be present — Gallery's contract permits a skill to return both an
     * image AND a text summary. The runner exposes whatever the JS produced; the caller
     * decides how to fold it into the chat surface.
     */
    data class ParsedResult(
        val text: String? = null,
        val imageBase64: String? = null,
        val webviewUrl: String? = null,
        val webviewIframe: Boolean = true,
        val webviewAspectRatio: Float = 4f / 3f,
        val error: String? = null,
    )

    /**
     * Run [scriptFile] with [data] (and optional [secret] for skills that need an API key).
     * Returns either a [Result.Ok] with parsed return values or [Result.Err] with a stable
     * code the caller can surface to the LLM.
     *
     * Caller must ensure [scriptFile] is inside the skill's directory — JsSkillRunner does
     * NOT path-validate. The `run_js` tool factory does that via SkillManager.
     */
    suspend fun runScript(
        scriptFile: File,
        skillRootDir: File? = null,
        data: String,
        secret: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (!scriptFile.exists() || !scriptFile.isFile) {
            return Result.Err("script_not_found", "no JS skill script at ${scriptFile.absolutePath}")
        }
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            var webView: WebView? = null
            try {
                // Root the asset loader at the SKILL ROOT (not the script's immediate parent) so a
                // script in a subdirectory (e.g. scripts/index.html) can still fetch sibling
                // resources elsewhere in the skill (../assets/x, ../lib/y) — matching the skill
                // format. Falls back to the script's parent when the caller didn't supply a root.
                val rootDir = (skillRootDir ?: scriptFile.parentFile)
                    ?: return@withContext Result.Err("script_not_found", "skill script has no parent directory")
                // Path of the script relative to the skill root → the rest of the served URL, so
                // the WebView loads the same file regardless of how deep the script sits. Guard
                // against a script that doesn't resolve under the root (falls back to its name).
                val relPath = runCatching { scriptFile.relativeTo(rootDir).invariantSeparatorsPath }
                    .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }
                    ?: scriptFile.name
                // Serve the skill directory over a virtual https origin via WebViewAssetLoader
                // instead of loading it from file://. The asset loader confines reads to THIS
                // skill's subtree, so a malicious skill can no longer fetch arbitrary app-private
                // files (databases/, datastore) the way a file:// page with universal access could.
                //
                // InternalStoragePathHandler derives the response MIME from the file name, so an
                // entry document whose name isn't *.html (e.g. "index.htm" or extensionless) is
                // served as application/octet-stream and the WebView never parses it as HTML;
                // ai_edge_gallery_get_result is then never defined and the run dies with
                // script_timeout. Wrap the handler so ONLY the top-level entry relPath has its
                // response rewritten to text/html; every other resource/subresource keeps the
                // MIME the handler inferred (so scripts, images, CSS still load correctly).
                val storageHandler = WebViewAssetLoader.InternalStoragePathHandler(context, rootDir)
                val htmlEntryHandler = WebViewAssetLoader.PathHandler { path ->
                    val response = storageHandler.handle(path) ?: return@PathHandler null
                    if (path == relPath && response.mimeType != "text/html") {
                        WebResourceResponse(
                            "text/html",
                            response.encoding,
                            response.statusCode,
                            response.reasonPhrase,
                            response.responseHeaders,
                            response.data,
                        )
                    } else {
                        response
                    }
                }
                // Each skill is served from its OWN subdomain of the reserved asset domain so
                // DOM storage (localStorage/IndexedDB) is per-skill: on a single shared origin
                // any skill could read state another skill persisted. Subdomains of
                // androidplatform.net never resolve on the real network, same as the parent.
                val originHost = skillOriginHost(rootDir.name)
                val assetLoader = WebViewAssetLoader.Builder()
                    .setDomain(originHost)
                    .addPathHandler(SKILL_PATH, htmlEntryHandler)
                    .build()
                // A virtual origin alone does not isolate native browser cookies: remote
                // frames still use their own site origins. Separate native profiles are
                // required in addition to per-skill origins and an origin-bound bridge.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    return@withContext secureWebViewUnavailable()
                }
                val wv = WebView(context.applicationContext)
                webView = wv
                if (!SkillWebViewProfile.configure(wv, rootDir)) {
                    return@withContext secureWebViewUnavailable()
                }
                wv.apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    // File access is OFF: the page is now served from https via the asset loader,
                    // not file://, so these flags are no longer needed and would only re-open the
                    // app-private-file exfiltration path.
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.domStorageEnabled = true           // localStorage for skills that store state
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // COMPATIBILITY (not ALWAYS_ALLOW): on the virtual https origin the WebView
                    // applies standard mixed-content rules. Skills hitting genuinely cross-origin
                    // https APIs (query-wikipedia → fetch) or CDN libs (qr-code → qrcodejs) still
                    // work via normal CORS; skills that relied on universal-access to bypass CORS
                    // must use a CORS-enabled endpoint.
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
                val bridge = BridgeImpl(deferred)
                WebViewCompat.addWebMessageListener(wv, JS_INTERFACE_NAME, setOf("https://$originHost")) {
                        _, message, sourceOrigin, isMainFrame, _ ->
                    if (isSkillBridgeOriginAllowed(sourceOrigin.toString(), isMainFrame, originHost)) {
                        val result = skillBridgeResultText(message)
                        bridge.onResultReady(result ?: "{\"error\":\"Навык отправил результат неподдерживаемого типа: ожидается JSON-строка.\"}")
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d(TAG, "JS console: level=${it.messageLevel()}, line=${it.lineNumber()}")
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                        // A navigation may not move the Android JS bridge/secret to another origin.
                        if (request.isForMainFrame && !isSkillOrigin(request.url.toString(), originHost)) {
                            deferred.complete("{\"error\":\"Навигация за пределы навыка запрещена.\"}")
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val finished = url?.let(android.net.Uri::parse)
                        if (url == null || !isSkillOrigin(url, originHost) || finished?.path != SKILL_PATH + relPath) return
                        Log.d(TAG, "JS skill page ready")
                        // The trigger waits up to 10s for the page's
                        // `ai_edge_gallery_get_result` to be defined, then invokes it and
                        // hands the result back via the JS bridge. JSONObject.quote escapes
                        // the data + secret strings safely.
                        val safeData = JSONObject.quote(data)
                        val safeSecret = JSONObject.quote(secret)
                        val script = """
                            (async function() {
                                // Preserve Gallery's callback name while native messages
                                // are accepted only from this skill's HTTPS main frame.
                                var nativeBridge = $JS_INTERFACE_NAME;
                                nativeBridge.onResultReady = function(json) {
                                    nativeBridge.postMessage(json);
                                };
                                var startTs = Date.now();
                                while (true) {
                                    if (typeof ai_edge_gallery_get_result === 'function') break;
                                    await new Promise(r => setTimeout(r, 100));
                                    if (Date.now() - startTs > 10000) {
                                        $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "ai_edge_gallery_get_result not defined within 10s"}));
                                        return;
                                    }
                                }
                                try {
                                    var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                                    $JS_INTERFACE_NAME.onResultReady(typeof result === 'string' ? result : JSON.stringify(result));
                                } catch (e) {
                                    $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "JS threw: " + (e && e.message ? e.message : String(e))}));
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
                // https://<skill-slug>-<hash>.appassets.androidplatform.net/skill/<relPath> —
                // intercepted by the asset loader and served from the skill root; never touches
                // the network.
                val skillUrl = "https://$originHost$SKILL_PATH$relPath"
                Log.d(TAG, "JS skill load: input_bytes=${data.toByteArray(Charsets.UTF_8).size}")
                wv.loadUrl(skillUrl)

                // Suspend the calling coroutine until the bridge fires or timeout. We let the
                // 10s within-script wait above handle "page loaded but JS missing"; the outer
                // timeout is a hard wall on infinite loops or unresponsive scripts.
                val resultJson = withTimeout(timeoutMs) { deferred.await() }
                Result.Ok(redactSkillResultSecret(parseResultJson(resultJson), secret))
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "JS skill execution timed out after ${timeoutMs}ms")
                Result.Err("script_timeout",
                    "JS skill execution exceeded ${timeoutMs}ms — check for infinite loops or unresponsive network calls")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "JS skill execution failed: ${t::class.simpleName}")
                Result.Err("script_failed", redactSkillSecret("${t::class.simpleName}: ${t.message.orEmpty()}", secret))
            } finally {
                runCatching { webView?.destroy() }
                    .onFailure { Log.w(TAG, "WebView.destroy failed: ${it::class.simpleName}") }
            }
        }
    }

    private fun parseResultJson(json: String): ParsedResult {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return ParsedResult(error = "JS skill returned non-JSON: $json")
        val errorRaw = obj["error"]?.jsonPrimitive?.contentOrNull
        val text = obj["result"]?.jsonPrimitive?.contentOrNull
        val imageBase64 = (obj["image"] as? JsonObject)?.get("base64")?.jsonPrimitive?.contentOrNull
        val webview = obj["webview"] as? JsonObject
        val webviewUrl = webview?.get("url")?.jsonPrimitive?.contentOrNull
        val webviewIframe = (webview?.get("iframe") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true
        val aspectRatio = webview?.get("aspectRatio")?.let {
            runCatching { it.toString().toFloat() }.getOrNull()
        } ?: (4f / 3f)
        return ParsedResult(
            text = text,
            imageBase64 = imageBase64,
            webviewUrl = webviewUrl,
            webviewIframe = webviewIframe,
            webviewAspectRatio = aspectRatio,
            error = errorRaw,
        )
    }

    /** Only the origin-bound main-frame WebMessageListener may call this result callback. */
    private class BridgeImpl(private val target: CompletableDeferred<String>) {
        fun onResultReady(json: String) {
            Log.d(TAG, "JS skill result: bytes=${json.toByteArray(Charsets.UTF_8).size}")
            target.complete(json)
        }
    }

    private fun secureWebViewUnavailable(): Result.Err = Result.Err(
        "secure_webview_unavailable",
        "Для безопасного запуска JS-навыков обновите Android System WebView или Chrome: " +
            "нужны отдельные профили WebView и защищённый канал сообщений. " +
            "Общий профиль браузера не используется.",
    )

    companion object {
        private const val JS_INTERFACE_NAME = "AiEdgeGallery"
        // WebViewAssetLoader's default reserved domain; never resolves on the real network.
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val SKILL_PATH = "/skill/"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 5 * 60_000L
        const val MAX_DATA_LENGTH = 64 * 1024  // 64KB cap on the data payload

        /**
         * Per-skill virtual origin host: `<slug>-<8 hex>.appassets.androidplatform.net`.
         * Deterministic per skill-directory name so a skill keeps its DOM storage across runs;
         * the hash keeps hosts distinct when two names sanitize to the same slug; the slug
         * keeps URLs readable in logs. Label stays within DNS limits (24 + 1 + 8 chars).
         */
        fun skillOriginHost(skillDirName: String): String {
            val slug = skillDirName.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(24)
                .trimEnd('-')
                .ifEmpty { "skill" }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(skillDirName.toByteArray(Charsets.UTF_8))
                .take(4)
                .joinToString("") { "%02x".format(it) }
            return "$slug-$hash.$ASSET_DOMAIN"
        }
    }
}

/** Unlike JavascriptInterface, the result bridge must reject messages from every iframe. */
internal fun isSkillBridgeOriginAllowed(sourceOrigin: String, isMainFrame: Boolean, originHost: String): Boolean =
    isMainFrame && isSkillOrigin(sourceOrigin, originHost)

/** getData() throws for ArrayBuffer, so untrusted messages must be type-checked first. */
internal fun skillBridgeResultText(message: WebMessageCompat): String? =
    if (message.type == WebMessageCompat.TYPE_STRING) message.data else null

private fun isSkillOrigin(url: String, originHost: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(originHost, ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null
}.getOrDefault(false)

/**
 * Persist the decoded image to app cache, return file:// URL the chat renderer can show.
 * Matches the existing UIMessagePart.Image(url) shape so the chat surface needs no changes.
 */
fun decodeBase64ImageToCacheFile(context: Context, base64: String): File? = runCatching {
    val cleanBase64 = base64.substringAfter(",")  // strip data:image/...;base64, prefix if any
    val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
    val outDir = File(context.cacheDir, "skill_results").also { it.mkdirs() }
    val out = File(outDir, "${UUID.randomUUID()}.png")
    out.writeBytes(bytes)
    out
}.getOrNull()

/** Never return a configured skill secret to chat/Room or export it in viewer metadata. */
internal fun redactSkillSecret(value: String, secret: String): String =
    if (secret.isEmpty()) value else value.replace(secret, "[redacted-skill-secret]")

internal fun redactSkillResultSecret(result: JsSkillRunner.ParsedResult, secret: String): JsSkillRunner.ParsedResult =
    result.copy(
        text = result.text?.let { redactSkillSecret(it, secret) },
        error = result.error?.let { redactSkillSecret(it, secret) },
        webviewUrl = result.webviewUrl?.let { redactSkillSecret(it, secret) },
        imageBase64 = result.imageBase64?.takeUnless { secret.isNotEmpty() && it.contains(secret) },
    )
