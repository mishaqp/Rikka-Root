package me.rerere.rikkahub.browser

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.WeakHashMap

/**
 * Token-cost optimisation pass — Readability.js bridge for browser_get_text.
 *
 * ## Attribution
 *
 * This file injects Mozilla's [Readability.js](https://github.com/mozilla/readability)
 * library (`assets/browser/readability.js`) into the page to extract main-article
 * content. Readability.js is licensed Apache-2.0 © Mozilla; the bundled copy is
 * pinned to commit `08be6b4bdb204dd333c9b7a0cfbc0e730b257252` (mozilla/readability,
 * main branch, 2025-11-15). License header is preserved at the top of the asset.
 *
 * The bundled Readability parser still needs a real browser DOM. The bounded
 * result/bridge path is also exercised offline in BrowserReadReliabilityTest using
 * the real extraction payload and a small DOM fixture in QuickJS.
 *
 * ## How injection works
 *
 * On every call we re-inject the library. Tracking a per-page sentinel via
 * `window.__rikkahubReadabilityLoaded` would be marginally cheaper, but page
 * navigation resets the JS context anyway — re-injecting a 90 KB string into the
 * already-JIT-compiled context is microseconds, and "always inject" avoids the
 * footgun where the sentinel survives but the function doesn't (e.g. CSP'd page,
 * SPA route swap that nukes globals).
 */
object ReadabilityRunner {

    private const val ASSET_PATH = "browser/readability.js"
    private const val PINNED_SHA = "08be6b4bdb204dd333c9b7a0cfbc0e730b257252"
    private const val MAX_READABILITY_ELEMENTS = 15_000

    /** Cached library text — read once from assets, reused for every injection. */
    @Volatile
    private var cachedLibrary: String? = null

    // A timeout stops waiting for the callback, not the parser in Chromium. Keep the
    // failed document out of subsequent article attempts until navigation/reload.
    private val timedOutDocuments = WeakHashMap<WebView, BrowserDocumentIdentity>()

    class ExtractionTimedOut(val documentUrl: String? = null) :
        RuntimeException("Извлечение статьи не завершилось вовремя.")

    /**
     * Inject Readability.js (if not already loaded in this WebView instance) and run
     * it against a clone of the live document. Returns the extracted plain text or
     * `null` if Readability decided the page wasn't article-shaped.
     *
     * Bounds the callback wait. JavaScript may continue running after timeout;
     * that document is skipped on subsequent extraction attempts.
     */
    suspend fun WebView.runReadability(timeoutMs: Long = 4_000L): String? =
        runReadabilityResult(timeoutMs = timeoutMs)?.text

    data class Extraction(val text: String, val totalChars: Int, val url: String)

    /** Clip inside JavaScript: clipping after the callback cannot protect its native bridge. */
    suspend fun WebView.runReadabilityResult(
        maxChars: Int = 64 * 1024,
        timeoutMs: Long = 4_000L,
    ): Extraction? {
        val limit = maxChars.coerceIn(100, 64 * 1024)
        val context = this.context.applicationContext ?: return null
        val library = loadLibrary(context) ?: return null
        val identity = extractionDocumentIdentity() ?: throw ExtractionTimedOut()
        synchronized(timedOutDocuments) {
            if (timedOutDocuments[this] == identity) return null
            timedOutDocuments.remove(this)
        }
        val raw = try {
            withTimeoutOrNull(timeoutMs) {
                // Inject the library, then run Readability against a cloned document.
                // Cloning is critical — Readability's `parse()` mutates the document it
                // operates on. Running against the live DOM would scrub the page from
                // under any subsequent browser_get_dom / browser_click call.
                val payload = """(function(){
                    try {
                        // Cloning and scoring a huge DOM (large GitHub diffs, listings) outlives the
                        // timeout and blocks the renderer for the raw fallback, so skip it.
                        if (document.getElementsByTagName && document.getElementsByTagName('*').length > $MAX_READABILITY_ELEMENTS) return JSON.stringify(null);
                        if (typeof Readability === 'undefined') { $library }
                        var doc = document.cloneNode(true);
                        var article = new Readability(doc).parse();
                        if (!article) return JSON.stringify(null);
                        var text = (article.textContent || '').replace(/\s+/g,' ').trim();
                        return JSON.stringify({
                            url: document.URL || '',
                            textContent: text.substring(0, $limit),
                            total_chars: text.length,
                            title: String(article.title || '').substring(0, 1024),
                            byline: String(article.byline || '').substring(0, 1024),
                            siteName: String(article.siteName || '').substring(0, 1024),
                            length: (article.textContent || '').length
                        });
                    } catch(e) { return JSON.stringify({error: String(e)}); }
                })()"""
                this@runReadabilityResult.evaluateJavascriptAsync(payload, timeoutMs)
            }
        } catch (cancelled: CancellationException) {
            synchronized(timedOutDocuments) { timedOutDocuments[this] = identity }
            throw cancelled
        }
        if (raw == null) {
            synchronized(timedOutDocuments) { timedOutDocuments[this] = identity }
            throw ExtractionTimedOut(identity.url)
        }
        return parseExtraction(raw)
    }

