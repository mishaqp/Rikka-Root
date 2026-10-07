package me.rerere.rikkahub.data.ai.tools

/** Agent's common approval registry, limited to capabilities implemented in this fork. */
object ToolApprovalDefaults {
    val ALWAYS_ASK: Set<String> = ToolPermissionPolicy.registry.keys + "eval_javascript"

    fun requiresApproval(toolName: String): Boolean = toolName in ALWAYS_ASK || toolName.startsWith("mcp__")
}
