package me.rerere.rikkahub.service

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Minute-resolution UNIX cron. Calendar occurrences use actual offsets, including DST overlaps. */
object CronExpressionParser {
    data class Schedule internal constructor(
        val expression: String,
        val intervalMillis: Long? = null,
        internal val minutes: Set<Int> = emptySet(),
        internal val hours: Set<Int> = emptySet(),
        internal val days: Set<Int> = emptySet(),
        internal val months: Set<Int> = emptySet(),
        internal val weekdays: Set<Int> = emptySet(),
        internal val anyDay: Boolean = true,
        internal val anyWeekday: Boolean = true,
    )
    private val aliases = mapOf("@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@weekly" to "0 0 * * 0", "@monthly" to "0 0 1 * *", "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *")
    private val monthNames = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").mapIndexed { i, name -> name to i + 1 }.toMap()
    private val weekdayNames = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").mapIndexed { i, name -> name to i }.toMap()

    fun parse(expression: String): Result<Schedule> = runCatching {
        val trimmed = expression.trim()
        require(trimmed.length in 1..256) { "Недопустимое расписание." }
        if (trimmed.startsWith("@every")) {
            val match = Regex("@every ([1-9][0-9]*)([smhd])").matchEntire(trimmed) ?: error("Недопустимый интервал.")
            val amount = match.groupValues[1].toLong()
            val unit = when (match.groupValues[2]) { "s" -> 1000L; "m" -> 60000L; "h" -> 3600000L; else -> 86400000L }
            val millis = Math.multiplyExact(amount, unit)
            require(millis in 60000L..2678400000L && millis % 60000L == 0L) { "Интервал: целые минуты, от минуты до 31 дня." }
            return@runCatching Schedule(trimmed, millis)
        }
        val fields = (aliases[trimmed.lowercase()] ?: trimmed).uppercase().split(Regex("\\s+"))
        require(fields.size == 5) { "Нужны пять полей cron." }
        val schedule = Schedule(trimmed,
            minutes = field(fields[0], 0, 59), hours = field(fields[1], 0, 23), days = field(fields[2], 1, 31),
            months = field(fields[3], 1, 12, monthNames), weekdays = field(fields[4], 0, 7, weekdayNames).map { it % 7 }.toSet(),
            anyDay = fields[2] == "*", anyWeekday = fields[4] == "*",
        )
        // Eight calendar years include a complete leap-year cycle and every weekday/date combination.
        require((0 until 366 * 8).any { matchesDate(schedule, LocalDate.of(2000, 1, 1).plusDays(it.toLong())) }) { "Расписание не имеет допустимых дат." }
        schedule
    }

    private fun field(raw: String, min: Int, max: Int, names: Map<String, Int> = emptyMap()): Set<Int> {
        fun value(text: String) = names[text] ?: text.toIntOrNull() ?: error("Недопустимое поле cron.")
        val result = sortedSetOf<Int>()
        val parts = raw.split(',')
        require(parts.size <= 64)
        for (part in parts) {
            val split = part.split('/')
            require(split.size in 1..2)
            val step = if (split.size == 2) split[1].toIntOrNull() ?: error("Недопустимый шаг.") else 1
            require(step in 1..(max - min + 1))
            val range = split[0].split('-')
            require(range.size in 1..2)
            val first = if (split[0] == "*") min else value(range[0])
            val last = when { split[0] == "*" -> max; range.size == 2 -> value(range[1]); split.size == 2 -> max; else -> first }
            require(first in min..max && last in first..max)
            for (number in first..last step step) result.add(number)
        }
        require(result.isNotEmpty())
        return result
    }

    private fun matchesDate(schedule: Schedule, date: LocalDate): Boolean {
        if (date.monthValue !in schedule.months) return false
        val day = date.dayOfMonth in schedule.days
        val weekday = date.dayOfWeek.value % 7 in schedule.weekdays
        return when { schedule.anyDay -> weekday; schedule.anyWeekday -> day; else -> day || weekday }
    }

    fun nextExecution(schedule: Schedule, afterMillis: Long, zone: ZoneId, anchorMillis: Long = 0): Long? = runCatching {
        schedule.intervalMillis?.let { interval ->
            if (afterMillis < anchorMillis) return@runCatching anchorMillis
            val elapsed = Math.subtractExact(afterMillis, anchorMillis)
            return@runCatching Math.addExact(anchorMillis, Math.multiplyExact(Math.addExact(elapsed / interval, 1L), interval))
        }
        val start = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        for (offset in 0 until 366 * 8) {
            val date = start.plusDays(offset.toLong())
            if (!matchesDate(schedule, date)) continue
            var earliest: Long? = null
            for (hour in schedule.hours) for (minute in schedule.minutes) {
                val local = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute)
                for (zoneOffset in zone.rules.getValidOffsets(local)) {
                    val millis = local.toInstant(zoneOffset).toEpochMilli()
                    if (millis > afterMillis && (earliest == null || millis < earliest)) earliest = millis
                }
            }
            if (earliest != null) return@runCatching earliest
        }
        null
    }.getOrNull()
}
