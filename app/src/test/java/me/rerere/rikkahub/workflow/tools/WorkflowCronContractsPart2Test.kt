package me.rerere.rikkahub.workflow.tools

import android.app.Application
import androidx.room.Room
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.createCronJobTools
import me.rerere.rikkahub.data.db.dao.ScheduledJobRunDao
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.repository.ScheduledJobPayload
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.*
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkflowCronContractsPart2Test {
    private val owner = Uuid.random()
    private val definition = WorkflowDefinition("wf", "Сценарий", trigger = TriggerSpec.Manual,
        actions = listOf(WorkflowAction("get_time_info", buildJsonObject {})), authoringAssistantId = owner.toString())
    private val loaded = WorkflowRepository.Loaded(WorkflowEntity("wf", "Сценарий", definitionJson = WorkflowJson.encode(definition),
        createdAtMs = 1, updatedAtMs = 2, lastRunAtMs = 3, lastRunStatus = "SUCCESS"), definition)
    private suspend fun invoke(tool: Tool, args: String): JsonObject {
        val result = runCatching { tool.execute(Json.parseToJsonElement(args)) }
        assertNull("Tool must return an actionable JSON result: ${tool.name}: $args", result.exceptionOrNull())
        val text = (result.getOrThrow().single() as UIMessagePart.Text).text
        assertFalse(text, text.contains('\n'))
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun assertError(result: JsonObject) {
        assertTrue(result.toString(), result["ok"] == JsonPrimitive(false) || result["status"] == JsonPrimitive("error"))
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result["message"]?.jsonPrimitive?.content.orEmpty()))
    }
    @Test fun workflowHistoryKeepsTimesAndDurationsAsJsonNumbers() = runBlocking {
        val repo = mockk<WorkflowRepository> {
            coEvery { getById("wf") } returns loaded
            coEvery { lastRuns("wf", any()) } returns listOf(WorkflowRun(1, "wf", 123, WorkflowRunStatus.SUCCESS, 456, null))
        }
        val result = invoke(workflowGetTool(repo), """{"id":"wf"}""")
        assertEquals(JsonPrimitive(3), result["last_run_at_ms"])
        val run = result.getValue("history").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(123), run["fired_at_ms"])
        assertEquals(JsonPrimitive(456), run["duration_ms"])
    }
    @Test fun workflowListKeepsTimeAsNumberAndMissingTimeAsJsonNull() = runBlocking {
        val repo = mockk<WorkflowRepository> {
            coEvery { listAll() } returns listOf(loaded, loaded.copy(entity = loaded.entity.copy(id = "other", lastRunAtMs = null)))
        }
        val result = invoke(workflowListTool(repo), "{}")
        val items = result.getValue("items").jsonArray
        assertEquals(JsonPrimitive(3), items[0].jsonObject["last_run_at_ms"])
        assertEquals(JsonNull, items[1].jsonObject["last_run_at_ms"])
    }
    @Test fun workflowSetEnabledRejectsQuotedBooleanWithoutMutation() = runBlocking {
        var changed = false
        val repo = mockk<WorkflowRepository> {
            coEvery { getById("wf") } returns loaded
            coEvery { setEnabled(any(), any()) } coAnswers { changed = true }
        }
        assertError(invoke(workflowSetEnabledTool(repo, owner.toString()), """{"id":"wf","enabled":"false"}"""))
        assertFalse(changed)
    }
    @Test fun workflowListRejectsQuotedEnabledFilterWithoutQuerying() = runBlocking {
        var queried = false
        val repo = mockk<WorkflowRepository> {
            coEvery { listEnabled() } coAnswers { queried = true; emptyList() }
            coEvery { listAll() } coAnswers { queried = true; emptyList() }
        }
        assertError(invoke(workflowListTool(repo), """{"enabled_only":"true"}"""))
        assertFalse(queried)
    }
    @Test fun workflowAuthoringRejectsInvalidTypesBeforePersistence() = runBlocking {
        val writes = mutableListOf<WorkflowDefinition>()
        val repo = mockk<WorkflowRepository> {
            coEvery { getById(any()) } returns null
            coEvery { upsert(any()) } coAnswers { writes += firstArg<WorkflowDefinition>() }
        }
        val base = Json.parseToJsonElement("""{"name":"Сценарий","trigger":{"type":"manual"},"actions":[{"tool":"get_time_info","args":{}}]}""").jsonObject
        val badFields = listOf("enabled" to JsonPrimitive("false"), "cooldown_seconds" to JsonPrimitive("12"),
            "max_runs_per_day" to JsonPrimitive("2"), "conditions" to JsonPrimitive(true), "name" to JsonPrimitive(123))
        val tool = workflowCreateTool(repo, { listOf("get_time_info") }, owner.toString())
        for ((key, bad) in badFields) {
            val args = buildJsonObject { put("definition", JsonObject(base + (key to bad))) }
            assertError(invoke(tool, args.toString()))
        }
        assertTrue(writes.toString(), writes.isEmpty())
    }
    @Test fun workflowRunReportsTruncatedSummaryAndHowToInspectHistory() = runBlocking {
        val repo = mockk<WorkflowRepository> { coEvery { getById("wf") } returns loaded }
        val engine = mockk<WorkflowEngine> {
            coEvery { fire("wf") } returns WorkflowEngine.FireOutcome(WorkflowRunStatus.SUCCESS, null, "x".repeat(2100))
        }
        val result = invoke(workflowRunTool(engine, repo, owner.toString()), """{"id":"wf"}""")
        assertEquals("x".repeat(2000), result["output_summary"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content?.contains("workflow_get") == true)
    }
    @Test fun cronHistoryRejectsQuotedLimitWithoutReadingHistory() = runBlocking {
        val job = ScheduledJobEntity("job", owner.toString(), "chat", "", mode = "llm", scheduleType = "once", timezone = "UTC", createdAtMs = 1)
        val repo = mockk<ScheduledJobRepository> { coEvery { getOwned("job", owner.toString()) } returns job }
        var queried = false
        val runs = mockk<ScheduledJobRunRepository> {
            coEvery { recentWithLookahead(any(), any()) } coAnswers { queried = true; emptyList() }
        }
        val tool = createCronJobTools(repo, runs, mockk(), Conversation(assistantId = owner, messageNodes = emptyList()), { emptySet() })
            .single { it.name == "get_job_history" }
        assertError(invoke(tool, """{"id":"job","limit":"5"}"""))
        assertFalse(queried)
    }
    @Test fun cronListReportsLimitWithoutLeakingOtherOwnersOrPayload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = ScheduledJobRepository(db, object : CronPayloadCipher {
                override fun seal(jobId: String, plaintext: String) = plaintext
                override fun open(jobId: String, encrypted: String) = encrypted
            })
            val payload = Json.encodeToString(ScheduledJobPayload.serializer(), ScheduledJobPayload("Задание", "Секретный промпт"))
            val rows = (1..101).map { ScheduledJobEntity("job$it", owner.toString(), "chat", payload,
                mode = "llm", scheduleType = "once", timezone = "UTC", createdAtMs = it.toLong()) }
            rows.forEach { db.scheduledJobDao().insert(it) }
            db.scheduledJobDao().insert(rows.first().copy(id = "foreign", ownerAssistantId = Uuid.random().toString(), createdAtMs = 9999))
            val tool = createCronJobTools(repo, ScheduledJobRunRepository(db), mockk(),
                Conversation(assistantId = owner, messageNodes = emptyList()), { emptySet() }).single { it.name == "list_jobs" }
            val result = invoke(tool, "{}")
            val jobs = result["jobs"]?.jsonArray
            assertEquals(100, jobs?.size)
            assertFalse(jobs.toString(), jobs!!.any { it.jsonObject["id"] == JsonPrimitive("foreign") })
            assertEquals(JsonPrimitive(true), result["truncated"])
            assertEquals(JsonPrimitive(101), result["total"])
            assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result["message"]?.jsonPrimitive?.content.orEmpty()))
            assertFalse(result.toString(), result.toString().contains("Секретный промпт"))
        } finally { db.close() }
    }
    @Test fun workflowGetReportsOmittedHistoryWithoutClaimingExactTotal() = runBlocking {
        val rows = (1..12).map { WorkflowRun(it.toLong(), "wf", it.toLong(), WorkflowRunStatus.SUCCESS, 1, null) }
        val repo = mockk<WorkflowRepository> {
            coEvery { getById("wf") } returns loaded
            coEvery { lastRuns("wf", any()) } coAnswers { rows.take(secondArg()) }
        }
        val result = invoke(workflowGetTool(repo), """{"id":"wf"}""")
        assertEquals(10, result.getValue("history").jsonArray.size)
        assertEquals(JsonPrimitive(true), result["history_truncated"])
        assertEquals(JsonNull, result["history_total"])
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result["history_message"]?.jsonPrimitive?.content.orEmpty()))
    }
    @Test fun cronHistoryReportsOmittedRowsWithActionableContinuation() = runBlocking {
        val job = ScheduledJobEntity("job", owner.toString(), "chat", "", mode = "llm", scheduleType = "once", timezone = "UTC", createdAtMs = 1)
        val repo = mockk<ScheduledJobRepository> { coEvery { getOwned("job", owner.toString()) } returns job }
        val rows = (1..3).map { ScheduledJobRunEntity("run$it", "job", 1, "scheduled", it.toLong(), status = "succeeded") }
        val runs = mockk<ScheduledJobRunRepository> { coEvery { recentWithLookahead("job", any()) } coAnswers { rows.take(secondArg<Int>() + 1) } }
        val tool = createCronJobTools(repo, runs, mockk(), Conversation(assistantId = owner, messageNodes = emptyList()), { emptySet() })
            .single { it.name == "get_job_history" }
        val result = invoke(tool, """{"id":"job","limit":2}""")
        assertEquals(2, result.getValue("runs").jsonArray.size)
        assertEquals(JsonPrimitive(true), result["truncated"])
        assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content?.contains("limit") == true)
    }

    @Test fun cronHistoryMaximumAndDefaultUseRealRepositoryLookaheadWithoutExpandingResponse() = runBlocking {
        val job = ScheduledJobEntity("job", owner.toString(), "chat", "", mode = "llm", scheduleType = "once", timezone = "UTC", createdAtMs = 1)
        val jobs = mockk<ScheduledJobRepository> { coEvery { getOwned("job", owner.toString()) } returns job }
        val rows = (1..101).map { ScheduledJobRunEntity("run$it", "job", 1, "scheduled", it.toLong(), status = "succeeded") }
        val queriedLimits = mutableListOf<Int>()
        val dao = mockk<ScheduledJobRunDao> {
            coEvery { recent("job", any()) } coAnswers { val limit = secondArg<Int>(); queriedLimits += limit; rows.take(limit) }
        }
        val db = mockk<AppDatabase> { every { scheduledJobRunDao() } returns dao }
        val runs = ScheduledJobRunRepository(db)
        val tool = createCronJobTools(jobs, runs, mockk(), Conversation(assistantId = owner, messageNodes = emptyList()), { emptySet() })
            .single { it.name == "get_job_history" }
        val maximum = invoke(tool, """{"id":"job","limit":100}""")
        assertEquals(100, maximum.getValue("runs").jsonArray.size)
        assertEquals(listOf(101), queriedLimits)
        assertEquals(JsonPrimitive(true), maximum["truncated"])
        assertEquals(JsonNull, maximum["total"])
        val defaultPage = invoke(tool, """{"id":"job"}""")
        assertEquals(20, defaultPage.getValue("runs").jsonArray.size)
        assertEquals(listOf(101, 21), queriedLimits)
        assertEquals(JsonPrimitive(true), defaultPage["truncated"])
        assertEquals(100, runs.recent("job", 1000).size)
        assertEquals(100, queriedLimits.last())
    }

}
