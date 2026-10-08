// Adapted from ExTV/rikkahub-agent reliability/ReliabilityTools.kt (AGPL-3.0).
package me.rerere.rikkahub.reliability

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.LocalFileAccess
import java.io.File
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun generateBugReportTool(
    context: Context,
    builder: BugReportBuilder,
    copyToWorkspace: (suspend (File) -> String)? = null,
): Tool = Tool(
    name = "generate_bug_report",
    description = "Создать ZIP-отчёт об ошибке: последние до 5000 строк logcat нашего процесса и полные ошибки ChatService с редактированием секретов, версия приложения/устройства/Android и README со списком исключений. Возвращает абсолютный path и content:// URI для отправки через ACTION_SEND. Файл хранится в кэше; при выбранном workspace текущего чата копия находится в /workspace/reports/, путь возвращается в workspace_path.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    execute = {
        val zip = builder.build()
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, zip)
        }.getOrNull()
        var workspaceError: String? = null
        val workspacePath = if (copyToWorkspace == null) {
            workspaceError = "Workspace текущего чата не выбран; отчёт доступен в кэше и через меню отправки."
            null
        } else try {
            copyToWorkspace(zip)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            workspaceError = "Не удалось сохранить копию в /workspace/reports/. Проверьте доступность workspace текущего чата; отчёт доступен в кэше и через меню отправки."
            null
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("path", zip.absolutePath)
            put("size_bytes", zip.length())
            if (uri != null) put("content_uri", uri.toString())
            put("share_intent_action", Intent.ACTION_SEND)
            put("mime_type", "application/zip")
            workspacePath?.let { put("workspace_path", it) }
            workspaceError?.let { put("workspace_copy_error", it) }
        }
        buildList {
            add(UIMessagePart.Text(payload.toString()))
            if (uri != null) add(UIMessagePart.Document(uri.toString(), zip.name, "application/zip"))
        }
    },
)

/** Uses the same workspace boundary and symlink checks as the existing archive/file tools. */
internal suspend fun copyBugReportToWorkspace(zip: File, access: LocalFileAccess): String = withContext(Dispatchers.IO) {
    val path = "/workspace/reports/${zip.name}"
    val destination = access.resolve(path, write = true)
    zip.inputStream().use { input -> access.openOutput(destination).use { output -> input.copyTo(output) } }
    path
}
