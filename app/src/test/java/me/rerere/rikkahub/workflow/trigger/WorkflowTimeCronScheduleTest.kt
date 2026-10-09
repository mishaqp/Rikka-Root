package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkflowTimeCronScheduleTest {
    @Test fun `weekday after DST gap keeps configured wall clock time`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.parse("2026-03-29T00:00:00+01:00[Europe/Berlin]").toInstant().toEpochMilli()
        val next = TimeCronTriggerFamily.computeNextFireMs(
            TriggerSpec.TimeCron(timeOfDay = "02:30", daysOfWeek = listOf(1)), zone, now,
        )
        assertEquals(ZonedDateTime.parse("2026-03-30T02:30:00+02:00[Europe/Berlin]").toInstant().toEpochMilli(), next)
    }

    @Test fun `daily next occurrence follows DST instead of fixed twenty four hours`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.parse("2026-03-28T09:00:00+01:00[Europe/Berlin]").toInstant().toEpochMilli()
        val next = TimeCronTriggerFamily.computeNextFireMs(TriggerSpec.TimeCron(timeOfDay = "09:00"), zone, now)
        assertEquals(ZonedDateTime.parse("2026-03-29T09:00:00+02:00[Europe/Berlin]").toInstant().toEpochMilli(), next)
    }

    @Test fun `restricted weekday selects next allowed day`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.parse("2026-10-09T12:00:00Z").toInstant().toEpochMilli()
        val next = TimeCronTriggerFamily.computeNextFireMs(
            TriggerSpec.TimeCron(timeOfDay = "04:00", daysOfWeek = listOf(1)), zone, now,
        )
        assertEquals(ZonedDateTime.parse("2026-10-12T04:00:00Z").toInstant().toEpochMilli(), next)
    }
}
