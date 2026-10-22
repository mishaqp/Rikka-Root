package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.RootMode
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandExecutionGuidanceTest {
    @Test fun `bundled operating instructions require observed completion before success claims`() = runBlocking {
        val soul = readAgentInstructions("SOUL.md")
        assertTrue(soul, soul.contains("PID"))
        assertTrue(soul, soul.contains("startup log", ignoreCase = true))
        assertTrue(soul, soul.contains("does not prove completion", ignoreCase = true))
        assertTrue(soul, soul.contains("after an error", ignoreCase = true))
        assertTrue(soul, soul.contains("verify the requested result", ignoreCase = true))
    }

    @Test fun `tool reference explains malformed commands and root only for an actual denied read`() = runBlocking {
        val tools = readAgentInstructions("TOOLS.md")
        listOf("sleep40", "sleep12", "chmod755", "chmod0644", "missing space", "Permission denied", "/data/adb", "root_exec")
            .forEach { required -> assertTrue("Missing instruction: $required", tools.contains(required, ignoreCase = true)) }
    }

    @Test fun `root system guide requires result verification after errors and background startup`() {
        val root = Tool("root_exec", "root test", execute = { emptyList() })
        val guide = RootMode.withGuide(root).systemPrompt(Model(), emptyList())
        assertTrue(guide, guide.contains("PID"))
        assertTrue(guide, guide.contains("стартовая строка журнала"))
        assertTrue(guide, guide.contains("не подтверждают завершение"))
        assertTrue(guide, guide.contains("После ошибки"))
        assertTrue(guide, guide.contains("проверь результат"))
    }

    private suspend fun readAgentInstructions(path: String): String {
        val root = listOf(File("src/main/assets/default-skills/agent-core"), File("app/src/main/assets/default-skills/agent-core"))
            .first { it.resolve("SKILL.md").isFile }
        val tool = createSkillTools(setOf("agent-core"), listOf(SkillMetadata("agent-core", "Agent instructions",
            autoLoad = true, autoLoadPath = "SOUL.md", skillDir = root)))
            .single { it.name == "use_skill" }
        if (path == "SOUL.md") return tool.systemPrompt(Model(), emptyList())
        return (tool.execute(buildJsonObject { put("name", "agent-core"); put("path", path) }).single() as UIMessagePart.Text).text
    }
}
