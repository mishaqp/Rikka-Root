package me.rerere.rikkahub.skills

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager

private const val TAG = "SkillInstallTools"

/**
 * Phase 16 audit fix — outcome of the auto-enable step that rides along with a successful
 * `skill_install_from_*` call. Decoupled from the DataStore write so it can be unit-tested
 * against a plain in-memory `Set<String>` without an Android Context.
 *
 * @property autoEnabled true when the skill is enabled for the calling assistant after the
 * install (either freshly enabled, or already-enabled and left alone).
 * @property updatedEnabledSkills the new enabled-skills set to persist, or null when no
 * persistence is needed (already enabled, or preserved-disabled).
 */
data class AutoEnableOutcome(
    val autoEnabled: Boolean,
    val detail: String,
    val updatedEnabledSkills: Set<String>?,
)

/**
 * Decide what the auto-enable step should do, given the calling assistant's current
 * [enabledSkills] set, the freshly-installed [skillName], and whether that skill
 * [existedBefore] the install wrote to disk.
 *
 *  - Already enabled → no-op, stays enabled (idempotent re-install).
 *  - New skill (did not exist before) → enable it.
 *  - Existed before but not enabled → the user previously disabled it; respect that and
 *    leave it disabled.
 */
fun decideAutoEnable(
    enabledSkills: Set<String>,
    skillName: String,
    existedBefore: Boolean,
): AutoEnableOutcome = when {
    skillName in enabledSkills -> AutoEnableOutcome(
        autoEnabled = true,
        detail = "Навык уже включён в текущем чате и готов к работе.",
        updatedEnabledSkills = null,
    )

    existedBefore -> AutoEnableOutcome(
        autoEnabled = false,
        detail = "Навык обновлён, но остаётся выключенным. Включите его в настройках навыков текущего чата перед use_skill.",
        updatedEnabledSkills = null,
    )

    else -> AutoEnableOutcome(
        autoEnabled = true,
        detail = "Навык включён в текущем чате и готов к работе.",
        updatedEnabledSkills = enabledSkills + skillName,
    )
}

/**
 * Run the auto-enable side effect for a freshly-installed [skillName]: resolve the calling
 * assistant, decide via [decideAutoEnable], and persist the updated enabled-skills set when
 * needed. Returns the outcome so the install tool can surface it in the response envelope.
 *
 * A DataStore write failure (or any other unexpected error) is caught and reported as
 * `autoEnabled = false` with a recovery hint — the install itself already succeeded, so we
 * never fail the whole tool call over the auto-enable step.
 */
/** Trusted calling conversation, supplied by LocalTools rather than model arguments. */
data class SkillInstallCaller(
    val enabledSkills: suspend () -> Set<String>,
    val saveEnabledSkills: suspend (Set<String>) -> Unit,
)

private suspend fun applyAutoEnable(
    caller: SkillInstallCaller,
    skillName: String,
    existedBeforeInstall: Boolean,
): AutoEnableOutcome = try {
    val outcome = decideAutoEnable(caller.enabledSkills(), skillName, existedBeforeInstall)
    outcome.updatedEnabledSkills?.let { caller.saveEnabledSkills(it) }
    outcome
} catch (_: Exception) {
    // Never log imported content, callback exception messages, or credentials.
    AutoEnableOutcome(
        autoEnabled = false,
        detail = "Навык установлен, но не включён в текущем чате. Включите его в настройках навыков этого чата.",
        updatedEnabledSkills = null,
    )
}

/**
 * Snapshot of every skill name on disk. Captured *before* the import writes, so the install
 * tool can tell a brand-new skill apart from a re-install of an existing (possibly disabled)
 * one — the importer itself doesn't surface that distinction.
 */
private fun existingSkillNames(skillManager: SkillManager): Set<String> =
    runCatching { skillManager.listSkills().map { it.name }.toSet() }.getOrDefault(emptySet())

/**
 * Phase 16 — `skill_install_from_url` LLM tool.
 *
 * Always requires per-call approval (NO_ALWAYS_ALLOW). Skills installed this way can ride
 * along with the assistant's full tool surface — the user must consent every single time
 * because the source URL is whatever the LLM said it was.
 *
 * Newly-installed skills are auto-enabled for the calling assistant so `use_skill` works on
 * the next turn without a manual toggle (Phase 16 audit fix). A re-install of a skill the
 * user previously disabled is left disabled — see `auto_enabled` / `auto_enabled_detail` in
 * the response.
 */
