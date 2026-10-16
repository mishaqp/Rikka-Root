package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HardlineToolArgumentsAuditTest(
    private val toolName: String,
    private val argumentName: String,
    private val value: String,
) {
    private val input: String get() = "{\"$argumentName\":$value}"

    @Test fun `raw JSON with malformed field is left for tool validation`() {
        assertNull(HardlineCommandGuard.checkTool(toolName, input))
    }

    @Test fun `parsed JSON with malformed field is left for tool validation`() {
        assertNull(HardlineCommandGuard.checkToolParsed(toolName, Json.parseToJsonElement(input).jsonObject))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}={2}")
        fun malformedFields(): List<Array<String>> = buildList {
            listOf(
                "termux_run_command" to "command",
                "termux_run_command" to "executable",
                "termux_run_command" to "arguments",
                "ssh_exec" to "command",
                "ssh_exec_saved" to "command",
                "shizuku_exec" to "command",
                "browser_eval_js" to "code",
            ).forEach { (tool, field) ->
                listOf("{}", "[]", "7", "null").forEach { value -> add(arrayOf(tool, field, value)) }
            }
            add(arrayOf("termux_run_command", "arguments", "[{}]"))
            add(arrayOf("termux_run_command", "arguments", "[[]]"))
        }
    }
}
