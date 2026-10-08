package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CronJobToolTest {
    private fun check(text: String) = ScheduleJobValidator.validate(Json.parseToJsonElement(text).jsonObject, setOf("send_sms"), 1000)
    @Test fun validatesOneShotFutureAndMutualExclusion() {
        assertNull(check("""{"name":"task","mode":"llm","prompt":"xx","schedule_type":"once","at_unix_ms":2000}"""))
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"xx","schedule_type":"once","at_unix_ms":500}"""))
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"xx","actions":[],"schedule_type":"once","at_unix_ms":2000}"""))
    }
    @Test fun rejectsUnknownZoneAndInvertedBounds() {
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"x","schedule_type":"cron","cron_expression":"@daily","timezone":"bad"}"""))
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"x","schedule_type":"cron","cron_expression":"@daily","start_at_unix_ms":4000,"end_at_unix_ms":3000}"""))
    }
    @Test fun creatorCannotSelectAnotherAssistantOrStoreCredentialFields() {
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"x","schedule_type":"once","at_unix_ms":2000,"assistant_id":"foreign"}"""))
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"x","schedule_type":"once","at_unix_ms":2000,"api_key":"ab"}"""))
    }
    @Test fun directActionsRequireAvailableToolsAndPositiveBoundedLimits() {
        assertNotNull(check("""{"name":"task","mode":"direct","actions":[{"tool":"root","args":{}}],"schedule_type":"once","at_unix_ms":2000}"""))
        assertNotNull(check("""{"name":"task","mode":"llm","prompt":"x","schedule_type":"once","at_unix_ms":2000,"max_runs":0}"""))
        assertNull(check("""{"name":"task","mode":"direct","actions":[{"tool":"send_sms","args":{}}],"schedule_type":"once","at_unix_ms":2000,"max_runs":1000}"""))
    }
    @Test fun directScheduleCannotDispatchAnotherAutomationRun() {
        val input = Json.parseToJsonElement("""{"name":"task","mode":"direct","actions":[{"tool":"subagent_dispatch","args":{}}],"schedule_type":"once","at_unix_ms":2000}""").jsonObject
        assertNotNull(ScheduleJobValidator.validate(input, setOf("subagent_dispatch"), 1000))
    }
}
