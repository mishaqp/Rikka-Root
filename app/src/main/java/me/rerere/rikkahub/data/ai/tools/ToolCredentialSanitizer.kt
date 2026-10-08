package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.workflow.repository.sanitizeWorkflowToolArguments

/** Workflow actions are persisted tool calls, so they use the same vault boundary as chat. */
internal fun sanitizeNestedToolArguments(
    name: String,
    arguments: String,
    sanitizeLeaf: (String, String) -> String,
): String {
    fun walk(tool: String, input: String, depth: Int): String {
        if (depth > 16) return "{\"_credentials_removed\":\"Слишком глубокие аргументы сценария не сохраняются.\"}"
        val safe = sanitizeLeaf(tool, input)
        return sanitizeWorkflowToolArguments(tool, safe) { child, args -> walk(child, args, depth + 1) }
    }
    return walk(name, arguments, 0)
}

internal fun protectToolArguments(
    name: String,
    input: String,
    ssh: SshToolSecretSanitizer?,
    mcp: McpToolSecretSanitizer?,
): String = sanitizeNestedToolArguments(name, input) { tool, args ->
    val safe = ssh?.sanitizeForPersistence(tool, args) ?: SshToolSecretSanitizer.sanitizeForExport(tool, args)
    mcp?.sanitizeForPersistence(tool, safe) ?: McpToolSecretSanitizer.sanitizeForExport(tool, safe)
}
