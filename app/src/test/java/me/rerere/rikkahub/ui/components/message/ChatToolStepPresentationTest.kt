package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Settings03
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
        assertEquals(ToolResultPreview.Command("", "ls: нет такого файла", 2,
            "Команда завершилась с ошибкой (код 2). Проверьте вывод и результат перед новым запуском."), termux)

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
        assertEquals(ToolResultPreview.Error("Проверьте адрес. Затем повторите."), russian)
        assertEquals(ToolResultPreview.Error("Не удалось выполнить инструмент"),
            preview("unknown_tool", """{"error":"something_broke"}"""))
        assertNull(toolErrorMessage("x", json("""{"error":false,"ok":1}""") as kotlinx.serialization.json.JsonObject))
    }

    @Test fun closedApplicationHasARecognizableInterruptionReason() {
        listOf("workspace_background_output", "workflow_run", "subagent_get").forEach { tool ->
            val result = preview(tool, """{"error":"process_interrupted"}""")
            assertEquals(ToolResultPreview.Error("Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите действие вручную."), result)
        }
    }

    @Test fun historicalMissingProgramResultsExplainTheCorrectExecutionEnvironment() {
        listOf("workspace_shell" to "apt install jq", "termux_run_command" to "pkg install jq",
            "ssh_exec_saved" to "сервер", "root_exec" to "Android").forEach { (tool, hint) ->
            val result = preview(tool, """{"exit_code":127,"stdout":"partial","stderr":"sh: jq: command not found"}""")
                as ToolResultPreview.Command
            val reason = result.reason.orEmpty()
            assertTrue(reason, reason.contains("Программа jq"))
            assertTrue(reason, reason.contains(hint))
            assertEquals("partial", result.stdout)
            assertEquals("sh: jq: command not found", result.stderr)
        }
    }

    @Test fun everyRegisteredToolShowsSafeRussianFallbackWithoutTechnicalPayload() {
        ToolPermissionPolicy.registry.keys.forEach { name ->
            val output = """{"error":"unexpected_backend_failure","detail":"java.io.IOException: file Отчёт.txt exploded","native_error_type":"IOException"}"""
            assertEquals("$name must not expose an error code or an exception", ToolResultPreview.Error("Не удалось выполнить инструмент"), preview(name, output))
        }
    }

    @Test fun russianRecoveryAndPartialSmsWarningsAreNeverCutToOneSentenceOrTwoHundredCharacters() {
        val warning = "Отправка не подтверждена полностью. Некоторые части уже могли уйти. " +
            "Не отправляйте сообщение повторно без проверки и нового решения пользователя. " +
            "Сначала проверьте отправленные сообщения на телефоне и состояние каждой части у оператора."
        val output = kotlinx.serialization.json.buildJsonObject {
            put("success", kotlinx.serialization.json.JsonPrimitive(false))
            put("error", kotlinx.serialization.json.JsonPrimitive(warning))
            put("automatic_retry_allowed", kotlinx.serialization.json.JsonPrimitive(false))
        }.toString()
        assertEquals(ToolResultPreview.Error(warning), preview("send_sms", output))

        assertEquals(ToolResultPreview.Error("Root недоступен. Откройте менеджер root и проверьте доступ."),
            preview("tap", """{"error":"root_not_granted","reason":"Root недоступен. Откройте менеджер root и проверьте доступ."}"""))
    }

    @Test fun mcpAuthenticationAndConnectionFailuresDoNotGetSshAdvice() {
        assertEquals(ToolResultPreview.Error("MCP: не удалось пройти авторизацию — проверьте учётные данные сервера"),
            preview("mcp_test", """{"error":"auth_failed","detail":"Unauthorized"}"""))
        assertEquals(ToolResultPreview.Error("MCP: не удалось подключиться к серверу"),
            preview("mcp_add", """{"error":"connect_failed","detail":"Connection refused"}"""))
        assertEquals(ToolResultPreview.Error("MCP: не удалось пройти авторизацию — проверьте учётные данные сервера"),
            preview("mcp__server__run", """{"error":"auth_failed"}"""))
        assertEquals(ToolResultPreview.Error("SSH: неверный логин, пароль или ключ"),
            preview("ssh_exec", """{"error":"auth_failed"}"""))
    }

    @Test fun nestedErrorCodeAndRussianRecoveryRemainReadable() {
        assertEquals(ToolResultPreview.Error("Нет root-доступа — разрешите его в менеджере root"),
            preview("root_exec", """{"error":{"code":"root_not_granted","message":"Permission denied"}}"""))
        assertEquals(ToolResultPreview.Error("Файл не найден. Проверьте выбранную папку."),
            preview("read_file", """{"error":{"code":"read_failed","message":"File not found","recovery":"Файл не найден. Проверьте выбранную папку."}}"""))
    }

    @Test fun statusAndIsErrorFailuresAreDetectedWithoutTreatingFalseEmptyErrorsAsFailures() {
        listOf("""{"isError":true,"content":[{"type":"text","text":"Нет доступа. Проверьте разрешение."}]}""",
            """{"status":"failed","message":"Нет доступа. Проверьте разрешение."}""").forEach { output ->
            assertEquals(ToolResultPreview.Error("Нет доступа. Проверьте разрешение."), preview("mcp_test", output))
        }
        listOf("false", "null", "\"\"", "0", "[]", "{}").forEach { value ->
            val result = preview("set_torch", """{"success":true,"error":$value}""")
            assertFalse("$value is not an error", result is ToolResultPreview.Error)
        }
        assertEquals(ToolResultPreview.Error("Не удалось выполнить инструмент"),
            preview("read_file", """{"success":false,"error":false}"""))
    }

    @Test fun partialBatchFailuresShowEachFileAndPreserveTheSuccessfulCount() {
        val result = preview("batch_move", """{"success":2,"failed":[{"path":"/sdcard/Фото/a.jpg","error":"Файл уже существует."},{"path":"/sdcard/Фото/b.jpg","error":"Копия сохранена, но источник не удалось полностью удалить. Назначение сохранено для восстановления."}]}""")
        assertTrue(result is ToolResultPreview.Error)
        val message = (result as ToolResultPreview.Error).message
        listOf("Успешно: 2", "a.jpg", "b.jpg", "Файл уже существует.", "Назначение сохранено для восстановления.").forEach {
            assertTrue("Missing $it in $message", message.contains(it))
        }
        assertFalse(message.contains("{\""))
        assertFalse(preview("batch_copy", """{"success":2,"failed":[]}""") is ToolResultPreview.Error)
    }

    @Test fun successfulResultsKeepFullWarningAndWarnFields() {
        listOf("warning", "warn", "warnings").forEach { key ->
            val message = "Копия сохранена. Проверьте содержимое файла перед удалением оригинала."
            val value = if (key == "warnings") "[\"$message\"]" else "\"$message\""
            val result = preview("copy_file", """{"success":true,"$key":$value}""")
            assertTrue("$key must be visible", result.toString().contains(message))
        }
    }

    @Test fun commandErrorsAndTimeoutsKeepUsefulOutput() {
        val result = preview("root_exec", """{"error":"command_timeout","stdout":"checkpoint saved\n","stderr":"disk nearly full\n","exit_code":124}""")
        listOf("не завершилась вовремя", "checkpoint saved", "disk nearly full", "124").forEach {
            assertTrue("Error must preserve command output: $result", result.toString().contains(it))
        }
        val timeout = preview("workspace_shell", """{"stdout":"partial output","stderr":"","timedOut":true,"exitCode":-1}""")
        assertTrue(timeout is ToolResultPreview.Error)
        assertTrue(timeout.toString().contains("partial output"))
        val ssh = preview("ssh_exec", """{"error":"command_timeout","partial_stdout":"remote checkpoint","partial_stderr":"remote warning"}""")
        assertTrue(ssh.toString().contains("remote checkpoint"))
        assertTrue(ssh.toString().contains("remote warning"))
    }

    @Test fun nonzeroCommandExitPreservesStreamsAndExplainsFailureInPreviewAndDetails() {
        val explanation = "Команда завершилась с ошибкой (код 7). Проверьте вывод и результат перед новым запуском."
        listOf("root_exec", "ssh_exec", "ssh_exec_saved", "termux_run_command", "workspace_shell").forEach { tool ->
            val output = kotlinx.serialization.json.buildJsonObject {
                put("success", kotlinx.serialization.json.JsonPrimitive(false))
                put(if (tool == "workspace_shell") "exitCode" else "exit_code", kotlinx.serialization.json.JsonPrimitive(7))
                put("stdout", kotlinx.serialization.json.JsonPrimitive("checkpoint saved\nlast useful line"))
                put("stderr", kotlinx.serialization.json.JsonPrimitive("command failed\ninspect the output"))
            }.toString()
            val result = preview(tool, output)
            assertTrue("$tool keeps the command renderer", result is ToolResultPreview.Command)
            val command = result as ToolResultPreview.Command
            assertEquals("checkpoint saved\nlast useful line", command.stdout)
            assertEquals("command failed\ninspect the output", command.stderr)
            assertEquals(7, command.exitCode)
            listOf(command.readableText(), presentToolDetails(tool, json(output))).forEach { text ->
                listOf(explanation, command.stdout, command.stderr).forEach { expected ->
                    assertTrue("$tool missing $expected from $text", text.contains(expected))
                }
            }
        }
    }

    @Test fun nonzeroCommandExitKeepsSpecificRussianMessageReasonOrTermuxNoteAndRecovery() {
        listOf("message", "reason", "note").forEach { key ->
            val output = """{"exit_code":3,"stdout":"saved","stderr":"failed","$key":"Команда выполнилась частично. Не запускайте её повторно без проверки.","recovery":"Проверьте созданный файл."}"""
            listOf(preview("termux_run_command", output)!!.readableText(), presentToolDetails("termux_run_command", json(output))).forEach { text ->
                assertTrue(text.contains("Команда выполнилась частично. Не запускайте её повторно без проверки."))
                assertTrue(text.contains("Проверьте созданный файл."))
            }
        }
    }

    @Test fun backgroundStopKeepsCommandAndExitCodeWithoutInventingForegroundRecovery() {
        val stopped = preview("workspace_background_output", """{"exitCode":143,"stdout":"stopped","stderr":""}""") as ToolResultPreview.Command
        assertEquals(143, stopped.exitCode)
        assertEquals("stopped", stopped.stdout)
        assertNull(stopped.reason)
        assertFalse("An intentional background stop must not gain a foreground failure explanation",
            stopped.readableText().contains("Команда завершилась с ошибкой"))
    }

    @Test fun mcpErrorEnvelopeIsReadableEvenWhenOriginalTextPartsFollowIt() {
        val message = "Сервер MCP сообщил об ошибке инструмента. Проверьте параметры вызова и повторите запрос."
        val output = """{"error":"mcp_tool_error","detail":"$message","raw_error":"IllegalStateException"}""" +
            "\njava.lang.IllegalStateException: native MCP failure"
        assertEquals(ToolResultPreview.Error(message), preview("mcp__server__run", output))
    }

    @Test fun commonRegisteredToolErrorCodesHaveActionableRussianReasons() {
        listOf(
            Triple("browser_open", "browser_not_open", "браузер"),
            Triple("termux_session_read", "session_not_found", "сесси"),
            Triple("skill_get_content", "skill_not_found", "Навык"),
            Triple("workflow_get", "not_found", "Сценарий"),
            Triple("subagent_get", "unknown_run", "Субагент"),
            Triple("check_token_usage", "conversation_not_allowed", "диалог"),
            Triple("calendar_create", "NO_PERMISSION", "разрешени"),
        ).forEach { (tool, code, expected) ->
            val result = preview(tool, """{"error":"$code"}""") as ToolResultPreview.Error
            assertTrue("$tool/$code: ${result.message}", result.message.contains(expected, ignoreCase = true))
            assertFalse(result.message.contains(code))
        }
    }

    @Test fun notFoundReasonsUseTheActualToolDomain() {
        listOf(
            "workflow_get" to "Сценарий не найден",
            "get_job_history" to "Задание не найдено",
            "mcp_get" to "Сервер MCP не найден",
            "mcp__server__read" to "Запрошенный объект MCP не найден",
            "ssh_download" to "Объект не найден",
            "unknown_tool" to "Объект не найден",
        ).forEach { (tool, message) ->
            assertEquals(ToolResultPreview.Error(message), preview(tool, """{"error":"not_found"}"""))
        }
    }

    @Test fun detailsRenderReadableFieldsListsAndCommandsWithoutJsonEnvelopes() {
        val listing = presentToolDetails("list_files", json("""{"files":[{"name":"Фото.jpg","path":"/sdcard/Фото.jpg","size_bytes":123}],"truncated":false}"""))
        listOf("Фото.jpg", "Путь", "Размер", "123").forEach { assertTrue(listing.contains(it)) }
        assertFalse(listing.contains("{\""))
        assertFalse(listing.contains("\"files\""))
        val root = presentToolDetails("root_exec", json("""{"stdout":"line 1\nline 2","stderr":"warn","exit_code":2}"""))
        listOf("line 1\nline 2", "warn", "Код завершения: 2").forEach { assertTrue(root.contains(it)) }
        val error = presentToolDetails("read_file", json("""{"error":"read_failed","recovery":"Файл не найден. Проверьте выбранную папку.","raw_error":"java.io.IOException: private","native_error_type":"IOException"}"""))
        assertEquals("Файл не найден. Проверьте выбранную папку.", error)
    }

    @Test fun fullFailureDetailsRetainSmsCountsPermissionsAndSuccessfulEntries() {
        val sms = presentToolDetails("send_sms", json("""{"error":"Часть сообщения уже отправлена. Не повторяйте отправку.","parts_total":3,"parts_sent":1,"parts_unknown":2,"required_permission":"android.permission.SEND_SMS"}"""))
        listOf("Не повторяйте отправку.", "Всего частей SMS: 3", "Передано частей SMS: 1", "Частей SMS без подтверждения: 2", "android.permission.SEND_SMS").forEach {
            assertTrue("Missing $it from $sms", sms.contains(it))
        }
        val mixed = presentToolDetails("batch_copy", json("""{"success":1,"results":[{"path":"copied.txt","success":true},{"path":"failed.txt","error":"Нет доступа."}]}"""))
        assertTrue(mixed.contains("copied.txt"))
        assertTrue(mixed.contains("Нет доступа."))
    }

    @Test fun successfulPlainTextCanMentionExceptionClassesWithoutBecomingAnError() {
        val text = "Документация: IOException означает ошибку чтения."
        assertEquals(ToolResultPreview.Summary(text), preview("mcp__server__docs", text))
        val startsWithClass = "IOException означает ошибку чтения, это справочная статья."
        assertEquals(ToolResultPreview.Summary(startsWithClass), preview("mcp__server__docs", startsWithClass))
    }

    @Test fun nestedSuccessfulWarningsRemainVisibleWithoutFailingTheResult() {
        val text = "Файл создан. Проверьте копию перед удалением оригинала."
        val result = preview("batch_copy", """{"success":true,"results":[{"path":"copy.txt","success":true,"warn":"$text"}]}""")
        assertTrue(result is ToolResultPreview.Summary)
        assertTrue(result.toString().contains(text))
    }

    @Test fun mcpDetailsRetainOriginalUsefulPartsAlongsideTheRussianError() {
        val warning = "Файл /workspace/report.csv уже создан. Не повторяйте операцию."
        val texts = presentToolOutputTexts("mcp__server__run", listOf(
            """{"error":"mcp_tool_error","detail":"Сервер MCP сообщил об ошибке инструмента.","raw_error":"$warning"}""",
            warning,
            "Retry rejected by server",
            "java.io.IOException: private native failure\n at server.main(Server.kt:1)",
            "java.lang.Exception: base native failure",
            "[java.lang.Error] base native failure",
            "Caused by: java.lang.AssertionError: native failure",
            "Exception in thread \"main\" java.lang.RuntimeException: native failure",
            " at server.main(Server.kt:1)",
            " at java.base/java.lang.Thread.run(Thread.java:1)",
            "\"java.lang.Exception: encoded native failure\"",
            """{"message":"java.lang.Exception: native failure","text":"Сохранён файл report.csv."}""",
        ))
        assertTrue(texts.joinToString("\n").contains(warning))
        assertTrue(texts.joinToString("\n").contains("Retry rejected by server"))
        assertFalse(texts.joinToString("\n").contains("java.io.IOException"))
        assertFalse(texts.joinToString("\n").contains("java.lang."))
        assertFalse(texts.joinToString("\n").contains("server.main"))
        assertFalse(texts.joinToString("\n").contains("java.base/"))
        assertTrue(texts.joinToString("\n").contains("Сохранён файл report.csv."))
        assertFalse(texts.joinToString("\n").contains("raw_error"))
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

    @Test fun termuxSessionsShowTheNewestScreenLines() {
        val screen = """{"success":true,"screen":"~ $ echo сессия\nсессия\n~ $ ls\na  b\n~ $ \n\n"}"""
        assertEquals(ToolResultPreview.Command("сессия\n~ $ ls\na  b\n~ $", "", null), preview("termux_session_send", screen))
        assertEquals(ToolResultPreview.Summary("Экран пуст"), preview("termux_session_read", """{"success":true,"screen":"  \n"}"""))
        assertEquals(ToolResultPreview.Summary("Сессия закрыта"), preview("termux_session_kill", """{"success":true,"killed":"s1"}"""))
    }

    @Test fun blankTermuxScreenKeepsFullNoteAndPartialCommandStreamsInPreviewAndDetails() {
        val note = "Сессия создана, но экран не прочитан. Команда уже могла выполниться. Прочитайте сессию перед повторным вводом."
        val stdout = "checkpoint saved\nlast useful line"
        val stderr = "partial warning\ncheck the result"
        val output = kotlinx.serialization.json.buildJsonObject {
            put("success", kotlinx.serialization.json.JsonPrimitive(true))
            put("screen", kotlinx.serialization.json.JsonPrimitive(" \n"))
            put("note", kotlinx.serialization.json.JsonPrimitive(note))
            put("partial_stdout", kotlinx.serialization.json.JsonPrimitive(stdout))
            put("partial_stderr", kotlinx.serialization.json.JsonPrimitive(stderr))
        }.toString()
        listOf(preview("termux_session_start", output)!!.readableText(),
            presentToolDetails("termux_session_start", json(output))).forEach { text ->
            listOf(note, stdout, stderr).forEach { expected ->
                assertTrue("Missing $expected from $text", text.contains(expected))
            }
        }
    }

    @Test fun tokenUsageIsReadableInsteadOfAStatusCode() {
        assertEquals(ToolResultPreview.Summary("Использовано 18.4k токенов · лимит не задан"),
            preview("check_token_usage", """{"total_tokens":18400,"budget_enabled":true,"status":"NO_BUDGET"}"""))
        assertEquals(ToolResultPreview.Summary("Использовано 900 токенов · лимит 1k · близко к лимиту"),
            preview("check_token_usage", """{"total_tokens":900,"hard_cap":1000,"status":"WARN"}"""))
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

    @Test fun assistantManagementHeadlinesShowTheActionAndTarget() {
        val titles = mapOf(
            "list" to "Ассистенты: список",
            "list_options" to "Ассистенты: доступные параметры",
            "get" to "Ассистент: просмотр Помощник",
            "create" to "Ассистент: создать Помощник",
            "update" to "Ассистент: изменить Помощник",
            "duplicate" to "Ассистент: скопировать Помощник",
            "switch" to "Ассистент: переключить на Помощник",
            "delete" to "Ассистент: удалить Помощник",
        )
        titles.forEach { (action, expected) ->
            assertEquals(expected, presentToolHeadline("manage_assistants",
                json("""{"action":"$action","name":"Помощник"}""")))
        }
        assertEquals("Ассистент: просмотр 123", presentToolHeadline("manage_assistants",
            json("""{"action":"get","assistant_id":"123"}""")))
        assertEquals("Ассистент: создать Новый помощник", presentToolHeadline("manage_assistants",
            json("""{"action":"create","settings":{"name":"Новый помощник"}}""")))
        assertNull(presentToolHeadline("manage_assistants", json("""{"action":"future"}""")))
        assertEquals(HugeIcons.AiBrain01, timelineToolIcon("manage_assistants"))
    }

    @Test fun appSettingsHeadlinesShowTheActionWithoutRepeatingTheValue() {
        assertEquals("Настройки: доступные разделы", presentToolHeadline("manage_app_settings",
            json("""{"action":"list_sections"}""")))
        assertEquals("Настройки: просмотр display", presentToolHeadline("manage_app_settings",
            json("""{"action":"get","section":"display"}""")))
        assertEquals("Настройки: изменить themeMode", presentToolHeadline("manage_app_settings",
            json("""{"action":"set","key":"themeMode","value":"dark"}""")))
        assertNull(presentToolHeadline("manage_app_settings", json("""{"action":"future"}""")))
        assertEquals(HugeIcons.Settings03, timelineToolIcon("manage_app_settings"))
    }
}
