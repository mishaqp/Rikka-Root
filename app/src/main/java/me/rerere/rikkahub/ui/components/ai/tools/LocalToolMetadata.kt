package me.rerere.rikkahub.ui.components.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.skillGetContentTool
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.model.Assistant

/** The actual names/descriptions and compact JSON schemas, at about four characters per token. */
internal fun estimateToolTokens(
    tools: Collection<Tool>,
    autoLoadedSkillBodies: List<String> = emptyList(),
): Long {
    val definitionCharacters = tools.sumOf { tool ->
        tool.name.length.toLong() + tool.description.length + serializedToolSchema(tool).length
    }
    val promptCharacters = autoLoadedSkillBodies.sumOf { it.length.toLong() }
    return (definitionCharacters + promptCharacters + 3L) / 4L
}

internal fun serializedToolSchema(tool: Tool): String = Json.encodeToJsonElement<InputSchema>(
    tool.parameters() ?: InputSchema.Obj(JsonObject(emptyMap()))
).toString()

/** Builds the real two skill definitions from the already-loaded catalog, without listing or extracting skills. */
internal fun skillToolsForAssistant(assistant: Assistant, allSkills: List<SkillMetadata>): List<Tool> {
    val skills = createSkillTools(assistant.enabledSkills, allSkills)
    if (skills.isEmpty()) return emptyList()
    return (listOf(skillGetContentTool(assistant.enabledSkills, allSkills) { null }) + skills).map { tool ->
        tool.copy(execute = { error("UI metadata cannot execute tools") })
    }
}

/** Uses the same prompt factory as generation; reads only files in the supplied metadata snapshot. */
internal fun skillPromptForAssistant(
    assistant: Assistant,
    allSkills: List<SkillMetadata>,
    model: Model = Model(),
): String {
    // Generation uses SkillManager's capped reader. The factory's direct-file fallback
    // has no cap, so exclude oversized auto-load files before invoking that fallback.
    val readable = allSkills.filter { skill ->
        !skill.autoLoad || runCatching {
            val file = if (skill.autoLoadPath.isNullOrBlank()) skill.skillFile
                else SkillPaths.resolveSkillFile(skill.skillDir, skill.autoLoadPath)
            file != null && file.length() <= SkillManager.MAX_SKILL_FILE_BYTES
        }.getOrDefault(false)
    }
    return createSkillTools(assistant.enabledSkills, readable)
        .firstOrNull { it.name == "use_skill" }
        ?.systemPrompt(model, emptyList()).orEmpty()
}
