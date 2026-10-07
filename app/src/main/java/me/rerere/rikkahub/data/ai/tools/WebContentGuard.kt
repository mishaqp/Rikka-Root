package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

object WebContentGuard {
    private val clientReaders = setOf("search_web", "scrape_web")
    private val serverReaders = setOf("web_search", "google_search", "url_context")

    fun isClientReader(name: String): Boolean = name in clientReaders

    fun hasWebContent(messages: List<UIMessage>): Boolean = messages.any { message ->
        message.annotations.any { it is UIMessageAnnotation.UrlCitation || it is UIMessageAnnotation.WebContentUsed } ||
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Tool -> part.toolName in clientReaders && part.isExecuted
                    is UIMessagePart.ServerTool -> part.toolName.lowercase() in serverReaders
                    else -> false
                }
            }
    }
}
