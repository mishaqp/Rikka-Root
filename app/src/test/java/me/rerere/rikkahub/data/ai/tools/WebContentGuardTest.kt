package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.providers.google.GoogleProvider
import okhttp3.OkHttpClient

class WebContentGuardTest {
    @Test fun nativeUrlResponseWithoutCitationsTriggersGuard() {
        val provider = GoogleProvider(OkHttpClient())
        val candidate = Json.parseToJsonElement("""{"content":{"role":"model","parts":[{"text":"answer"}]},"urlContextMetadata":{"urlMetadata":[]}}""").jsonObject
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java).apply { isAccessible = true }
        val message = method.invoke(provider, candidate) as UIMessage
        assertEquals("answer", message.toText())
        assertTrue(WebContentGuard.hasWebContent(listOf(message)))
    }

    @Test fun executedSearchAndPageReadsTaintTheConversationButOtherToolsDoNot() {
        for (name in listOf("search_web", "scrape_web", "browser_open", "browser_get_text", "browser_get_links", "browser_click_and_read", "run_js", "skill_install_from_url")) {
            val result = UIMessagePart.Tool("id", name, "{}", output = listOf(UIMessagePart.Text("external content")))
            assertTrue(name, WebContentGuard.hasWebContent(listOf(UIMessage.assistant("").copy(parts = listOf(result)))))
            assertFalse("unexecuted request is not read content", WebContentGuard.hasWebContent(listOf(UIMessage.assistant("").copy(parts = listOf(result.copy(output = emptyList()))))))
        }
        assertFalse(WebContentGuard.hasWebContent(listOf(UIMessage.user("search_web https://example.test"))))
        assertFalse(WebContentGuard.hasWebContent(emptyList()))
        assertFalse(WebContentGuard.isClientReader("browser_done"))
        assertFalse(WebContentGuard.isClientReader("skill_install_from_text"))
    }

    @Test fun providerSearchResultsAndCitationsAlsoCountAsWebContent() {
        for (name in listOf("web_search", "google_search", "url_context")) {
            val result = UIMessagePart.ServerTool("id", name, status = ServerToolStatus.COMPLETED)
            assertTrue(name, WebContentGuard.hasWebContent(listOf(UIMessage.assistant("").copy(parts = listOf(result)))))
        }
        assertTrue(WebContentGuard.hasWebContent(listOf(UIMessage.assistant("answer").copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation("source", "https://example.test")),
        ))))
    }

    @Test fun citationFreeProviderMarkerSurvivesConversationSerialization() {
        val messages = listOf(UIMessage.assistant("answer without citations").copy(
            annotations = listOf(UIMessageAnnotation.WebContentUsed),
        ))
        val reopened = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(messages))
        assertTrue(WebContentGuard.hasWebContent(reopened))
        val fileSearch = UIMessagePart.ServerTool("file", "file_search", status = ServerToolStatus.COMPLETED)
        assertFalse(WebContentGuard.hasWebContent(listOf(UIMessage.assistant("").copy(parts = listOf(fileSearch)))))
    }
}
