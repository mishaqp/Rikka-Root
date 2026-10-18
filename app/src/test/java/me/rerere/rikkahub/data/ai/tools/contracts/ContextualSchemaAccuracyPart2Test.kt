package me.rerere.rikkahub.data.ai.tools.contracts

import android.app.Application
import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.parametersOrEmpty
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.tools.local.notificationTool
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.skills.SkillInstallCaller
import me.rerere.rikkahub.skills.SkillSaver
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.skillInstallFromTextTool
import me.rerere.rikkahub.skills.skillInstallFromUrlTool
import me.rerere.rikkahub.skills.js.JsSkillRunner
import me.rerere.rikkahub.skills.js.runJsTool
import me.rerere.rikkahub.subagent.subagentDispatchTool
import me.rerere.rikkahub.subagent.subagentListTool
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Constants are verified on the actual public creators, not a copied contract/schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ContextualSchemaAccuracyPart2Test {
    private fun dispatch() = subagentDispatchTool(mockk(relaxed = true), mockk(relaxed = true))
    private fun field(tool: Tool, name: String) = (tool.parametersOrEmpty() as InputSchema.Obj).properties.getValue(name).jsonObject
    private fun attr(tool: Tool, name: String, key: String, expected: Int) =
        assertEquals("${tool.name}.$name.$key must publish its actual fixed contract", JsonPrimitive(expected), field(tool, name)[key])
    private fun manager() = mockk<SkillManager>().also { every { it.listSkills() } returns emptyList() }
    private val caller get() = SkillInstallCaller({ emptySet() }, {})
    private suspend fun result(tool: Tool, input: JsonObject) =
        Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject

    @Test fun subagentNumericBoundsAndDefaultsAreAvailableToTheModelAsSchemaKeywords() {
        val tool = dispatch()
        attr(tool, "timeout_seconds", "minimum", 1); attr(tool, "timeout_seconds", "maximum", 480); attr(tool, "timeout_seconds", "default", 300)
        attr(tool, "max_trips", "minimum", 1); attr(tool, "max_trips", "maximum", 32); attr(tool, "max_trips", "default", 12)
    }

    @Test fun subagentTextAndToolListBoundsPublishOnlyTheExistingFixedLimits() {
        val tool = dispatch()
        assertTrue("task remains required", "task" in (tool.parametersOrEmpty() as InputSchema.Obj).required.orEmpty())
        attr(tool, "task", "minLength", 1); attr(tool, "task", "maxLength", 100000)
        attr(tool, "label", "maxLength", 60); attr(tool, "system_prompt", "maxLength", 32000)
        attr(tool, "model_id", "minLength", 1); attr(tool, "tools", "maxItems", 256)
        assertEquals(JsonPrimitive(1), field(tool, "tools").getValue("items").jsonObject["minLength"])
        assertFalse("Available models are dynamic", field(tool, "model_id").containsKey("enum"))
        assertFalse("Allowed tools belong to the current caller, not a static enum", field(tool, "tools").getValue("items").jsonObject.containsKey("enum"))
    }

    @Test fun subagentBooleanDefaultsMatchActualDispatchAndListing() {
        val tool = dispatch()
        assertEquals(JsonPrimitive(false), field(tool, "run_in_background")["default"])
        assertEquals(JsonPrimitive(false), field(tool, "no_result")["default"])
        assertEquals(JsonPrimitive(false), field(subagentListTool(mockk(relaxed = true), mockk(relaxed = true)), "active_only")["default"])
    }

    @Test fun runJsDataLimitDescribesUtf16LengthAndMatchesTheActualNonAsciiBoundary() = runBlocking {
        val manager = manager()
        every { manager.getSkillDir("missing-contract-skill") } returns null
        val runner = mockk<JsSkillRunner>()
        val tool = runJsTool(mockk<Context>(), manager, runner, mockk())
        // A valid JSON string that is 65536 UTF-16 units and nearly128KiB in UTF-8.
        val data = "\"" + "я".repeat(65534) + "\""
        assertEquals(65536, data.length)
        assertTrue(data.toByteArray(Charsets.UTF_8).size > 65536)
        val accepted = result(tool, buildJsonObject { put("skill_name", "missing-contract-skill"); put("data", data) })
        assertEquals("The length guard accepts multibyte text at65536 units", JsonPrimitive("skill_not_found"), accepted["error"])
        val rejected = result(tool, buildJsonObject { put("skill_name", "missing-contract-skill"); put("data", data + " ") })
        assertEquals(JsonPrimitive("data_too_large"), rejected["error"])
        coVerify(exactly = 0) { runner.runScript(any(), any(), any(), any()) }
        lengthUnits(field(tool, "data"), 65536)
    }

    @Test fun skillTextLimitDescribesUtf16LengthAndMatchesTheActualNonAsciiBoundary() = runBlocking {
        var saves = 0
        val saver = object : SkillSaver {
            override fun saveSkill(name: String, content: String): SkillMetadata? { saves++; return null }
        }
        val tool = skillInstallFromTextTool(SkillUrlImporter(saver), caller, manager())
        val prefix = "---\nname: utf16-contract\ndescription: Boundary fixture\n---\n"
        val text = prefix + "я".repeat(262144 - prefix.length)
        assertEquals(262144, text.length)
        assertTrue(text.toByteArray(Charsets.UTF_8).size > 262144)
        val accepted = result(tool, buildJsonObject { put("content", text) })
        assertEquals("The importer reaches the save seam at262144 units, without disk or UI", JsonPrimitive("save_failed"), accepted["error"])
        assertEquals(1, saves)
        val rejected = result(tool, buildJsonObject { put("content", text + "я") })
        assertEquals(JsonPrimitive("body_too_large"), rejected["error"])
        assertEquals("Oversized input must not reach persistence", 1, saves)
        lengthUnits(field(tool, "content"), 262144)
    }

    @Test fun urlInstallerDescribesApprovalWithoutPromisingReviewOfTheFetchedFinalNameBeforeSave() {
        val tool = skillInstallFromUrlTool(mockk(), caller, manager())
        assertTrue("Existing per-call approval stays mandatory", tool.needsApproval(buildJsonObject { put("url", "https://example.test/SKILL.md") }))
        truthfulInstallerApproval(tool)
    }

    @Test fun textInstallerDescribesApprovalWithoutPromisingASecondReviewOfTheParsedFinalName() {
        val tool = skillInstallFromTextTool(mockk(), caller, manager())
        assertTrue("Existing per-call approval stays mandatory", tool.needsApproval(buildJsonObject { put("content", "source text") }))
        truthfulInstallerApproval(tool)
    }

    @Test fun notificationIdSchemaPublishesTheActualAndroidIntCeilingBeforePlatformEffects() = runBlocking {
        val context = mockk<Context>()
        val tool = notificationTool(context)
        for (id in listOf(2147483648L, Long.MAX_VALUE)) {
            val rejected = result(tool, buildJsonObject { put("title", "Заголовок"); put("id", id) })
            assertTrue(rejected.toString(), rejected.containsKey("error"))
            assertTrue("The actual argument guard explains id before Android is contacted", rejected.toString().contains("id"))
        }
        verify { context wasNot Called }
        attr(tool, "id", "minimum", 0)
        attr(tool, "id", "maximum", Int.MAX_VALUE)
        assertFalse("The generated default ID is dynamic", field(tool, "id").containsKey("default"))
    }

    private fun lengthUnits(field: JsonObject, maximum: Int) {
        val description = field["description"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("Describe the actual length ceiling $maximum in UTF-16 units: $description",
            description.contains(maximum.toString()) && description.contains("UTF-16", ignoreCase = true))
        assertFalse("A String.length ceiling is not a byte ceiling: $description",
            Regex("(?i)(?:[Кк]и?[Бб]|Ki?B|байт)").containsMatchIn(description))
    }

    private fun truthfulInstallerApproval(tool: Tool) {
        val description = tool.description
        val promisesFinalNameReview = Regex(
            "(?is)(?:reviews?\\s*(?:and|\\+)?\\s*approves?|проверяет\\s+и\\s+одобряет).{0,100}(?:skill\\s+name|имя\\s+навыка|итогов[а-яё]*\\s+им[а-яё]*)"
        )
        assertFalse("The final source-declared name is parsed only during execute; approval must not claim another pre-save name review: $description",
            promisesFinalNameReview.containsMatchIn(description))
        assertTrue("Keep per-call approval explicit in the public contract", Regex("(?i)approv|одобр|подтвержд").containsMatchIn(description))
        assertTrue("The actual result name must remain documented", Regex("(?i)\\bname\\b").containsMatchIn(description))
    }
}
