package me.rerere.rikkahub.browser

import android.app.Application
import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A scripted WebView models native callback loss/navigation without a network. The real
 * tool, coroutine bridge and result decoder run unchanged. The extraction payload itself
 * runs in QuickJS against a small DOM fixture; assertions therefore check the actual JS
 * character limit before crossing the WebView callback, rather than trusting a mock to clip.
 * Mozilla's DOM-dependent parser alone is replaced by a fixture article producer; its
 * production wrapper, normalization, clipping, JSON encoding and native callback remain real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BrowserReadReliabilityTest {
    private lateinit var view: ScriptedWebView

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        view = ScriptedWebView(RuntimeEnvironment.getApplication())
        BrowserController.bindForeground(view, "browser-test")
    }

    @After fun tearDown() {
        BrowserController.unbind(view)
        BrowserController.clearTaskWindow()
        view.close()
        view.destroy()
        Dispatchers.resetMain()
    }

    private suspend fun execute(name: String, args: String = "{}"): JsonObject {
        val tool = requireNotNull(createBrowserTool(name, view.context,
            ToolInvocationContext(callerConversationId = "browser-test")))
        val parts = tool.execute(Json.parseToJsonElement(args))
        return Json.parseToJsonElement((parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `text DOM and links wait for the current document before extracting`() = runTest {
        for (name in listOf(BrowserToolDefaults.GET_TEXT, BrowserToolDefaults.GET_DOM, BrowserToolDefaults.GET_LINKS)) {
            val loadedAt = testScheduler.currentTime + 600L
            view.ready = { testScheduler.currentTime >= loadedAt }
            view.pageText = "Страница загрузилась"
            val result = execute(name, """{"extract_mode":"raw"}""")
            assertFalse("$name read the loading document: $result", result.containsKey("error"))
            assertTrue("$name returned before load completion", testScheduler.currentTime >= loadedAt)
        }
    }

    @Test fun `a lost read callback during navigation is retried without reopening the page`() = runTest {
        view.pageText = "Результат после перехода"
        view.lostReadCallbacks = 1
        val result = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(view.pageText, result["text"]?.jsonPrimitive?.content)
        assertEquals(0, view.navigationCount)
        assertSame(view, BrowserController.activeWebView())
    }

    @Test fun `missing bridge result remains a failure after click instead of empty success`() = runTest {
        view.pageText = "До нажатия"
        view.lostReadCallbacks = Int.MAX_VALUE
        val result = execute(BrowserToolDefaults.CLICK_AND_READ,
            """{"selector":"#continue","extract_mode":"raw"}""")
        assertTrue("Read error was converted to successful empty text: $result", result.containsKey("error"))
        assertFalse(result["success"]?.jsonPrimitive?.booleanOrNull == true)
        val explanation = result["message"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(result.toString(), explanation.contains("страниц", ignoreCase = true))
        assertEquals("The click must never be retried with the read", 1, view.clickCount)
        assertSame(view, BrowserController.activeWebView())
    }

    @Test fun `large click read is clipped in JavaScript before native callback and reports full length`() = runTest {
        view.pageText = "Ж".repeat(300_000)
        view.maxCallbackChars = 80_000
        val result = execute(BrowserToolDefaults.CLICK_AND_READ,
            """{"selector":"#continue","extract_mode":"raw","max_chars":1024}""")
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("Ж".repeat(1024), result["text"]?.jsonPrimitive?.content)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1024, result["shown_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(300_000, result["total_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals("Показано 1024 из 300000 символов.", result["message"]?.jsonPrimitive?.content)
        assertEquals(1, view.clickCount)
    }

    @Test fun `ordinary text and DOM truncation show how much content was omitted`() = runTest {
        view.pageText = "x".repeat(20_000)
        for ((name, field) in listOf(BrowserToolDefaults.GET_TEXT to "text", BrowserToolDefaults.GET_DOM to "html")) {
            val result = execute(name, """{"extract_mode":"raw","max_chars":1000}""")
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(1000, result[field]?.jsonPrimitive?.content?.length)
            assertEquals(1000, result["shown_chars"]?.jsonPrimitive?.intOrNull)
            assertTrue(result.toString(), (result["total_chars"]?.jsonPrimitive?.intOrNull ?: 0) >= 20_000)
            assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content?.startsWith("Показано 1000 из ") == true)
        }
    }

    @Test fun `readability text is clipped before the bridge while retaining its full character count`() = runTest {
        view.articleText = "Статья ".repeat(50_000)
        view.maxCallbackChars = 80_000
        val result = execute(BrowserToolDefaults.GET_TEXT, """{"max_chars":1000}""")
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(view.articleText!!.take(1000), result["text"]?.jsonPrimitive?.content)
        assertEquals("readability", result["extract_mode"]?.jsonPrimitive?.content)
        assertEquals(1000, result["shown_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(view.articleText!!.trim().length, result["total_chars"]?.jsonPrimitive?.intOrNull)
        assertTrue(result["truncated"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test fun `a failed read keeps the same session usable for the next request`() = runTest {
        view.lostReadCallbacks = Int.MAX_VALUE
        val failure = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertTrue(failure.toString(), failure.containsKey("error"))
        assertEquals("js_no_result", failure["error"]?.jsonPrimitive?.content)
        view.lostReadCallbacks = 0
        view.pageText = "Повтор сработал"
        val retry = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertEquals(view.pageText, retry["text"]?.jsonPrimitive?.content)
        assertSame(view, BrowserController.activeWebView())
        assertEquals(0, view.navigationCount)
    }

    @Test fun `open waits for the new document rather than complete from the previous page`() = runTest {
        view.pageUrl = { if (testScheduler.currentTime >= 400L) "https://example.test/releases/latest" else "https://example.test/old" }
        view.documentOrigin = { if (testScheduler.currentTime >= 400L) 2000 else 1000 }
        view.ready = { testScheduler.currentTime < 200L || testScheduler.currentTime >= 600L }
        val result = execute(BrowserToolDefaults.OPEN, """{"url":"https://example.test/releases"}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("https://example.test/releases/latest", result["current_url"]?.jsonPrimitive?.content)
        assertTrue("The old complete document was reported as the new page", testScheduler.currentTime >= 600L)
        assertEquals(1, view.navigationCount)
    }

    @Test fun `reload of the same URL waits for a new JavaScript document context`() = runTest {
        view.documentOrigin = { if (testScheduler.currentTime >= 400L) 2000 else 1000 }
        view.ready = { testScheduler.currentTime < 200L || testScheduler.currentTime >= 600L }
        val result = execute(BrowserToolDefaults.OPEN, """{"url":"https://example.test/releases"}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertTrue("Same-URL navigation reused the previous completed document", testScheduler.currentTime >= 600L)
        assertEquals(1, view.navigationCount)
    }

    @Test fun `read click and read again never returns the previous page while navigation is queued`() = runTest {
        var clickedAt: Long? = null
        view.pageTextAt = { if (clickedAt != null && testScheduler.currentTime - clickedAt!! >= 600L) "Новая страница" else "Старая страница" }
        view.onClick = { clickedAt = testScheduler.currentTime }
        view.ready = { clickedAt == null || testScheduler.currentTime - clickedAt!! < 200L || testScheduler.currentTime - clickedAt!! >= 600L }
        assertEquals("Старая страница", execute(BrowserToolDefaults.GET_TEXT,
            """{"extract_mode":"raw"}""")["text"]?.jsonPrimitive?.content)
        val click = execute(BrowserToolDefaults.CLICK, """{"selector":"#continue","full":true}""")
        assertEquals(true, click["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Новая страница", execute(BrowserToolDefaults.GET_TEXT,
            """{"extract_mode":"raw"}""")["text"]?.jsonPrimitive?.content)
        assertEquals(1, view.clickCount)
        assertSame(view, BrowserController.activeWebView())
    }

    @Test fun `default diff clips its snapshots before the callback and explains the unexamined remainder`() = runTest {
        view.pageText = "x".repeat(300_000)
        view.maxCallbackChars = 80_000
        val result = execute(BrowserToolDefaults.CLICK_AND_READ, """{"selector":"#continue"}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        val diff = requireNotNull(result["diff"]).jsonObject
        assertEquals(result.toString(), true, diff["partial"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, diff["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(65536, diff["shown_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(300_000, diff["total_chars"]?.jsonPrimitive?.intOrNull)
        assertTrue(result.toString(), result["warning"]?.jsonPrimitive?.content?.contains("65536 из 300000") == true)
        assertTrue(result.toString(), result["warning"]?.jsonPrimitive?.content?.contains("не проверена") == true)
        assertEquals(1, view.clickCount)
    }

    @Test fun `lost default diff snapshots warn that comparison failed without retrying the action`() = runTest {
        view.lostReadCallbacks = Int.MAX_VALUE
        val result = execute(BrowserToolDefaults.CLICK, """{"selector":"#continue"}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        val diff = requireNotNull(result["diff"]).jsonObject
        assertEquals(result.toString(), true, diff["partial"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(result.toString(), result["warning"]?.jsonPrimitive?.content?.contains("Не удалось сравнить") == true)
        assertEquals(1, view.clickCount)
    }

    @Test fun `a readable loading page is extracted within a ten second budget without duplicate readiness waits`() = runTest {
        val oldBudget = BrowserController.perToolTimeoutMs
        try {
            BrowserController.perToolTimeoutMs = 10_000L
            view.ready = { false }
            view.readsWhileLoading = true
            view.pageText = "Текст доступен, завис только внешний ресурс"
            for (args in listOf("""{"extract_mode":"raw"}""", """{"selector":"main"}""")) {
                val result = execute(BrowserToolDefaults.GET_TEXT, args)
                assertFalse(result.toString(), result.containsKey("error"))
                assertEquals(view.pageText, result["text"]?.jsonPrimitive?.content)
            }
        } finally {
            BrowserController.perToolTimeoutMs = oldBudget
        }
    }

    @Test fun `click read shares its completed readiness budget with text extraction`() = runTest {
        val oldBudget = BrowserController.perToolTimeoutMs
        try {
            BrowserController.perToolTimeoutMs = 10_000L
            view.ready = { false }
            view.readsWhileLoading = true
            view.pageText = "Текст после нажатия доступен"
            val result = execute(BrowserToolDefaults.CLICK_AND_READ,
                """{"selector":"#continue","extract_mode":"raw"}""")
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(view.pageText, result["text"]?.jsonPrimitive?.content)
            assertEquals(1, view.clickCount)
        } finally {
            BrowserController.perToolTimeoutMs = oldBudget
        }
    }

    @Test fun `unneeded enormous article metadata cannot overflow an otherwise bounded text callback`() = runTest {
        view.articleText = "Содержимое статьи ".repeat(100)
        view.articleMetadata = "M".repeat(150_000)
        view.maxCallbackChars = 80_000
        val result = execute(BrowserToolDefaults.GET_TEXT, """{"max_chars":1000}""")
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("readability", result["extract_mode"]?.jsonPrimitive?.content)
        assertEquals(view.articleText!!.take(1000), result["text"]?.jsonPrimitive?.content)
    }

    @Test fun `open still rejects the old page if its preliminary document identity callback was lost`() = runTest {
        view.lostIdentityCallbacks = 1
        view.pageUrl = { if (testScheduler.currentTime >= 400L) "https://example.test/releases/latest" else "https://example.test/old" }
        view.documentOrigin = { if (testScheduler.currentTime >= 400L) 2000 else 1000 }
        // Keep the previous readyState stable long enough to expose the missing-identity path.
        view.ready = { testScheduler.currentTime < 400L || testScheduler.currentTime >= 600L }
        val result = execute(BrowserToolDefaults.OPEN, """{"url":"https://example.test/releases"}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("https://example.test/releases/latest", result["current_url"]?.jsonPrimitive?.content)
        assertTrue(testScheduler.currentTime >= 600L)
        assertEquals(1, view.navigationCount)
    }

    @Test fun `open reports a page that never finished loading instead of success`() = runTest {
        view.ready = { false }
        val result = execute(BrowserToolDefaults.OPEN, """{"url":"https://example.test/slow"}""")
        assertEquals(result.toString(), false, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("page_load_timeout", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `reads report the document url so a stale page is visible`() = runTest {
        val result = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertEquals("https://example.test/releases", result["url"]?.jsonPrimitive?.content)
    }

    private suspend fun assertReadDocumentUrl(name: String, args: String = "{}") {
        view.pageUrl = { "https://example.test/pending-navigation" }
        view.documentUrl = { "https://example.test/still-visible" }
        view.pageText = "Текст прежнего документа"
        view.articleText = "Статья прежнего документа ".repeat(20)
        val result = execute(name, args)
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals("A native URL update must not relabel the previous document's content",
            "https://example.test/still-visible", result["url"]?.jsonPrimitive?.content)
    }

    @Test fun `raw text identifies its document even when native URL has already changed`() = runTest {
        assertReadDocumentUrl(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
    }

    @Test fun `selector text identifies its document even when native URL has already changed`() = runTest {
        assertReadDocumentUrl(BrowserToolDefaults.GET_TEXT, """{"selector":"main"}""")
    }

    @Test fun `readability text identifies its document even when native URL has already changed`() = runTest {
        assertReadDocumentUrl(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"readability"}""")
    }

    @Test fun `DOM identifies its document even when native URL has already changed`() = runTest {
        assertReadDocumentUrl(BrowserToolDefaults.GET_DOM)
    }

    @Test fun `links identify their document even when native URL has already changed`() = runTest {
        assertReadDocumentUrl(BrowserToolDefaults.GET_LINKS)
    }

    @Test fun `timed out readability does not queue raw extraction behind an unresponsive parser`() = runTest {
        view.dropReadabilityCallbacks = true
        val result = execute(BrowserToolDefaults.GET_TEXT)
        assertTrue("A parser timeout must remain visible: $result", result.containsKey("error"))
        assertEquals("The still-running parser must not receive a queued fallback", 0, view.rawReadRequests)
        assertEquals(1, view.readabilityInvocations)
        assertEquals(0, view.navigationCount)
        assertSame(view, BrowserController.activeWebView())
    }

    @Test fun `auto extraction skips a timed out parser for the same document and retries on a new document`() = runTest {
        view.dropReadabilityCallbacks = true
        execute(BrowserToolDefaults.GET_TEXT)
        assertEquals(1, view.readabilityInvocations)
        view.dropReadabilityCallbacks = false
        view.articleText = "Восстановившаяся статья ".repeat(20)
        view.pageText = "Доступный обычный текст"
        val recovered = execute(BrowserToolDefaults.GET_TEXT)
        assertFalse(recovered.toString(), recovered.containsKey("error"))
        assertEquals(view.pageText, recovered["text"]?.jsonPrimitive?.content)
        assertEquals("Do not rerun the parser which timed out in this document", 1, view.readabilityInvocations)
        // Reloading the same URL creates a new document and must allow article extraction again.
        view.documentOrigin = { 2000 }
        val reloaded = execute(BrowserToolDefaults.GET_TEXT)
        assertEquals("readability", reloaded["extract_mode"]?.jsonPrimitive?.content)
        assertEquals(2, view.readabilityInvocations)
        assertEquals(0, view.navigationCount)
    }

    @Test fun `XML without body is read from its root by text DOM and links tools`() = runTest {
        view.hasBody = false
        view.pageText = "Версия 42 из Atom"
        view.outerHtml = "<feed><entry>Версия 42 из Atom</entry></feed>"
        view.anchorCount = 1
        val text = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertEquals(view.pageText, text["text"]?.jsonPrimitive?.content)
        val dom = execute(BrowserToolDefaults.GET_DOM)
        assertEquals(view.outerHtml, dom["html"]?.jsonPrimitive?.content)
        val links = execute(BrowserToolDefaults.GET_LINKS)
        assertEquals(1, links["count"]?.jsonPrimitive?.intOrNull)
        assertFalse(links.toString(), links.containsKey("error"))
    }

    @Test fun `malformed bridge payload exposes only a short preview and logs exception type`() = runTest {
        view.malformedReadPayload = "x".repeat(70_000)
        val result = execute(BrowserToolDefaults.GET_TEXT, """{"extract_mode":"raw"}""")
        assertEquals("js_parse_failed", result["error"]?.jsonPrimitive?.content)
        assertFalse(result.toString(), result.containsKey("raw"))
        assertEquals("x".repeat(300), result["raw_preview"]?.jsonPrimitive?.content)
        assertTrue("The diagnostic must retain the error type and original size",
            ShadowLog.getLogsForTag("BrowserTools").any {
                Regex("js_parse_failed: [A-Za-z]+Exception, 70000 chars").containsMatchIn(it.msg)
            })
    }

    @Test fun `Enter in an input submits its associated form once`() = runTest {
        val result = execute(BrowserToolDefaults.PRESS_KEY, """{"key":"Enter","full":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, result["submitted"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, view.submitCount)
        assertEquals(view.pageUrl(), result["current_url"]?.jsonPrimitive?.content)
    }

    @Test fun `Enter in textarea does not submit the associated form`() = runTest {
        view.activeTagName = "TEXTAREA"
        val result = execute(BrowserToolDefaults.PRESS_KEY, """{"key":"Enter","full":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(result["submitted"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(0, view.submitCount)
    }

    @Test fun `cancelled Enter keydown does not submit a form`() = runTest {
        view.cancelKeydown = true
        val result = execute(BrowserToolDefaults.PRESS_KEY, """{"key":"Enter","full":true}""")
        assertEquals(true, result["success"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(result["submitted"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(0, view.submitCount)
    }

    @Test fun `missing select value preserves selection and dispatches no change`() = runTest {
        view.activeTagName = "SELECT"
        val result = execute(BrowserToolDefaults.SELECT, """{"selector":"#pick","value":"missing","full":true}""")
        assertEquals("option_not_found", result["error"]?.jsonPrimitive?.content)
        assertFalse(result["success"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals("existing", view.lastSelectedValue)
        assertEquals(0, view.changeCount)
        assertTrue(result["options"].toString().contains("existing"))
    }

    @Test fun `links report the full count when only the first hundred are returned`() = runTest {
        view.anchorCount = 101
        val result = execute(BrowserToolDefaults.GET_LINKS)
        assertEquals(100, result["count"]?.jsonPrimitive?.intOrNull)
        assertEquals(101, result["total"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(100, result["links"]?.let { (it as kotlinx.serialization.json.JsonArray).size })
    }

    @Test fun `ready state timeout includes an unresponsive JavaScript callback`() = runTest {
        view.dropAllCallbacks = true
        assertFalse(view.awaitReadyState(1000L))
        assertEquals("An individual 1500ms probe must not overrun the whole 1000ms wait", 1000L, testScheduler.currentTime)
    }

    private class ScriptedWebView(context: Context) : WebView(context), AutoCloseable {
        private val js = QuickJs.create(Dispatchers.Unconfined)
        private val bundledParser by lazy { context.assets.open("browser/readability.js").bufferedReader().use { it.readText() } }
        var pageText = "Готово"
        var pageTextAt: () -> String = { pageText }
        var onClick: () -> Unit = {}
        var pageUrl: () -> String = { "https://example.test/releases" }
        var documentUrl: () -> String = { pageUrl() }
        var documentOrigin: () -> Int = { 1000 }
        var articleText: String? = null
        var articleMetadata = ""
        var readsWhileLoading = false
        var ready: () -> Boolean = { true }
        var lostReadCallbacks = 0
        var lostIdentityCallbacks = 0
        var dropAllCallbacks = false
        var dropReadabilityCallbacks = false
        var readabilityInvocations = 0
        var rawReadRequests = 0
        var hasBody = true
        var outerHtml: String? = null
        var anchorCount = 0
        var malformedReadPayload: String? = null
        var activeTagName = "INPUT"
        var cancelKeydown = false
        var submitCount = 0
        var changeCount = 0
        var lastSelectedValue = "existing"
        var maxCallbackChars = Int.MAX_VALUE
        var navigationCount = 0
        var clickCount = 0

        override fun getUrl(): String = pageUrl()
        override fun getTitle(): String = "Локальная страница"
        override fun loadUrl(url: String) { navigationCount++ }

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            if (dropAllCallbacks) return
            val isReadability = script.contains("new Readability(doc).parse()")
            if (isReadability) {
                readabilityInvocations++
                if (dropReadabilityCallbacks) return
            }
            if (script.contains("JSON.stringify([document.URL") && lostIdentityCallbacks > 0) {
                lostIdentityCallbacks--
                resultCallback?.onReceiveValue("null")
                return
            }
            if (script.contains(".click()")) { clickCount++; onClick() }
            val isRead = (script.contains("querySelector") || script.contains("document.body.innerText")) &&
                !script.contains(".click()")
            if (isRead && !isReadability) rawReadRequests++
            if (isRead && !isReadability && malformedReadPayload != null) {
                resultCallback?.onReceiveValue(malformedReadPayload)
                return
            }
            if (isRead && lostReadCallbacks > 0) {
                lostReadCallbacks--
                resultCallback?.onReceiveValue("null")
                return
            }
            if (isRead && !ready() && !readsWhileLoading) {
                resultCallback?.onReceiveValue(JsonPrimitive("""{"error":"js_failed","detail":"loading document replaced"}""").toString())
                return
            }
            val text = JsonPrimitive(pageTextAt()).toString()
            val article = JsonPrimitive(articleText).toString()
            val fixture = """
                var fixtureSubmitCount = 0, fixtureChangeCount = 0;
                function KeyboardEvent(type, options) { this.type = type; this.key = options.key; }
                function Event(type) { this.type = type; }
                var anchors = Array.from({length:$anchorCount}, function(_, i) {
                    return {href:'https://example.test/link/'+i,innerText:'Link '+i};
                });
                var el = {innerText:$text,textContent:$text,outerHTML:${JsonPrimitive(outerHtml ?: "<main>${pageTextAt()}</main>")},
                    tagName:${JsonPrimitive(activeTagName)},value:'existing',options:[{value:'existing'}],
                    form:{requestSubmit:function(){fixtureSubmitCount++;},submit:function(){fixtureSubmitCount++;}},
                    dispatchEvent:function(event){
                        if(event.type === 'change') fixtureChangeCount++;
                        return !(event.type === 'keydown' && $cancelKeydown);
                    },
                    scrollIntoView:function(){},click:function(){},querySelectorAll:function(selector){return selector === 'a[href]' ? anchors : [];},
                    cloneNode:function(){return this;}};
                var performance = {timeOrigin:${documentOrigin()},timing:{navigationStart:${documentOrigin()}}};
                var document = {URL:${JsonPrimitive(documentUrl())},readyState:${JsonPrimitive(if (ready()) "complete" else "loading")},
                    body:${if (hasBody) "el" else "null"},documentElement:el,activeElement:el,
                    querySelector:function(selector){return selector === 'body' ? this.body : el;},cloneNode:function(){return this;}};
                var fixtureArticle = $article;
                var fixtureMetadata = ${JsonPrimitive(articleMetadata)};
            """.trimIndent()
            val executable = if (script.contains("new Readability(doc).parse()")) {
                script.replace(bundledParser, "function Readability(){this.parse=function(){return fixtureArticle === null ? null : {textContent:fixtureArticle,title:fixtureMetadata,byline:fixtureMetadata,siteName:fixtureMetadata};};}")
            } else script
            val returned = runBlocking { js.evaluate<Any?>(fixture + "\n" + executable) }
            runBlocking {
                submitCount += js.evaluate<Int>("fixtureSubmitCount")
                changeCount += js.evaluate<Int>("fixtureChangeCount")
                lastSelectedValue = js.evaluate<String>("el.value")
            }
            val raw = when (returned) {
                is String -> JsonPrimitive(returned).toString()
                null -> "null"
                else -> returned.toString()
            }
            // Simulate a WebView/native bridge result limit: only JS-side clipping avoids it.
            resultCallback?.onReceiveValue(if (raw.length > maxCallbackChars) "null" else raw)
        }

        override fun close() { js.close() }
    }
}
