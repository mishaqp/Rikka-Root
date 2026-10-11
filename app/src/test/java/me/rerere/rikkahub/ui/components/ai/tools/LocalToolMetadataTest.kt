package me.rerere.rikkahub.ui.components.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalToolMetadataTest {
    @get:Rule val temp = TemporaryFolder()
    private fun tool(description: String, schema: InputSchema? = null) = Tool(
        name = "sample",
        description = description,
        parameters = { schema },
        execute = { error("Inspecting a tool must never execute it") },
    )

    @Test fun emptyCapabilitiesCostNoTokens() {
        assertEquals(0L, estimateToolTokens(emptyList()))
    }

    @Test fun realDescriptionsAndSchemasIncreaseTheEstimate() {
        val short = tool("Short")
        val long = tool("Long description ".repeat(50))
        val schema = InputSchema.Obj(JsonObject(mapOf(
            "query" to JsonObject(mapOf("type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("Search query ".repeat(50))))
        )), required = listOf("query"))

        assertTrue(estimateToolTokens(listOf(long)) > estimateToolTokens(listOf(short)))
        assertTrue(estimateToolTokens(listOf(tool("Short", schema))) > estimateToolTokens(listOf(short)))
    }

    @Test fun skillPromptCharactersAreIncludedAtFourCharactersPerToken() {
        assertEquals(25L, estimateToolTokens(emptyList(), listOf("x".repeat(100))))
        assertEquals(1L, estimateToolTokens(emptyList(), listOf("x")))
    }

    @Test fun schemaUsesJsonInsteadOfTheKotlinDataClassRepresentation() {
        val emptySchema = InputSchema.Obj(JsonObject(emptyMap()))
        assertEquals("{\"type\":\"object\",\"properties\":{}}", serializedToolSchema(tool("", emptySchema)))
        assertEquals(serializedToolSchema(tool("", emptySchema)), serializedToolSchema(tool("")))
    }

    @Test fun skillPromptIncludesOnlyEnabledBodiesAndKeepsLazyDiscovery() {
        val coreDir = temp.newFolder("core")
        coreDir.resolve("SKILL.md").writeText("---\nname: core\ndescription: core\n---\n  Core instructions  ")
        val lazyDir = temp.newFolder("lazy")
        lazyDir.resolve("SKILL.md").writeText("Lazy body is loaded on demand")
        val skills = listOf(
            SkillMetadata("core", "Core", autoLoad = true, skillDir = coreDir),
            SkillMetadata("lazy", "Discovery & hints", skillDir = lazyDir),
            SkillMetadata("disabled", "Disabled", autoLoad = true, skillDir = lazyDir),
        )
        val assistant = Assistant(enabledSkills = setOf("core", "lazy"))

        val prompt = skillPromptForAssistant(assistant, skills, Model())

        assertTrue(prompt.startsWith("Core instructions\n\n**Skills**\n"))
        assertTrue(prompt.contains("<description>Discovery &amp; hints</description>"))
        assertTrue(!prompt.contains("Lazy body is loaded on demand"))
        assertTrue(!prompt.contains("disabled"))
        assertEquals(listOf("skill_get_content", "use_skill"), skillToolsForAssistant(assistant, skills).map { it.name })
    }

    @Test fun autoLoadPathUsesTheSidecarAndNeverFallsBackToTheDefaultBody() {
        val dir = temp.newFolder("sidecar")
        dir.resolve("SKILL.md").writeText("Default body")
        dir.resolve("SOUL.md").writeText("  Sidecar instructions  ")
        val assistant = Assistant(enabledSkills = setOf("core"))
        val skill = SkillMetadata("core", "Core", autoLoad = true, autoLoadPath = "SOUL.md", skillDir = dir)

        assertEquals("Sidecar instructions\n\n", skillPromptForAssistant(assistant, listOf(skill), Model()))
        assertEquals("", skillPromptForAssistant(assistant, listOf(skill.copy(autoLoadPath = "missing.md")), Model()))
    }

    @Test fun autoLoadPreviewObservesTheRuntimeFileSizeLimit() {
        val dir = temp.newFolder("large")
        dir.resolve("SKILL.md").writeText("Default instructions")
        dir.resolve("SOUL.md").writeText("x".repeat(SkillManager.MAX_SKILL_FILE_BYTES.toInt() + 1))
        val skill = SkillMetadata("core", "Core", autoLoad = true, autoLoadPath = "SOUL.md", skillDir = dir)

        assertEquals("", skillPromptForAssistant(Assistant(enabledSkills = setOf("core")), listOf(skill), Model()))
    }

    @Test fun inspectingMissingSkillsDoesNotCreateTheirDirectory() {
        val missing = File(temp.root, "missing")
        val skill = SkillMetadata("core", "Core", autoLoad = true, skillDir = missing)

        assertEquals("", skillPromptForAssistant(Assistant(enabledSkills = setOf("core")), listOf(skill), Model()))
        assertTrue(!missing.exists())
    }
}
