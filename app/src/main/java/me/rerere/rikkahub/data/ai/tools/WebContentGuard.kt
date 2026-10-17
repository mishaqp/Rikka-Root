package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

object WebContentGuard {
    // JS skills may return remote pages/API data; conservatively retain the web-content boundary.
    // download_file + read_file is the documented way to read a remote file; MCP servers relay
    // arbitrary remote content (fetch, GitHub, Notion), so both count as web content.
    // Shell output can relay curl/wget, aliases, scripts and remote session output. Classify
    // the result conservatively instead of trying to infer network access from shell syntax.
    private val clientReaders = setOf(
        "search_web", "scrape_web", "run_js", "skill_install_from_url", "download_file", "http_request",
        "workspace_shell", "root_exec", "ssh_exec", "ssh_exec_saved", "termux_run_command",
        "termux_session_start", "termux_session_send", "termux_session_read",
    )
    private val serverReaders = setOf("web_search", "google_search", "url_context")

    fun isClientReader(name: String): Boolean = name in clientReaders || name.startsWith("mcp__") ||
        name.startsWith("browser_") && name != "browser_done"

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