fun skillInstallFromUrlTool(
    importer: SkillUrlImporter,
    caller: SkillInstallCaller,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_url",
    description = """
        Загрузить и установить навык по URL. Поддерживаются Markdown Rikka-Root с frontmatter,
        Markdown OpenClaw и JSON Hermes. Имена инструментов по возможности приводятся
        к эквивалентам Rikka-Root. Каждый вызов с переданными аргументами требует одобрения.
        Если name не указан, итоговое имя определяется из содержимого при установке и возвращается в name.
        Новый навык автоматически включается у вызывающего ассистента, кроме ранее существовавшего
        и отключённого навыка. Успешный результат:
        { ok, name, format, source_url, auto_enabled, auto_enabled_detail }.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL Markdown или JSON навыка по HTTP(S). Loopback и частные IP-адреса отклоняются.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательное имя для сохранения навыка: 1–40 символов из [a-z0-9-_]. По умолчанию используется имя из источника.")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val url = json.jsonObject["url"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_url", "url is required")
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromUrl(url, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(caller, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_url", url)
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

/**
 * Phase 16 — `skill_install_from_text`. Companion to `skill_install_from_url` for sources
 * the URL importer can't reach: authenticated servers, private intranets, SSH-only hosts,
 * MCP-relayed skill servers, or content the user just pasted into chat.
 *
 * Compose with any read-capable tool to bridge the gap, e.g.:
 *  - `ssh_exec_saved(host_label="myserver", command="cat ~/skills/morning.md")` → pipe stdout
 *  - `termux_run_command(command="curl -H 'Authorization: Bearer …' https://my.server/skill.md")`
 *  - any MCP tool returning a skill body
 *
 * Same approval gate as `skill_install_from_url` — every install is reviewed individually
 * (NO_ALWAYS_ALLOW). Same format detect + tool-name transcode + persistence as the URL path,
 * and the same auto-enable behavior (Phase 16 audit fix).
 */
fun skillInstallFromTextTool(
    importer: SkillUrlImporter,
    caller: SkillInstallCaller,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_text",
    description = """
        Установить навык из уже полученного текста Markdown или JSON, например прочитанного
        через ssh_exec, termux_run_command или MCP из источника с авторизацией.
        Поддерживаются форматы Rikka-Root, OpenClaw и Hermes, как в skill_install_from_url.
        Каждый вызов с переданными аргументами требует одобрения. Если name не указан, итоговое имя
        определяется из содержимого при установке и возвращается в name. Новый навык автоматически включается у вызывающего ассистента, кроме ранее существовавшего и отключённого навыка.
        Успешный результат: { ok, name, format, source_label, auto_enabled, auto_enabled_detail }.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Полный текст навыка: Markdown с frontmatter Rikka-Root/OpenClaw или JSON Hermes. Не более 262144 единиц UTF-16.")
                })
                put("source_label", buildJsonObject {
                    put("type", "string")
                    put("description", "Источник содержимого: URL, метка SSH-хоста, инструмент MCP, буфер обмена и т. п. Сохраняется во frontmatter навыка для проверки происхождения.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательное имя для сохранения навыка: 1–40 символов из [a-z0-9-_]. По умолчанию используется имя из источника.")
                })
            },
            required = listOf("content"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val content = json.jsonObject["content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_content", "content is required")
        val sourceLabel = json.jsonObject["source_label"]?.jsonPrimitive?.contentOrNull
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromText(content, sourceLabel, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(caller, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_label", JsonPrimitive(sourceLabel ?: "imported"))
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

private fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", when (code) {
            "missing_url" -> "Укажите адрес источника навыка в url."
            "invalid_url" -> "Укажите полный адрес источника навыка с именем хоста."
            "unsupported_url_scheme" -> "Для загрузки навыка разрешены только адреса http и https."
            "loopback_host_rejected" -> "Локальные и недоступные извне адреса запрещены для импорта навыка."
            "missing_content", "empty_body" -> "Укажите непустой текст навыка в content."
            "html_response" -> "Источник содержит HTML-страницу вместо файла навыка. Укажите исходный текст SKILL.md или его прямой URL."
            "body_too_large" -> "Текст навыка превышает допустимые 262144 единиц UTF-16. Сократите его перед установкой."
            "transcode_failed" -> "Не удалось разобрать формат навыка. Проверьте текст Markdown или JSON."
            "missing_name" -> "В описании навыка отсутствует имя. Укажите name."
            "invalid_name" -> "Имя навыка должно содержать 1–40 символов: a-z, 0-9, дефис или подчёркивание."
            "save_failed" -> "Не удалось записать файлы навыка. Проверьте доступное место."
            else -> detail
        })
        put("message", when (code) {
            "missing_url", "invalid_url", "unsupported_url_scheme" -> "Адрес навыка отсутствует или неверен. Укажите полный URL с http или https."
            "loopback_host_rejected" -> "Адрес навыка недоступен для импорта. Используйте разрешённый внешний URL или skill_install_from_text."
            "missing_content", "empty_body" -> "Текст навыка пуст. Укажите полный текст в content."
            "body_too_large" -> "Текст навыка слишком велик. Сократите его до 262144 единиц UTF-16 и повторите установку."
            "missing_name", "invalid_name" -> "Имя навыка отсутствует или неверно. Укажите name длиной 1–40 символов из a-z, 0-9, дефиса и подчёркивания."
            "fetch_failed" -> "Не удалось скачать навык. Проверьте сеть и URL или используйте skill_install_from_text."
            "save_failed" -> "Не удалось сохранить навык. Проверьте свободное место и повторите установку."
            else -> "Не удалось установить навык. Проверьте формат и содержимое источника, затем повторите вызов."
        })
    }.toString()))
