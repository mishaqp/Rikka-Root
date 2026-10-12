package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolStepPresentationTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun preview(tool: String, output: String) =
        presentToolResult(tool, output, runCatching { json(output) }.getOrNull())

    @Test fun commandToolsShowStdoutWithRealLineBreaksAndNoJson() {
        val root = preview("root_exec", """{"backend":"root","success":true,"stdout":"a\nb\nc\n","stderr":"","exit_code":0}""")
        assertEquals(ToolResultPreview.Command("a\nb\nc", "", null), root)

        val termux = preview("termux_run_command", """{"success":true,"mode":"capture","exit_code":2,"stdout":"","stderr":"ls: нет такого файла\n"}""")
        assertEquals(ToolResultPreview.Command("", "ls: нет такого файла", 2), termux)

        val ssh = preview("ssh_exec_saved", """{"success":true,"exit_code":0,"stdout":"up 3 days","stderr":"warn"}""")
        assertEquals(ToolResultPreview.Command("up 3 days", "warn", null), ssh)

        val workspace = preview("workspace_shell", """{"exitCode":0,"stdout":"","stderr":"","timedOut":false}""")
        assertEquals(ToolResultPreview.Summary("Без вывода"), workspace)
    }

    @Test fun errorsBecomeRussianReasons() {
        val background = preview("termux_run_command",
            """{"error":"termux_run_failed","reason":"Not allowed to start service Intent { act=com.termux.RUN_COMMAND }: app is in background"}""")
        assertEquals(ToolResultPreview.Error(
            "Termux не разрешает запуск из фона — дайте Termux разрешение «Поверх других приложений»"), background)

        val overlay = preview("termux_run_command",
            """{"error":"termux_run_failed","reason":"err=1: Termux requires \"Display over other apps\" permission"}""")
        assertTrue((overlay as ToolResultPreview.Error).message.contains("Поверх других приложений"))

        assertEquals(ToolResultPreview.Error("SSH: неверный логин, пароль или ключ"),
            preview("ssh_exec", """{"error":"auth_failed","detail":"Auth fail"}"""))
        val russian = preview("download_file", """{"error":"bad","recovery":"Проверьте адрес. Затем повторите."}""")
        assertEquals(ToolResultPreview.Error("Проверьте адрес"), russian)
        assertEquals(ToolResultPreview.Error("Ошибка: something_broke"),
            preview("unknown_tool", """{"error":"something_broke"}"""))
        assertNull(toolErrorMessage("x", json("""{"error":false,"ok":1}""") as kotlinx.serialization.json.JsonObject))
    }

    @Test fun otherToolsGetShortSummaries() {
        val written = preview("write_text_file", """{"success":true,"path":"/sdcard/Download/test.txt","bytes_written":12}""")
        assertEquals(ToolResultPreview.Summary("test.txt · 12 байт"), written)
        val list = preview("list_files", """{"path":"/sdcard","entries":[{"name":"a"},{"name":"b"}]}""")
        assertEquals(ToolResultPreview.Summary("sdcard · найдено: 2"), list)
        assertEquals(ToolResultPreview.Summary("Готово"), preview("set_torch", """{"success":true}"""))
        assertEquals(ToolResultPreview.Summary("Первая\nВторая"), preview("x", "Первая\nВторая"))
        assertNull(preview("x", "  "))
        val summary = (preview("get_battery_status", """{"level":80,"charging":false}""") as ToolResultPreview.Summary).text
        assertFalse("Summary must not be raw JSON", summary.contains("{"))
    }

    @Test fun headlinesCarryTheCallEssence() {
        assertEquals("Root: ls /sdcard", presentToolHeadline("root_exec", json("""{"command":"ls   /sdcard"}""")))
        assertEquals("Termux: echo hi", presentToolHeadline("termux_run_command", json("""{"command":"echo hi"}""")))
        assertEquals("Termux: bash -c ls", presentToolHeadline("termux_run_command",
            json("""{"executable":"/data/data/com.termux/files/usr/bin/bash","arguments":["-c","ls"]}""")))
        assertEquals("Файл: записан test.txt", presentToolHeadline("workspace_write_file", json("""{"path":"/workspace/test.txt","text":"x"}""")))
        assertEquals("Браузер: открыта example.com", presentToolHeadline("browser_open", json("""{"url":"https://www.example.com/"}""")))
        assertEquals("SSH home: uptime", presentToolHeadline("ssh_exec_saved", json("""{"name":"home","command":"uptime"}""")))
        assertEquals("Терминал: make test", presentToolHeadline("workspace_shell", json("""{"command":"make\ntest"}""")))
        assertEquals("MCP github: create_issue", presentToolHeadline("mcp__0123abcd_github__create_issue", json("{}")))
        assertNull(presentToolHeadline("get_battery_status", json("{}")))
        assertNull(presentToolHeadline("root_exec", null))
    }

    @Test fun everyRegisteredToolHasItsOwnIcon() {
        val missing = ToolPermissionPolicy.registry.keys.filter { name ->
            ToolUIRegistry.resolve(name).toolName.isEmpty() && timelineToolIcon(name) == null
        }
        assertEquals("Tools that fall back to the generic wrench", emptyList<String>(), missing)
        assertNotNull(timelineToolIcon("root_exec"))
        assertTrue(timelineToolIcon("root_exec") != timelineToolIcon("browser_open"))
        assertNull(timelineToolIcon("totally_unknown_tool"))
    }
}
