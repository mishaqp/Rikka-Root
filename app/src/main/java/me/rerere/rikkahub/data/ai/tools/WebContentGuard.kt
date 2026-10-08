package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

object WebContentGuard {
    // JS skills may return remote pages/API data; conservatively retain the web-content boundary.
    private val clientReaders = setOf("search_web", "scrape_web", "run_js", "skill_install_from_url")
    private val serverReaders = setOf("web_search", "google_search", "url_context")

    fun isClientReader(name: String): Boolean = name in clientReaders || name.startsWith("browser_") && name != "browser_done"

    fun hasWebContent(messages: List<UIMessage>): Boolean = messages.any { message ->
        message.annotations.any { it is UIMessageAnnotation.UrlCitation || it is UIMessageAnnotation.WebContentUsed } ||
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Tool -> isClientReader(part.toolName) && part.isExecuted
                    is UIMessagePart.ServerTool -> part.toolName.lowercase() in serverReaders
                    else -> false
                }
            }
    }
}
