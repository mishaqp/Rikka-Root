package me.rerere.rikkahub.workflow.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/** Required nesting adapter: workflow args are tool calls too, before Room/FTS or export. */
fun sanitizeWorkflowDefinition(
    definition: WorkflowDefinition,
    sanitizeActionArgs: (String, String) -> String,
): WorkflowDefinition = definition.copy(actions = definition.actions.map { action ->
    action.copy(args = Json.parseToJsonElement(sanitizeActionArgs(action.tool, action.args.toString())) as JsonObject)
})

fun sanitizeWorkflowToolArguments(
    toolName: String,
    input: String,
    sanitizeActionArgs: (String, String) -> String,
): String {
    if (!setOf("workflow_create", "workflow_update").any { it.startsWith(toolName) }) return input
    return try {
        val args = Json.parseToJsonElement(input) as JsonObject
        val definition = args["definition"] as JsonObject
        val actions = definition["actions"] as JsonArray
        val safeActions = actions.map { element ->
            val action = element as JsonObject
            val name = (action["tool"] as JsonPrimitive).content
            val actionArgs = action["args"] as JsonObject
            JsonObject(action.toMutableMap().apply {
                put("args", Json.parseToJsonElement(sanitizeActionArgs(name, actionArgs.toString())))
            })
        }
        JsonObject(args.toMutableMap().apply {
            put("definition", JsonObject(definition.toMutableMap().apply { put("actions", JsonArray(safeActions)) }))
        }).toString()
    } catch (_: Exception) {
        "{\"_credentials_removed\":\"Неполные аргументы сценария удалены из истории для защиты секретов.\"}"
    }
}