    private suspend fun WebView.extractionDocumentIdentity(): BrowserDocumentIdentity? {
        val raw = evaluateJavascriptAsync("""(function(){
            return JSON.stringify([document.URL || '', String(
                performance.timeOrigin || (performance.timing && performance.timing.navigationStart) || 0)]);
        })()""", 1_500L) ?: return null
        return runCatching {
            val outer = Json.parseToJsonElement(raw)
            val inner = if (outer is JsonPrimitive && outer.isString) outer.content else outer.toString()
            val fields = Json.parseToJsonElement(inner).jsonArray
            BrowserDocumentIdentity(fields[0].jsonPrimitive.content, fields[1].jsonPrimitive.content)
        }.getOrNull()
    }

    /**
     * Test hook: which SHA the runner is pinned to. Surfaced for logging / Doctor.
     * No public API around it — the constant exists so a future asset bump bumps
     * exactly one place.
     */
    fun pinnedSha(): String = PINNED_SHA

    /**
     * Read the bundled library from `assets/browser/readability.js`. Cached on the
     * first call; any IO failure logs and returns null so the calling tool can
     * fall back to selector-based extraction without throwing.
     */
    private fun loadLibrary(context: Context): String? {
        cachedLibrary?.let { return it }
        return runCatching {
            context.assets.open(ASSET_PATH).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
        }.onSuccess { cachedLibrary = it }
            .onFailure { android.util.Log.w("ReadabilityRunner", "asset load failed type=${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * Parse the JSON-encoded result the WebView returned. evaluateJavascript wraps
     * a JS string return in JSON quotes, so [raw] is a doubly-encoded string
     * (`"\"{...}\""`) that we unwrap once into the inner JSON object, then read the
     * `textContent` field. Returns null on `JSON.stringify(null)` (Readability
     * couldn't parse the page) and on any decode error.
     */
    private fun parseExtraction(raw: String?): Extraction? {
        if (raw == null || raw == "null" || raw == "\"null\"") return null
        return runCatching {
            val outer = Json.parseToJsonElement(raw)
            val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty()
                        else outer.toString()
            val element = Json.parseToJsonElement(inner)
            // The JS payload returned `null` directly (no article) — surface as null.
            if (element is JsonPrimitive && element.contentOrNull == null) return@runCatching null
            val obj = element.jsonObject
            // Error envelope from inside the JS — surface as null so the caller can
            // fall back to selector-based extraction. Logging the detail would be
            // noisy on every non-article page; skip.
            if (obj.containsKey("error")) return@runCatching null
            val text = obj["textContent"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return@runCatching null
            val total = obj["total_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceAtLeast(text.length) ?: text.length
            Extraction(text, total, obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.getOrNull()
    }
}
