// Adapted from ExTV/rikkahub-agent reliability/ReliabilityTools.kt (AGPL-3.0).
package me.rerere.rikkahub.reliability

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import kotlin.coroutines.coroutineContext

/** Writes managed cache and must also be registered in the central tool permission policy. */
fun generateBugReportTool(context: Context,
    diagnosticLoader: suspend () -> List<ReportDiagnostic> = { emptyList() }): Tool = Tool(
    name = "generate_bug_report",
    description = "Создать ZIP-отчёт с разрешёнными сведениями о версии приложения, Android и среде выполнения, кодами ошибок и известными символами стека. Диалоги, настройки, секреты, logcat, команды и их вывод не включаются. Возвращает URI и вложение; пользователь может отправить файл кнопкой в результате инструмента. Параметры не требуются.",
    parameters = { InputSchema.Obj(buildJsonObject {}, emptyList()) },
    needsApproval = { true },
    execute = { input ->
        var report: File? = null
        val invalidInput = input !is JsonObject || input.isNotEmpty()
        try {
            check(!invalidInput)
            val file = BugReportBuilder(context, diagnosticLoader).build()
            report = file
            coroutineContext.ensureActive()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val payload = buildJsonObject {
                put("ok", true)
                put("file_name", file.name)
                put("size_bytes", file.length())
                put("content_uri", uri.toString())
                put("mime_type", "application/zip")
            }
            listOf(UIMessagePart.Text(payload.toString()),
                UIMessagePart.Document(uri.toString(), file.name, "application/zip"))
        } catch (cancelled: CancellationException) {
            report?.delete()
            throw cancelled
        } catch (_: Exception) {
            report?.delete()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", if (invalidInput) "Инструмент создания диагностического отчёта не принимает параметры."
                    else "Не удалось создать или предоставить диагностический отчёт. Проверьте доступность кэша приложения.")
            }.toString()))
        }
    },
)
