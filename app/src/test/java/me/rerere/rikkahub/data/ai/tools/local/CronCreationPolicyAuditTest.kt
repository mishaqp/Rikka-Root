package me.rerere.rikkahub.data.ai.tools.local

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.service.CronJobScheduler
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CronCreationPolicyAuditTest {
    private fun input(name: String, args: JsonObject) = buildJsonObject {
        put("name", "Задание"); put("mode", "direct"); put("schedule_type", "once")
        put("at_unix_ms", System.currentTimeMillis() + 60_000)
        put("actions", buildJsonArray { add(buildJsonObject { put("tool", name); put("args", args) }) })
    }

    @Test fun `direct schedules reject headless blocked actions before storing or enqueueing`() = runBlocking {
        val cases = listOf(
            "send_sms" to buildJsonObject { put("phone_number", "+70000000000"); put("message", "test") },
            "ask_user" to buildJsonObject { put("question", "Вопрос") },
            "mcp_update" to buildJsonObject { put("id", Uuid.random().toString()); put("url", "https://example.test/mcp") },
            "root_exec" to buildJsonObject { put("command", "setenforce 0") },
        )
        for ((name, args) in cases) {
            val expected = HeadlessToolPolicy.blockReason(name, args)
            assertNotNull("Fixture must represent a headless blocked action: $name", expected)
            val repository = mockk<ScheduledJobRepository>()
            val runs = mockk<ScheduledJobRunRepository>(relaxed = true)
            val scheduler = mockk<CronJobScheduler>()
            coEvery { repository.create(any(), any()) } answers { firstArg<ScheduledJobEntity>() }
            coEvery { repository.getById(any()) } returns null
            every { repository.payload(any()) } throws IllegalStateException("No stored payload needed")
            coEvery { scheduler.schedule(any()) } returns true
            val caller = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), config = ConversationConfig())
            val tool = createCronJobTools(repository, runs, scheduler, caller) { setOf(name) }.first { it.name == "schedule_job" }
            val parts = tool.execute(input(name, args))
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(name, "error", result["status"]?.jsonPrimitive?.content)
            assertEquals(name, expected, result["code"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { repository.create(any(), any()) }
            coVerify(exactly = 0) { scheduler.schedule(any()) }
        }
    }

    @Test fun `direct read action remains schedulable`() {
        val args = buildJsonObject { put("path", "/scratch/input.txt") }
        assertNull(HeadlessToolPolicy.blockReason("read_file", args))
        assertNull(ScheduleJobValidator.validate(input("read_file", args), setOf("read_file"), System.currentTimeMillis()))
    }
}
