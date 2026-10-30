package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.root.RootInvocation
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.preferences.TermuxRuntime

/** Ordinary mode retains its consent gate; automatic permission is global on this device. */
fun buildRootTool(manager: RootShellManager, accessStore: RootAccessStore? = null, assistantId: String? = null): Tool = Tool(
    name = "root_exec",
    description = "Выполнить неинтерактивную команду Android через su после проверки UID 0. Используйте для диагностики и операций, требующих root; для обычной оболочки Termux используйте termux_run_command, для удалённой машины — ssh_exec_saved. Нужны root на устройстве и разрешение root-менеджера; автоматического перехода на Termux или Shizuku нет. Подтверждение требуется, если на этом устройстве не включено глобальное автоодобрение или Always Allow; видимые защищённые операции сохраняют отдельное подтверждение. Возвращает ограниченные stdout/stderr, exit_code и ошибки. Начатые команды автоматически не повторяются. timeout_ms: 1000–300000; если не указан, используется пользовательская настройка «Время ожидания команды», максимум 300000 мс. Явный timeout_ms имеет приоритет. PID или стартовая строка журнала не подтверждают завершение. После ошибки проверьте результат запрошенной операции, прежде чем сообщать, что всё выполнено. Для проверки обновлений root-модулей сначала прочитайте updateJson из /data/adb/modules/*/module.prop без изменений, затем скачайте JSON через download_file в /scratch и прочитайте read_file. При необходимости используйте https://api.github.com/repos/<владелец>/<репо>/releases/latest вместо HTML GitHub. Проверка обновлений не разрешает установку.",
    needsApproval = { input ->
        val command = ((input as? JsonObject)?.get("command") as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
        command.isNullOrBlank() || assistantId == null || accessStore?.isAllowed("root_exec") != true ||
            RootApprovalPolicy.reason(command) != null
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Непустая команда оболочки Android с root, например id -u или dumpsys battery.") })
                put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Таймаут команды в миллисекундах: 1000–300000. Если не указан, используется настройка «Время ожидания команды», максимум 300000 мс. Явное значение имеет приоритет."); put("minimum", 1000); put("maximum", 300000); put("default", TermuxRuntime.commandTimeoutMs.coerceIn(1_000L, 300_000L).toInt()) })
            },
            required = listOf("command"),
        )
    },
    execute = { input ->
        val args = input as? JsonObject
        val commandValue = args?.get("command") as? JsonPrimitive
        val command = commandValue?.takeIf { it.isString }?.contentOrNull
        val timeoutValue = args?.get("timeout_ms")
        val timeout = if (timeoutValue == null) TermuxRuntime.commandTimeoutMs.coerceIn(1_000L, 300_000L).toInt()
            else (timeoutValue as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        // Trusted invocation provenance survives revocation between journal acknowledgment and entry.
        val invocation = currentCoroutineContext()[RootInvocation]
        val headless = currentCoroutineContext()[RunExecutionContext] != null
        suspend fun automaticAllowed(): Boolean = invocation?.automaticAllowed?.invoke()
            ?: (assistantId != null && accessStore?.isAllowed("root_exec") == true)
        val automaticAtStart = if (invocation?.automatic == false) false else automaticAllowed()
        val automaticInvocation = invocation?.automatic ?: automaticAtStart
        val result = when {
            command.isNullOrBlank() || timeout == null -> buildJsonObject {
                put("error", "invalid_arguments")
                put("reason", "Укажите непустую команду в command, а timeout_ms — целым числом.")
            }
            '\u0000' in command -> buildJsonObject {
                put("error", "invalid_arguments")
                put("reason", "Команда содержит нулевой байт (NUL), который оболочка не может передать без изменений. Команда не выполнена; передайте строку без NUL.")
            }
            // An Approved invocation has already passed the explicit confirmation in the loop.
            (headless || invocation?.automatic == true || invocation == null && !automaticAtStart && !automaticInvocation) && RootCommandGuard.check(command) != null -> buildJsonObject {
                put("error", "root_command_blocked")
                put("reason", RootCommandGuard.check(command))
            }
            else -> {
                // The loop owns Auto vs Approved and the durable non-replay checkpoint.
                // A failed journal write prevents launch; output is deliberately never journaled.
                val entryId = if (assistantId != null && accessStore != null) withContext(NonCancellable) {
                    accessStore.beginCommand(assistantId, command)
                } else null
                var exitCode: Int? = null
                var status = "root_execution_indeterminate"
                var journalError = false
                val executed = try {
                    manager.exec(command, timeout.coerceIn(1_000, 300_000), headless = headless) {
                        !automaticInvocation || automaticAllowed() && RootApprovalPolicy.reason(command) == null
                    }.also {
                        exitCode = it.exitCode
                        status = it.error ?: if (it.exitCode == 0) "completed" else "failed"
                    }
                } catch (error: CancellationException) {
                    status = "cancelled"
                    throw error
                } finally {
                    if (entryId != null) withContext(NonCancellable) {
                        try {
                            accessStore!!.finishCommand(entryId, exitCode, status)
                        } catch (_: Exception) {
                            // Audit failure cannot replace the command's actual result or cancellation.
                            // No arbitrary exception text (or source command) is emitted here.
                            journalError = true
                        }
                    }
                }
                buildJsonObject {
                    put("backend", "root")
                    put("success", executed.error == null && executed.exitCode == 0)
                    put("stdout", executed.stdout)
                    put("stderr", executed.stderr)
                    if (executed.stdoutTruncated || executed.stderrTruncated) {
                        put("truncated", true)
                        put("stdout_truncated", executed.stdoutTruncated)
                        put("stderr_truncated", executed.stderrTruncated)
                        put("recovery", "Вывод сокращён; root_exec автоматически не сохраняет полный вывод в файл. Если он уже записан исходной командой в известный файл, прочитайте нужный диапазон из этого файла. Для проверки результата используйте отдельную команду чтения с ограниченным выводом. Не повторяйте исходную root-команду вслепую: она могла изменить данные.")
                    }
                    executed.exitCode?.let { put("exit_code", it) }
                    executed.error?.let { put("error", it) }
                    val reason = executed.reason ?:
                        if (executed.error == null) commandExitFailureReason(executed.exitCode, executed.stderr, CommandEnvironment.ANDROID_ROOT, command = command) else null
                    reason?.let { put("reason", it) }
                    if (journalError) {
                        put("journal_error", "root_journal_save_failed")
                        put("warning", "Команда завершилась, но результат не удалось сохранить в журнале. Проверьте устройство перед новым вызовом; команда автоматически не повторяется.")
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
