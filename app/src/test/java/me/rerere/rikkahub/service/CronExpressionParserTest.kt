package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CronExpressionParserTest {
    private fun next(expr: String, after: String, zone: String = "UTC", anchor: Long = 0) =
        CronExpressionParser.nextExecution(CronExpressionParser.parse(expr).getOrThrow(), Instant.parse(after).toEpochMilli(), ZoneId.of(zone), anchor)

    @Test fun acceptsNamesAndFindsNextWeekday() {
        assertEquals(Instant.parse("2026-10-12T09:00:00Z").toEpochMilli(), next("0 9 * * MON-FRI", "2026-10-09T09:00:00Z"))
    }
    @Test fun skipsNonexistentSpringTime() {
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), next("30 2 * * *", "2026-03-08T06:59:00Z", "America/New_York"))
    }
    @Test fun overlapHasTwoDistinctInstants() {
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), next("30 1 * * *", "2026-11-01T05:30:00Z", "America/New_York"))
    }
    @Test fun intervalRetainsExactDurationAcrossHourBoundary() {
        assertEquals(63 * 60000L, next("@every 7m", "1970-01-01T00:57:00Z"))
    }
    @Test fun rejectsMalformedUnboundedOrSubMinuteExpressions() {
        listOf("@every 10s", "@every 0m", "@every 32d", "60 * * * *", "*/0 * * * *", "* * * * * *", "0 0 30 2 *").forEach {
            assertTrue(it, CronExpressionParser.parse(it).isFailure)
        }
    }
    @Test fun unixRestrictedDayFieldsUseOr() {
        assertEquals(Instant.parse("2026-10-05T00:00:00Z").toEpochMilli(), next("0 0 1 * MON", "2026-10-02T00:00:00Z"))
    }
}
