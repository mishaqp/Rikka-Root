package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import android.content.ContextWrapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.prepareArguments
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.skills.*
import me.rerere.rikkahub.skills.js.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class SkillRuntimeMessagesPart2Test {
    @get:Rule val temporary = TemporaryFolder()
    private fun body(parts: List<UIMessagePart>): JsonObject {
        val raw = (parts.single() as UIMessagePart.Text).text
        assertFalse(raw, raw.contains('\n'))
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        assertTrue("Expected a compact JSON object, got: $raw", parsed is JsonObject)
        return parsed as JsonObject
    }
    private fun russianDetail(body: JsonObject, code: String) {
        assertEquals(body.toString(), code, body["error"]?.jsonPrimitive?.content)
        for (field in listOf("message", "detail")) assertTrue("$field: $body",
            body[field]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
    }
    private fun skill(): Pair<SkillMetadata, me.rerere.ai.core.Tool> {
        val dir = temporary.newFolder(); File(dir, "SKILL.md").writeText("USER English instructions stay exact")
        val metadata = SkillMetadata("contract-skill", "USER English description", skillDir = dir)
        return metadata to createSkillTools(setOf(metadata.name), listOf(metadata)).single()
    }
    private fun manager(): Pair<SkillManager, android.content.Context> {
        val files = temporary.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files }
        return SkillManager(context, mockk()) to context
    }
    private fun installDemo(manager: SkillManager) {
        val dir = File(manager.getSkillsDir(), "contract-demo").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: contract-demo\ndescription: fixture\n---\nUSER BODY")
        File(dir, "index.html").writeText("<html>USER SCRIPT</html>")
    }

    @Test fun unknownUseSkillNameHasRussianDetailWithoutChangingTheAvailableList() = runBlocking {
        val (_, tool) = skill()
        val result = body(tool.execute(buildJsonObject { put("name", "missing-contract-skill") }))
        russianDetail(result, "skill_not_enabled")
        assertEquals(listOf("contract-skill"), result.getValue("available_skills").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun useSkillOutsidePathHasRussianDetailAndDoesNotReadForeignFile() = runBlocking {
        val (metadata, tool) = skill()
        val foreign = File(metadata.skillDir.parentFile, "outside.md").apply { writeText("PRIVATE OUTSIDE") }
        val result = body(tool.execute(buildJsonObject { put("name", metadata.name); put("path", "../outside.md") }))
        russianDetail(result, "path_outside_skill")
        assertFalse(result.toString(), result.toString().contains("PRIVATE OUTSIDE"))
        assertEquals("PRIVATE OUTSIDE", foreign.readText())
    }

    @Test fun lazySkillInstructionIsRussianWhileUserBodyAndMetadataRemainExact() {
        val (_, tool) = skill()
        val prompt = tool.systemPrompt(Model(), emptyList())
        assertTrue(prompt, prompt.contains(Regex("[Нн]авык|[Нн]авыков|[Нн]авыки")))
        assertFalse(prompt, prompt.contains("You have access to the following skills"))
        assertTrue(prompt, prompt.contains("USER English description"))
    }

    @Test fun useSkillSuccessStillReturnsTheOriginalInstructions() = runBlocking {
        val (metadata, tool) = skill()
        val raw = (tool.execute(buildJsonObject { put("name", metadata.name) }).single() as UIMessagePart.Text).text
        assertEquals("USER English instructions stay exact", raw)
    }

    @Test fun unknownJsSkillReturnsRussianDetailBeforeRunnerExecution() = runBlocking {
        val (manager, context) = manager()
        val runner = mockk<JsSkillRunner>()
        val result = body(runJsTool(context, manager, runner, mockk()).execute(buildJsonObject { put("skill_name", "missing-contract-skill") }))
        russianDetail(result, "skill_not_found")
        coVerify(exactly = 0) { runner.runScript(any(), any(), any(), any()) }
    }

    @Test fun outsideJsScriptUsesTheRealSkillResolverAndReturnsRussianDetail() = runBlocking {
        val (manager, context) = manager(); installDemo(manager)
        val runner = mockk<JsSkillRunner>()
        val result = body(runJsTool(context, manager, runner, mockk()).execute(buildJsonObject {
            put("skill_name", "contract-demo"); put("script", "../outside.html")
        }))
        russianDetail(result, "invalid_script_path")
        coVerify(exactly = 0) { runner.runScript(any(), any(), any(), any()) }
    }

    @Test fun htmlInstallErrorHasRussianToolDetailAndNeverSavesOrEnablesAnything() = runBlocking {
        val (manager, _) = manager()
        var saves = 0; var enables = 0
        val saver = object : SkillSaver { override fun saveSkill(name: String, content: String): SkillMetadata? { saves++; error("HTML must never be saved") } }
        val importer = SkillUrlImporter(saver)
        val caller = SkillInstallCaller({ emptySet() }, { enables++ })
        val result = body(skillInstallFromTextTool(importer, caller, manager).execute(buildJsonObject { put("content", "<html><body>Not a skill</body></html>") }))
        russianDetail(result, "html_response")
        assertEquals(0, saves); assertEquals(0, enables)
    }

    @Test fun authoredJsEmptyResultFallbackIsCompactJson() = runBlocking {
        val (manager, context) = manager(); installDemo(manager)
        val runner = mockk<JsSkillRunner>()
        coEvery { runner.runScript(any(), any(), any(), any()) } returns JsSkillRunner.Result.Ok(JsSkillRunner.ParsedResult())
        val result = body(runJsTool(context, manager, runner, mockk()).execute(buildJsonObject { put("skill_name", "contract-demo") }))
        assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
    }

    @Test fun externalJsTextAndScriptErrorsRemainVerbatim() = runBlocking {
        val (manager, context) = manager(); installDemo(manager)
        val runner = mockk<JsSkillRunner>()
        val tool = runJsTool(context, manager, runner, mockk())
        coEvery { runner.runScript(any(), any(), any(), any()) } returns JsSkillRunner.Result.Ok(JsSkillRunner.ParsedResult(text = "USER English text\nexact stdout"))
        assertEquals("USER English text\nexact stdout", (tool.execute(buildJsonObject { put("skill_name", "contract-demo") }).single() as UIMessagePart.Text).text)
        coEvery { runner.runScript(any(), any(), any(), any()) } returns JsSkillRunner.Result.Ok(JsSkillRunner.ParsedResult(error = "USER SCRIPT ERROR: keep exact"))
        val failure = body(tool.execute(buildJsonObject { put("skill_name", "contract-demo") }))
        assertEquals("js_error", failure["error"]?.jsonPrimitive?.content)
        assertEquals("USER SCRIPT ERROR: keep exact", failure["detail"]?.jsonPrimitive?.content)
    }

    @Test fun blankSkillContentNameReachesRussianDetailThroughPreparedRuntimeArguments() = runBlocking {
        val (metadata, _) = skill()
        var reads = 0
        val tool = skillGetContentTool(setOf(metadata.name), listOf(metadata)) { reads++; null }
        for (name in listOf("", " ")) {
            val prepared = tool.prepareArguments(buildJsonObject { put("name", name) })
            assertEquals(JsonPrimitive(name), prepared["name"])
            val result = body(tool.execute(prepared))
            assertEquals("missing_required_arg", result["error"]?.jsonPrimitive?.content)
            assertEquals(listOf(metadata.name), result["available_skills"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("Blank names must never reach the content reader", 0, reads)
            russianDetail(result, "missing_required_arg")
        }
    }
}
