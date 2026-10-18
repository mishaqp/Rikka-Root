package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    /**
     * Optional disk helper: supplies the mtime-aware read cache the per-turn auto-load
     * path uses, and backs the read-only `skill_get_content` tool. Everything needed for
     * correctness already lives on [SkillMetadata], so when this is absent the files are
     * read straight from the skill's own directory. Same result, just uncached, and the
     * content tool is not offered.
     */
    skillManager: SkillManager? = null,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOfNotNull(
        // Phase 16 audit fix — read-only accessor so the LLM can show a skill's content
        // without re-installing it. Sits under the same skills surface as use_skill.
        skillManager?.let { manager ->
            skillGetContentTool(
                enabledSkills = enabledSkills,
                allSkills = allSkills,
                contentReader = manager::getContent,
            )
        },
        Tool(
            name = "use_skill",
            description = """
                Загрузить и применить навык со специальными инструкциями или возможностями.
                Вызывайте, когда запрос пользователя соответствует одному из доступных навыков.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    // Auto-load skills with `auto_load: true` in their SKILL.md frontmatter:
                    // their body (auto_load_path file if set, else SKILL.md) is inlined into
                    // the system prompt every turn, no `use_skill` call needed. Use for the
                    // "core persona" skills (agent-core/SOUL.md). Models that previously
                    // never bothered to discover the SOUL via use_skill now see it on turn 1.
                    val autoLoaded = available.filter { it.autoLoad }
                    autoLoaded.forEach { skill ->
                        val path = skill.autoLoadPath
                        // Both branches go through SkillManager's mtime-aware cache so
                        // the per-turn auto-load reads are O(stat) on cache hit rather
                        // than O(file I/O) — N auto-load skills × every turn used to
                        // re-read SOUL/HEARTBEAT/etc from disk every time.
                        val body = runCatching {
                            if (path.isNullOrBlank()) {
                                skillManager?.readSkillBody(skill.name)
                                    ?: SkillFrontmatterParser
                                        .extractBody(skill.skillFile.readText())
                            } else {
                                skillManager?.readSkillFileCached(skill.name, path)
                                    ?: SkillPaths.resolveSkillFile(skill.skillDir, path)
                                        ?.takeIf { file -> file.exists() }
                                        ?.readText()
                            }
                        }.getOrNull()
                        if (!body.isNullOrBlank()) {
                            appendLine(body.trim())
                            appendLine()
                        }
                    }

                    // Lazy skills — listed for discovery; loaded on demand via `use_skill`.
                    val lazy = available.filterNot { it.autoLoad }
                    if (lazy.isNotEmpty()) {
                        appendLine("**Навыки**")
                        appendLine("Вам доступны следующие навыки. Когда запрос пользователя соответствует навыку, вызовите `use_skill`, чтобы загрузить его инструкции.")
                        appendLine("<available_skills>")
                        lazy.forEach { skill ->
                            appendLine("  <skill>")
                            appendLine("    <name>${skill.name.escapeXml()}</name>")
                            appendLine("    <description>${skill.description.take(1024).escapeXml()}</description>")
                            appendLine("  </skill>")
                        }
                        append("</available_skills>")
                        appendLine()
                    }
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Имя навыка, который нужно применить.")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Необязательный относительный путь к файлу внутри папки навыка. Без него читаются инструкции SKILL.md. Используйте только пути из Markdown-ссылок в SKILL.md; не угадывайте и не придумывайте пути."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                // Return structured error envelopes instead of throwing. AICore /
                // small models hit `use_skill` with `{}` (no name) regularly; before
                // this fix the LLM saw a 20-frame Java stack trace and gave up. The
                // recovery hint + available_skills list lets the model self-correct
                // on its next call.
                fun err(code: String, detail: String): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", code)
                            put("detail", detail)
                            put("message", when (code) {
                                "missing_required_arg" -> "Не указано имя навыка."
                                "skill_not_enabled" -> "Навык выключен в текущем чате."
                                "skill_not_found" -> "Навык не найден на устройстве."
                                "skill_body_not_found" -> "Файл SKILL.md навыка не найден."
                                "path_outside_skill" -> "Путь выходит за папку навыка."
                                "skill_file_not_found" -> "Файл навыка не найден."
                                "skill_path_is_directory" -> "Указана папка навыка, а требуется файл."
                                else -> "Не удалось прочитать навык."
                            })
                            put("recovery", when (code) {
                                "skill_not_enabled" -> "Включите навык в настройках текущего чата и повторите use_skill."
                                "skill_body_not_found", "skill_not_found" -> "Проверьте установку навыка или установите его заново."
                                "path_outside_skill", "skill_file_not_found", "skill_path_is_directory" -> "Укажите путь к файлу из ссылок в SKILL.md внутри папки навыка; для основных инструкций не передавайте path."
                                else -> "Укажите name из available_skills и повторите use_skill."
                            })
                            put(
                                "available_skills",
                                kotlinx.serialization.json.buildJsonArray {
                                    enabledSkills.forEach {
                                        add(kotlinx.serialization.json.JsonPrimitive(it))
                                    }
                                },
                            )
                        }.toString()
                    )
                )
                // Refuse oversized skill files before reading them whole. SkillManager
                // enforces the same cap on its cached reads (readCached); this is the
                // model-facing envelope so the LLM gets a clean error instead of a
                // failed/empty read.
                fun tooLargeErr(file: java.io.File): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "skill_file_too_large")
                            put("message", "Файл навыка превышает допустимый размер. Сократите его до ${SkillManager.MAX_SKILL_FILE_BYTES} байт и повторите вызов.")
                            put("max_bytes", SkillManager.MAX_SKILL_FILE_BYTES)
                            put("size_bytes", file.length())
                        }.toString()
                    )
                )
                val suppliedName = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: return@Tool err(
                        "missing_required_arg",
                        "Для use_skill укажите аргумент name с именем навыка.",
                    )
                // Preserve Root's upstream escaped-name round trip.
                val name = available.firstOrNull { skill -> skill.name.escapeXml() == suppliedName }?.name ?: suppliedName
                if (name !in enabledSkills) {
                    return@Tool err(
                        "skill_not_enabled",
                        "Навык '$name' не включён для текущего ассистента.",
                    )
                }
                // Resolve through the skill's own directory rather than looking it up by
                // name. A skill whose frontmatter `name:` differs from its folder name
                // (e.g. folder "directory-name", name "Display Name") is unreachable by a
                // name-keyed lookup, which is the upstream bug this adopts the fix for.
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: return@Tool err(
                        "skill_not_found",
                        "Навык '$name' включён, но его метаданные не найдены на устройстве.",
                    )
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                if (path.isNullOrBlank()) {
                    val skillMd = skill.skillFile
                    if (!skillMd.exists()) {
                        return@Tool err(
                            "skill_body_not_found",
                            "Навык '$name' включён, но его файл SKILL.md не найден на устройстве.",
                        )
                    }
                    if (skillMd.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                        return@Tool tooLargeErr(skillMd)
                    }
                    val content = SkillFrontmatterParser.extractBody(skillMd.readText())
                    return@Tool listOf(UIMessagePart.Text(content))
                }
                val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                    ?: return@Tool err(
                        "path_outside_skill",
                        "Путь '$path' выходит за папку навыка '$name'.",
                    )
                if (!target.exists()) {
                    return@Tool err(
                        "skill_file_not_found",
                        "Файл '$path' не найден в навыке '$name'. Используйте только пути из Markdown-ссылок в SKILL.md.",
                    )
                }
                if (!target.isFile) {
                    return@Tool err("skill_path_is_directory", "Путь должен указывать на обычный файл внутри папки навыка.")
                }
                if (target.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                    return@Tool tooLargeErr(target)
                }
                listOf(UIMessagePart.Text(target.readText()))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
