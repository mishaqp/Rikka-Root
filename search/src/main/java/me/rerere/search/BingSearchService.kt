package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.util.Locale

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val query = params["query"]?.jsonPrimitive?.content ?: error("Укажите поисковый запрос в query.")
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Encoding", "gzip, deflate, sdch")
                .header("Accept-Charset", "utf-8")
                .header("Connection", "keep-alive")
                .referrer("https://www.bing.com/")
                .cookie("SRCHHPGUSR", "ULSR=1")
                .timeout(5000)
                .get()

            // 解析搜索结果
            val results = doc.select("li.b_algo").map { element ->
                val title = element.select("h2").text()
                val link = element.select("h2 > a").attr("href")
                val snippet = element.select(".b_caption p").text()
                SearchResultItem(
                    title = title,
                    url = link,
                    text = snippet
                )
            }.take(commonOptions.resultSize.coerceIn(1, 50))

            require(results.isNotEmpty()) {
                "Bing не вернул результатов. Измените запрос или выберите другой поисковый сервис в настройках."
            }

            Result.success(SearchResult(items = results))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val reason = when (error) {
                is HttpStatusException -> "Bing вернул ошибку HTTP ${error.statusCode}. Повторите поиск позже или выберите другой поисковый сервис."
                is InterruptedIOException -> "Bing не ответил вовремя. Проверьте подключение к интернету и повторите поиск."
                is UnknownHostException, is ConnectException -> "Не удалось подключиться к Bing. Проверьте подключение к интернету и повторите поиск."
                else -> "Не удалось выполнить поиск Bing из-за ошибки соединения. Повторите поиск или выберите другой поисковый сервис."
            }
            Result.failure(IOException(reason, error))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Bing не умеет читать страницы. Используйте браузер или другой поисковый сервис."))
    }
}
