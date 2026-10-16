package me.rerere.rikkahub.workflow.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.workflow.model.WorkflowJson
import org.junit.Assert.*
import org.junit.Test

class WorkflowApprovalRendererAuditTest {
    @Test fun `audit 12 both approval formats show every action argument beyond old limits`() {
        val longCommand = "echo " + "проверка ".repeat(30) + "КОНЕЦ_КОМАНДЫ"
        val longBody = "содержимое ".repeat(30) + "КОНЕЦ_ФАЙЛА"
        val actionArgs = buildJsonObject {
            put("command", longCommand)
            put("content", longBody)
            put("destination", "/sdcard/последний-аргумент.txt")
            put("password", "must-remain-secret")
        }
        val definition = """{"name":"Проверка","trigger":{"type":"manual"},"actions":[{"tool":"ssh_exec","args":$actionArgs}]}"""
        assertNotNull("The fixture must exercise the renderer, not its invalid-JSON fallback", WorkflowJson.parseStored(definition))
        for (name in listOf("workflow_create", "workflow_update")) {
            val input = JsonObject(mapOf("definition" to Json.parseToJsonElement(definition))).toString()
            for (rendered in listOf(WorkflowApprovalRenderer.renderPlain(name, input), WorkflowApprovalRenderer.renderTelegramHtml(name, input))) {
                assertTrue(rendered, rendered.contains(longCommand))
                assertTrue(rendered, rendered.contains(longBody))
                assertTrue(rendered, rendered.contains("/sdcard/последний-аргумент.txt"))
                assertFalse("Expanding approval details must keep secret masking", rendered.contains("must-remain-secret"))
            }
        }
    }
}
