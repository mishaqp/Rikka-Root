package me.rerere.rikkahub.skills

import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledAgentSkillsTest {
    private fun assetRoot(): File = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .first { it.resolve("default-skills/agent-core/SKILL.md").isFile }

    @Test fun `catalog bundled installs resolve all actual manifests and JS entry links`() {
        val assets = assetRoot()
        val catalog = parseSkillCatalogJson(assets.resolve("skill-catalog.json").readText())
        assertEquals(12, catalog.skills.size)
        catalog.skills.filter { it.isBundled }.forEach { entry ->
            val root = assets.resolve("default-skills/${entry.name}")
            val manifest = root.resolve("SKILL.md")
            assertTrue(entry.name, manifest.isFile)
            assertEquals(entry.name, SkillFrontmatterParser.parse(manifest.readText())["name"])
            if (entry.compatibility == "js") {
                assertTrue("${entry.name} entry missing", root.resolve("scripts/index.html").isFile)
            }
        }
    }

    @Test fun `bundled QR and mood chart HTML load local library files`() {
        val assets = assetRoot().resolve("default-skills")
        listOf("qr-code/scripts/index.html", "mood-tracker/assets/dashboard.html").forEach { relative ->
            val html = assets.resolve(relative)
            val sources = Regex("<script[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .findAll(html.readText()).map { it.groupValues[1] }.toList()
            assertTrue("No external script tags found in $relative", sources.isNotEmpty())
            sources.forEach { source ->
                assertFalse("Unexpected CDN dependency $source", source.startsWith("http"))
                assertTrue("Missing bundled dependency $source", html.parentFile.resolve(source).isFile)
            }
        }
    }

    @Test fun `core doctrines reference Root boundaries without removed tool calls`() {
        val assets = assetRoot().resolve("default-skills")
        val texts = listOf("agent-core/SKILL.md", "agent-core/SOUL.md", "agent-core/HEARTBEAT.md", "agent-core/TOOLS.md", "autonomous-agent/SKILL.md")
            .map { assets.resolve(it).readText() }
        val removed = Regex("`(?:telegram_[a-z_]+|whisper_[a-z_]+|transcribe_audio_file|ssh_save_host|web_fetch)(?:`|\\()")
        texts.forEach { assertFalse("Removed tool remains in core doctrine", removed.containsMatchIn(it)) }
        assertTrue(texts.joinToString().contains("ConversationConfig"))
        assertTrue(texts.joinToString().contains("HARDLINE"))
        assertTrue(texts.joinToString().contains("AndroidKeyStore"))
    }
}
