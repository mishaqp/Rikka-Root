package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class DrawerConversationPresentationTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private val friday = monday.plusDays(4)
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun labelsKeepYesterdayEvenWhenItWasInThePreviousWeek() {
        assertEquals("Сегодня", drawerDateLabel(monday, monday))
        assertEquals("Вчера", drawerDateLabel(monday.minusDays(1), monday))
        assertEquals("10 октября", drawerDateLabel(monday.minusDays(2), monday))
    }

    @Test fun olderDatesKeepTheirOwnCalendarDayAndYear() {
        assertEquals("12 октября", drawerDateLabel(monday, friday))
        assertEquals("Вчера", drawerDateLabel(friday.minusDays(1), friday))
        assertEquals("11 октября", drawerDateLabel(monday.minusDays(1), friday))
        assertEquals("12 октября 2025", drawerDateLabel(monday.minusYears(1), friday))
    }

    @Test fun datesUseLocalTimeRatherThanUtc() {
        val conversation = conversation(Instant.parse("2026-10-15T21:30:00Z"))
        val header = drawerConversationHeader(null, conversation, ConversationSortOrder.UPDATE_TIME, friday, zone)
        assertEquals("Сегодня", (header as ConversationListItem.DateHeader).label)
    }

    @Test fun consecutiveTimesInTheSameLocalDayDoNotCreateDuplicateHeaders() {
        val before = conversation(monday.atStartOfDay(zone).toInstant())
        val after = conversation(monday.atStartOfDay(zone).plusHours(23).toInstant())
        assertNull(drawerConversationHeader(before, after, ConversationSortOrder.UPDATE_TIME, friday, zone))
    }

    @Test fun pinnedSectionHasOneHeaderAndTheFollowingDateSectionGetsItsOwn() {
        val pinned = conversation(friday.atStartOfDay(zone).toInstant()).copy(isPinned = true)
        assertEquals(ConversationListItem.PinnedHeader,
            drawerConversationHeader(null, pinned, ConversationSortOrder.UPDATE_TIME, friday, zone))
        assertNull(drawerConversationHeader(pinned, pinned, ConversationSortOrder.UPDATE_TIME, friday, zone))
        assertTrue(drawerConversationHeader(pinned, pinned.copy(isPinned = false),
            ConversationSortOrder.UPDATE_TIME, friday, zone) is ConversationListItem.DateHeader)
    }

    @Test fun selectedLegacyDateBasisStillControlsGrouping() {
        val item = conversation(friday.atStartOfDay(zone).toInstant())
            .copy(createAt = friday.minusDays(1).atStartOfDay(zone).toInstant())
        val header = drawerConversationHeader(null, item, ConversationSortOrder.CREATE_TIME, friday, zone)
        assertEquals("Вчера", (header as ConversationListItem.DateHeader).label)
    }

    @Test fun dayHeadersKeepDifferentKeysAcrossWeekAndYearBoundaries() {
        for (today in listOf(monday, monday.plusDays(1), friday, monday.plusDays(6))) {
            val dates = (0..35).map { today.minusDays(it.toLong()) } + today.minusYears(1)
            val headers = dates.map { date ->
                drawerConversationHeader(null, conversation(date.atStartOfDay(zone).toInstant()),
                    ConversationSortOrder.UPDATE_TIME, today, zone) as ConversationListItem.DateHeader
            }
            assertEquals(dates, headers.map { it.date })
            assertEquals(headers.size, headers.map { it.date }.toSet().size)
        }
    }

    @Test fun consecutiveDaysInTheSameWeekHaveSeparateHeaders() {
        val before = conversation(monday.atStartOfDay(zone).toInstant())
        val after = conversation(monday.plusDays(1).atStartOfDay(zone).toInstant())
        val header = drawerConversationHeader(before, after, ConversationSortOrder.UPDATE_TIME, friday, zone)
            as ConversationListItem.DateHeader
        assertEquals(monday.plusDays(1), header.date)
        assertEquals("13 октября", header.label)
    }

    private fun conversation(updateAt: Instant) = Conversation(
        assistantId = Uuid.random(), messageNodes = emptyList(), updateAt = updateAt,
    )
}
