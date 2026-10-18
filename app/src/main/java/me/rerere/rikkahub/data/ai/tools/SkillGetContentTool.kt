package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillContent
import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * Phase 16 audit fix — `skill_get_content` LLM tool.
 *
 * Read-only accessor that returns a skill's full SKILL.md body, description, format,
 * source label, and current enabled state. Sits alongside `use_skill` under the same
 * skills surface (see [createSkillTools]) and needs no approval: it only reads disk,
 * unlike `use_skill` which loads a skill into the active turn.
 *
 * Works on disabled skills too — the user may want the LLM to show them a skill they're
 * considering enabling. The `enabled` field surfaces the current state so the LLM can
 * tell the user whether `use_skill` would work right now.
 *
 * [enabledSkills] is the calling assistant's enabled-skills set (used only to populate
 * the `enabled` flag). [allSkills] is every skill on disk, used to build the
 * `available_skills` list in the not-found envelope. [contentReader] resolves a skill
 * name to its on-disk content — production wiring passes `SkillManager::getContent`;
 * JVM tests pass a tmp-dir-free fake (the narrow-seam pattern, same as `SkillSaver`).
 */
fun skillGetContentTool(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    contentReader: (String) -> SkillContent?,
): Tool = Tool(
    name = "skill_get_content",
    description = """
        Показать полное Markdown-содержимое навыка, описание и текущее состояние включения,
        не выполняя его. Используйте для показа навыка пользователю или проверки перед use_skill.
        Работает и с отключёнными навыками. При успехе возвращает
        { name, description, format, source_label, enabled, content_md, args_schema };
        для неизвестного имени — { error: "skill_not_found", available_skills: [...] }.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Имя навыка, содержимое которого нужно прочитать.")
                })
            },
            required = listOf("name"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        fun notFound(name: String): List<UIMessagePart> = listOf(
            UIMessagePart.Text(buildJsonObject {
                put("error", "skill_not_found")
                put("message", "Навык не найден. Выберите имя из available_skills и повторите вызов.")
                put("name", name)
                put("available_skills", buildJsonArray {
                    allSkills.forEach { add(JsonPrimitive(it.name)) }
                })
            }.toString())
        )

        val name = json.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@Tool listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("error", "missing_required_arg")
                    put("detail", "Для skill_get_content укажите непустое имя навыка в name.")
                    put("message", "Не указано имя навыка. Укажите name из available_skills.")
                    put("available_skills", buildJsonArray {
                        allSkills.forEach { add(JsonPrimitive(it.name)) }
                    })
                }.toString())
            )

        val content = contentReader(name) ?: return@Tool notFound(name)
        val payload = buildJsonObject {
            put("name", content.name)
            put("description", content.description)
            content.format?.let { put("format", it) }
            content.sourceLabel?.let { put("source_label", it) }
            put("enabled", name in enabledSkills)
            put("content_md", content.contentMd)
            content.argsSchema?.let { put("args_schema", it) }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
