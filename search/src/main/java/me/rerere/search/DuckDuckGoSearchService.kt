package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/** Keyless HTML search and page reading using the existing provider/tool interfaces. */
object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name = "DuckDuckGo"
    private const val MAX_BODY_BYTES = 1024L * 1024
    private const val MAX_CONTENT_CHARS = 32 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.duckduckgo_desc))
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema =
        schema("query", "Search keywords")

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema =
        schema("url", "Absolute HTTP or HTTPS URL to read")

    private fun schema(name: String, description: String) = InputSchema.Obj(
        properties = buildJsonObject {
            put(name, buildJsonObject {
                put("type", "string")
                put("description", description)
            })
        },
        required = listOf(name),
    )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        resultOf {
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            require(!query.isNullOrBlank()) { "query is required" }
            val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query).build()
            val page = fetch(Request.Builder().url(url).headersForPage().build())
            check(page.code == 200) { "DuckDuckGo search failed: HTTP ${page.code}; retry later" }
            val doc = Jsoup.parse(page.body, page.url)
            check(doc.select(".anomaly-modal, [class*=anomaly-modal]").isEmpty() &&
                !page.body.contains("confirm this search was made by a human", ignoreCase = true)
            ) { "DuckDuckGo returned an anti-bot challenge; retry later" }
            val items = doc.select("div.result").mapNotNull { row ->
                val anchor = row.selectFirst("a.result__a") ?: return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val link = resultUrl(anchor.attr("href")) ?: return@mapNotNull null
                SearchResult.SearchResultItem(
                    title = title,
                    url = link,
                    text = row.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
                )
            }.take(commonOptions.resultSize.coerceIn(1, 50))
            check(items.isNotEmpty() || doc.select(".no-results, #no-results").isNotEmpty()) {
                "DuckDuckGo returned unrecognized search markup; retry later"
            }
            SearchResult(items = items)
        }
    }

    private fun resultUrl(href: String): String? {
        if (href.isBlank()) return null
        val resolved = "https://duckduckgo.com/".toHttpUrl().resolve(href) ?: return null
        if (resolved.host in setOf("duckduckgo.com", "html.duckduckgo.com") && resolved.encodedPath == "/l/") {
            return resolved.queryParameter("uddg")?.toHttpUrlOrNull()?.toString()
        }
        return resolved.toString()
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions,
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        resultOf {
            val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()?.toHttpUrlOrNull()
            require(url != null) { "url must be an absolute HTTP or HTTPS URL" }
            val page = fetch(Request.Builder().url(url).headersForPage().build())
            check(page.code in 200..299) { "Page read failed: HTTP ${page.code}" }
            val item = if (page.contentType == "text/plain") {
                ScrapedResultUrl(page.url, page.body.take(MAX_CONTENT_CHARS))
            } else {
                require(page.contentType == null || page.contentType in setOf("text/html", "application/xhtml+xml")) {
                    "Page read requires HTML or plain text, received ${page.contentType}"
                }
                readablePage(page)
            }
            require(item.content.isNotBlank()) { "No readable page content found" }
            ScrapedResult(urls = listOf(item))
        }
    }

    private fun readablePage(page: FetchedPage): ScrapedResultUrl {
        val doc = Jsoup.parse(page.body, page.url)
        fun meta(selector: String) = doc.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
        val metadata = ScrapedResultMetadata(
            title = meta("meta[property=og:title]") ?: doc.title().takeIf { it.isNotBlank() },
            description = meta("meta[name=description]") ?: meta("meta[property=og:description]"),
            language = doc.selectFirst("html[lang]")?.attr("lang")?.takeIf { it.isNotBlank() },
            publishedDate = meta("meta[property=article:published_time]"),
        )
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
        val content = doc.selectFirst("article") ?: doc.selectFirst("main, [role=main]") ?: doc.body()
        content.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, tr").forEach {
            it.before(TextNode("\n"))
            it.after(TextNode("\n"))
        }
        val text = content.wholeText().lines().joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_CONTENT_CHARS)
        return ScrapedResultUrl(page.url, text, metadata)
    }

    private fun Request.Builder.headersForPage() =
        header("User-Agent", USER_AGENT).header("Accept", "text/html,text/plain,application/xhtml+xml")

    private data class FetchedPage(val code: Int, val url: String, val contentType: String?, val body: String)

    /** Keep cancellation attached until body consumption ends, and close every response. */
    private suspend fun fetch(request: Request): FetchedPage = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val page = response.use {
                        val body = it.body
                        require(body.contentLength() <= MAX_BODY_BYTES) { "Page exceeds the 1 MiB download limit" }
                        val buffer = Buffer()
                        val source = body.source()
                        while (source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1 - buffer.size)) != -1L) {
                            require(buffer.size <= MAX_BODY_BYTES) { "Page exceeds the 1 MiB download limit" }
                        }
                        val type = body.contentType()
                        FetchedPage(it.code, it.request.url.toString(), type?.let { "${it.type}/${it.subtype}" },
                            buffer.readString(type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(page)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
