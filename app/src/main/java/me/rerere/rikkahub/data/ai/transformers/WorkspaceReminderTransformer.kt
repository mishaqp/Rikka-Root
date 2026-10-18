package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 提示中包含 Rootfs 实际使用的 shell 与发行版, 避免模型默认按 bash / apt 来写命令。
 * 同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(
            workspace = workspace,
            shell = workspaceRepository.rootfsShell(workspaceId),
            distro = readDistroName(workspaceId),
            cwd = ctx.workspaceCwd,
        ) + buildAgentsPrompt(workspaceId, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            readRootfsText(workspaceId, path, MAX_AGENTS_BYTES)?.let { path to it }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Следуйте приведённым ниже инструкциям AGENTS.md.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("Источник AGENTS.md: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private suspend fun readDistroName(workspaceId: String): String? =
        OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
            readRootfsText(workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
        }

    // 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分
    private suspend fun readRootfsText(workspaceId: String, path: String, maxBytes: Long): String? =
        try {
            val size = workspaceRepository.rootfsFileSize(workspaceId, path)
            require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportRootfsFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
            null
        }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val MAX_OS_RELEASE_BYTES = 16L * 1024
        val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")
    }
}

private const val MAX_DISTRO_NAME_LENGTH = 80

/** 从 os-release 内容中取发行版名称, 优先 PRETTY_NAME, 其次 NAME + VERSION_ID */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件, 拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    shell: String,
    distro: String?,
    cwd: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("Вам доступна постоянная рабочая папка Linux \"${workspace.name}\" в изолированном окружении proot Rootfs.")
    appendLine("- Файлы рабочей папки смонтированы в `/workspace`. Используйте этот каталог для работы: записанные файлы сохраняются между сообщениями текущего диалога.")
    appendLine("- Все пути для инструментов рабочей папки должны быть абсолютными внутри Rootfs, например `/workspace/notes.md`.")
    if (distro != null) {
        appendLine("- Дистрибутив Rootfs: $distro. Для установки недостающих инструментов используйте его штатный менеджер пакетов.")
    }
    if (shell == "/bin/bash") {
        appendLine("- `workspace_shell` выполняет команды через `/bin/bash`.")
    } else {
        appendLine("- `workspace_shell` выполняет команды через POSIX-оболочку `$shell`. Bash не установлен: не используйте его специфичный синтаксис, например массивы, here-strings (`<<<`) и раскрытие фигурных скобок.")
    }
    appendLine("- Доступные инструменты:")
    appendLine("  - `workspace_read_file`: прочитать содержимое файла.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: создать файл или точечно изменить существующий.")
    appendLine("  - `workspace_shell`: выполнить команды оболочки; файлы рабочей папки смонтированы в /workspace.")
    appendLine("- Для задач, решаемых стандартными утилитами Unix, предпочитайте `workspace_shell`. Для точечных правок используйте `workspace_edit_file` вместо перезаписи целого файла.")
    appendLine("- Каталог навыков смонтирован в `/skills`. Каждый навык находится в `/skills/<skill-name>/`: файл `SKILL.md` с полями frontmatter `name` и `description` и дополнительные файлы. Перед использованием прочитайте `SKILL.md` и следуйте его инструкциям.")
    appendLine("- Встроенные навыки приложения смонтированы в `/builtin_skills/<skill-name>/` с той же структурой. Каталог `/builtin_skills` доступен ТОЛЬКО ДЛЯ ЧТЕНИЯ: читать файлы и запускать скрипты можно, но изменять, перезаписывать и удалять что-либо нельзя. Навык из `/skills` с тем же именем заменяет встроенный.")
    appendLine("- Загруженные пользователем файлы смонтированы в `/upload`. Каталог `/upload` доступен ТОЛЬКО ДЛЯ ЧТЕНИЯ: читайте `/upload/<file-name>`, но ничего там не изменяйте, не перезаписывайте и не удаляйте. Для изменения файла сначала скопируйте его в `/workspace` и правьте копию.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Текущий рабочий каталог: `$cwd`. Используйте его по умолчанию для файловых операций и команд оболочки.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
