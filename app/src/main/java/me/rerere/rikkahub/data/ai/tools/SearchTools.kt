package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    // Only some search services can read a page; otherwise point the model at the browser.
    val selectedOptions = settings.searchServices.getOrElse(
        index = settings.searchServiceSelected, defaultValue = { SearchServiceOptions.DEFAULT })
    val canScrape = SearchService.getService(selectedOptions).scrapingParameters(selectedOptions) != null
    val verifyTool = if (canScrape) "scrape_web" else "browser_open + browser_get_text (если доступны)"
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Найти в интернете актуальную или узкоспециальную информацию.
                    Используйте для последних новостей, текущих фактов и проверки утверждений.
                    Порядок результатов не доказывает их свежесть. Предпочитайте первоисточники и
                    проверяйте заголовок, URL, дату публикации и содержимое каждого результата.
                    Необязательные фильтры даты публикации и домена используйте только по смыслу вопроса.
                    Если дата или первоисточник отсутствуют либо источники противоречат друг другу,
                    уточните поисковый запрос или проверьте нужную страницу через $verifyTool.
                    Формулируйте точные ключевые слова; при необходимости выполните несколько поисков.
                    Сегодня ${LocalDate.now().toLocalString(true)}.

                    Формат ответа:
                    - retrievedAt — местное время получения, а не дата публикации
                    - items[].id — короткий ID; index, title, url, publishedDate (если есть), highlights (если есть), text
                    - images[] — URL изображений по запросу; массив может быть пустым

                    Ссылки на источники:
                    - После утверждения из результатов добавляйте `[citation,domain](id)`.
                    - Допускаются несколько ссылок; если результаты не цитируются, ссылки не нужны.

                    Изображения:
                    - Когда они помогают ответу, вставляйте Markdown `![](url)`.
                    - Добавляйте от 2 до 4 изображений только из images[]; не придумывайте и не меняйте URL.
                    - Обычно помещайте их в начале ответа; если подходящих изображений нет, не добавляйте их.

                    Пример:
                    Столица Франции — Париж. [citation,example.com](abc123)
                    Население — около 2,1 млн человек. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    localizedSearchParameters(service.parameters(options), options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Прочитать подробное содержимое страницы по URL.
                        Используйте, когда пользователь просит прочитать определённую страницу,
                        поисковой выдержки недостаточно или текущее утверждение нужно проверить по первоисточнику.
                        Для общих вопросов без такой просьбы чтение страницы обычно не требуется.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        localizedSearchParameters(service.scrapingParameters(options), options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        val payload = JsonInstantPretty.encodeToJsonElement(result).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

/** Translate only model-facing descriptions; SDK field names, constraints and execution stay intact. */
private fun localizedSearchParameters(schema: InputSchema?, options: SearchServiceOptions): InputSchema? = when (schema) {
    null -> null
    is InputSchema.Obj -> schema.copy(properties = JsonObject(schema.properties.mapValues { (name, value) ->
        val description = when (name) {
            "query" -> if (options is SearchServiceOptions.GrokOptions)
                "Вопрос для поиска; можно сформулировать на естественном языке."
                else "Поисковый запрос: ключевые слова для поиска информации."
            "url" -> if (options is SearchServiceOptions.DuckDuckGoOptions)
                "Абсолютный URL страницы по HTTP или HTTPS, содержимое которой нужно прочитать."
                else "URL страницы, содержимое которой нужно прочитать."
            "urls" -> "Массив URL страниц для чтения пользовательским скриптом поиска."
            "topic" -> "Тема поиска: general — общая, news — новости, finance — финансы."
            "type" -> "Режим поиска Exa: fast — быстрый, auto — сбалансированный по умолчанию, deep — обобщённый ответ со ссылками."
            "startPublishedDate" -> "Необязательная нижняя граница даты публикации в ISO-8601: результаты опубликованы после этой даты."
            "endPublishedDate" -> "Необязательная верхняя граница даты публикации в ISO-8601: результаты опубликованы до этой даты."
            "includeDomains" -> "Необязательный массив доменов, которые нужно включить в поиск."
            "excludeDomains" -> "Необязательный массив доменов, которые нужно исключить из поиска."
            "maxAgeHours" -> "Необязательный максимальный возраст загруженного содержимого в часах: целое число от -1 до 720. Указывайте, когда важна свежесть содержимого."
            "sources" -> "Необязательный массив источников Firecrawl: web или news; по умолчанию web."
            "categories" -> "Необязательный массив категорий Firecrawl: github или research; пустой массив означает отсутствие фильтра и используется по умолчанию."
            "onlyMainContent" -> "Читать только основное содержимое страницы; по умолчанию true."
            else -> null
        }
        if (description == null || value !is JsonObject) value
        else JsonObject(value + ("description" to JsonPrimitive(description)))
    }))
}
