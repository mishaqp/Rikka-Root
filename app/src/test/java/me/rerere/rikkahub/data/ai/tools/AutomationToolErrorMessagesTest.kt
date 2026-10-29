package me.rerere.rikkahub.data.ai.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.automation.*
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.NULL_CONTEXT
import me.rerere.rikkahub.costguards.checkTokenUsageTool
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.skills.*
import me.rerere.rikkahub.skills.js.runJsTool
import me.rerere.rikkahub.reliability.BugReportBuilder
import me.rerere.rikkahub.reliability.generateBugReportTool
import me.rerere.rikkahub.subagent.*
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.tools.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AutomationToolErrorMessagesTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun execute(tool: Tool, args: String = "{}"): JsonObject = Json.parseToJsonElement(
        tool.execute(Json.parseToJsonElement(args)).filterIsInstance<UIMessagePart.Text>().first().text,
    ).jsonObject

    private fun assertRussianRecovery(payload: JsonObject, code: String, field: String = "error") {
        assertEquals(code, payload[field]?.jsonPrimitive?.content)
        val message = listOf("message", "recovery").mapNotNull { payload[it]?.jsonPrimitive?.content }
        assertTrue("Russian explanation and action required: $payload", message.any { Regex("[А-Яа-яЁё]").containsMatchIn(it) })
        assertTrue("Recovery action required: $payload", message.any {
            Regex("Укажите|Проверьте|Повторите|Выберите|Включите|Откройте|Дождитесь|Вызовите|Сократите|Используйте|Удалите|Создайте|Завершите|Вернитесь|Настройте", RegexOption.IGNORE_CASE).containsMatchIn(it)
        })
    }

    @Test fun `browser argument errors preserve codes and explain recovery in Russian`() = runBlocking {
        listOf(
            browserOpenTool(NULL_CONTEXT) to "missing_url",
            browserClickTool() to "missing_selector",
            browserTypeTool() to "missing_selector",
            browserScrollTool() to "missing_direction",
            browserSubmitTool() to "missing_selector",
            browserSelectTool() to "missing_selector",
            browserPressKeyTool() to "missing_key",
            browserEvalJsTool() to "missing_code",
            browserWaitForTool() to "missing_selector",
            browserClickAndReadTool() to "missing_selector",
            browserDoneTool() to "missing_summary",
        ).forEach { (tool, code) -> assertRussianRecovery(execute(tool), code) }
    }

    @Test fun `browser session errors offer Russian actions`() {
        assertRussianRecovery(BrowserController.taskTimeoutEnvelope(), "browser_task_timeout")
        assertRussianRecovery(BrowserController.sessionLostEnvelope(), "browser_session_lost")
        assertRussianRecovery(BrowserController.bindBusyEnvelope(), "browser_busy")
    }

    @Test fun `skill content lookup preserves available list and explains failures`() = runBlocking {
        val tool = skillGetContentTool(emptySet(), emptyList()) { null }
        assertRussianRecovery(execute(tool), "missing_required_arg")
        assertRussianRecovery(execute(tool, """{"name":"unknown"}"""), "skill_not_found")
    }

    @Test fun `use skill explains missing arguments and oversized files`() = runBlocking {
        val dir = folder.newFolder()
        dir.resolve("SKILL.md").writeText("x".repeat(600_000))
        val tool = createSkillTools(setOf("large"), listOf(SkillMetadata("large", "", skillDir = dir))).single()
        assertRussianRecovery(execute(tool), "missing_required_arg")
        assertRussianRecovery(execute(tool, """{"name":"large"}"""), "skill_file_too_large")
    }

    @Test fun `installation errors explain rejected source without changing importer codes`() = runBlocking {
        val importer = mockk<SkillUrlImporter>()
        val manager = mockk<me.rerere.rikkahub.data.files.SkillManager>(relaxed = true)
        val caller = SkillInstallCaller({ emptySet() }, {})
        assertRussianRecovery(execute(skillInstallFromUrlTool(importer, caller, manager)), "missing_url")
        assertRussianRecovery(execute(skillInstallFromTextTool(importer, caller, manager)), "missing_content")
        coEvery { importer.importFromText(any(), any(), any()) } returns SkillUrlImporter.Result.Err("parse_failed", "Unknown format")
        assertRussianRecovery(execute(skillInstallFromTextTool(importer, caller, manager), """{"content":"broken"}"""), "parse_failed")
    }

    @Test fun `run js missing skill has Russian recovery`() = runBlocking {
        assertRussianRecovery(execute(runJsTool(NULL_CONTEXT, mockk(), mockk(), mockk())), "missing_skill_name")
    }

    @Test fun `external automation errors accompany codes with Russian recovery`() = runBlocking {
        val config = mockk<ExternalAutomationConfig>()
        assertRussianRecovery(execute(externalAutomationSetEnabledTool(config)), "invalid_enabled")
        assertRussianRecovery(execute(externalAutomationAddTrustedPackageTool(config), """{"package_name":"wrong-package"}"""), "invalid_package_name")
        assertRussianRecovery(execute(externalAutomationRemoveTrustedPackageTool(config)), "invalid_package_name")
    }

    @Test fun `external automation rejected history has a Russian recovery`() = runBlocking {
        val config = mockk<ExternalAutomationConfig>()
        every { config.enabledFlow } returns flowOf(false)
        every { config.trustedPackagesFlow } returns flowOf(emptySet())
        every { config.recentInvocationsFlow } returns flowOf(listOf(
            ExternalAutomationConfig.InvocationLog(1, "test.app", "RUN_TASK", "rejected:untrusted_or_disabled"),
            ExternalAutomationConfig.InvocationLog(2, "test.app", "RUN_TASK", "deduped:request_id_replay"),
        ))
        val entries = execute(externalAutomationStatusTool(config))["recent_invocations"]!!.jsonArray
        assertRussianRecovery(entries[0].jsonObject, "rejected:untrusted_or_disabled", "status")
        assertRussianRecovery(entries[1].jsonObject, "deduped:request_id_replay", "status")
    }

    @Test fun `schedule validation errors keep machine code and give Russian correction`() = runBlocking {
        val tools = createCronJobTools(mockk(), mockk(), mockk(),
            Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = emptyList()), { emptySet() })
        assertRussianRecovery(execute(tools.first { it.name == "schedule_job" }), "invalid_name", "code")
        tools.filter { it.name in setOf("get_job_history", "delete_job", "pause_job", "resume_job", "trigger_job_now") }
            .forEach { assertRussianRecovery(execute(it), "missing_id", "code") }
    }

    @Test fun `schedule history explains safe outcome code in Russian`() = runBlocking {
        val repo = mockk<me.rerere.rikkahub.data.repository.ScheduledJobRepository>()
        val runs = mockk<me.rerere.rikkahub.data.repository.ScheduledJobRunRepository>()
        coEvery { repo.getOwned(any(), any()) } returns mockk()
        coEvery { runs.recentWithLookahead(any(), any()) } returns listOf(
            me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity("run", "job", 1, "manual", 0,
                status = "blocked", outcomeCode = "android_permission_required"),
        )
        val tools = createCronJobTools(repo, runs, mockk(),
            Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = emptyList()), { emptySet() })
        val entry = execute(tools.first { it.name == "get_job_history" }, """{"id":"job"}""")["runs"]!!.jsonArray.single().jsonObject
        assertRussianRecovery(entry, "android_permission_required", "code")
    }

    @Test fun `schedule history warns incomplete root output requires checking before retry`() = runBlocking {
        val repo = mockk<me.rerere.rikkahub.data.repository.ScheduledJobRepository>()
        val runs = mockk<me.rerere.rikkahub.data.repository.ScheduledJobRunRepository>()
        coEvery { repo.getOwned(any(), any()) } returns mockk()
        coEvery { runs.recentWithLookahead(any(), any()) } returns listOf(
            me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity("run", "job", 1, "manual", 0,
                status = "indeterminate", outcomeCode = "root_output_incomplete"),
        )
        val tools = createCronJobTools(repo, runs, mockk(),
            Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = emptyList()), { emptySet() })
        val entry = execute(tools.first { it.name == "get_job_history" }, """{"id":"job"}""")["runs"]!!.jsonArray.single().jsonObject
        assertEquals("root_output_incomplete", entry["code"]?.jsonPrimitive?.content)
        val message = entry["message"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("вывод", ignoreCase = true) && message.contains("неполн", ignoreCase = true))
        assertTrue(message, message.contains("Проверьте", ignoreCase = true))
    }

    @Test fun `subagent unknown run and terminal failure preserve codes and explain recovery`() = runBlocking {
        val owner = SubAgentOwner("assistant", "conversation")
        val registry = SubAgentRegistry()
        assertRussianRecovery(execute(subagentGetTool(registry, owner), """{"id":"unknown"}"""), "unknown_run")
        assertRussianRecovery(execute(subagentCancelTool(registry, owner)), "unknown_run")
        assertRussianRecovery(encodeRun(SubAgentRun("id", owner, "test", "model", errorCode = "run_failed")), "run_failed")
    }

    @Test fun `subagent incomplete root output warns against automatic retry`() {
        val run = SubAgentRun("id", SubAgentOwner("assistant", "conversation"), "test", "model", errorCode = "root_output_incomplete")
        val encoded = encodeRun(run)
        assertEquals("root_output_incomplete", encoded["error"]?.jsonPrimitive?.content)
        val message = encoded["message"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("вывод", ignoreCase = true) && message.contains("неполн", ignoreCase = true))
        assertTrue(message, message.contains("Проверьте", ignoreCase = true))
    }

    @Test fun `token usage unavailable chat tells user how to recover`() = runBlocking {
        assertRussianRecovery(execute(checkTokenUsageTool(Assistant(), null) { null }), "no_conversation")
    }

    @Test fun `workflow missing definition and missing id give Russian actions`() = runBlocking {
        val repo = mockk<WorkflowRepository>()
        assertRussianRecovery(execute(workflowCreateTool(repo, { emptyList() }, "owner")), "missing_definition")
        assertRussianRecovery(execute(workflowGetTool(repo)), "missing_id")
        assertRussianRecovery(execute(workflowDeleteTool(repo, "owner")), "missing_id")
        assertRussianRecovery(execute(workflowSetEnabledTool(repo, "owner")), "missing_id")
    }

    @Test fun `workflow persistence errors hide raw exceptions and preserve code`() = runBlocking {
        val repo = mockk<WorkflowRepository>()
        coEvery { repo.getById(any()) } returns null
        coEvery { repo.upsert(any()) } throws IOException("write failed token=private")
        val payload = execute(workflowCreateTool(repo, { listOf("show_toast") }, "owner"),
            """{"definition":{"id":"id","name":"test","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}}""")
        assertRussianRecovery(payload, "persist_failed")
        assertFalse(payload.toString().contains("token=private"))
    }

    @Test fun `bug report build failure explains recovery without leaking exception`() = runBlocking {
        val builder = mockk<BugReportBuilder>()
        coEvery { builder.build() } throws IOException("token=private")
        val payload = execute(generateBugReportTool(NULL_CONTEXT, builder))
        assertRussianRecovery(payload, "report_failed")
        assertFalse(payload.toString().contains("token=private"))
    }
}
