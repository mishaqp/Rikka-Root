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

    @Test fun dateBucketsKeepYesterdayEvenWhenItWasInThePreviousWeek() {
        assertEquals(DrawerConversationDateGroup.TODAY, drawerDateGroup(monday, monday))
        assertEquals(DrawerConversationDateGroup.YESTERDAY, drawerDateGroup(monday.minusDays(1), monday))
        assertEquals(DrawerConversationDateGroup.EARLIER, drawerDateGroup(monday.minusDays(2), monday))
    }

    @Test fun weekStartsOnMondayAndOlderDatesShareOneBucket() {
        assertEquals(DrawerConversationDateGroup.THIS_WEEK, drawerDateGroup(monday, friday))
        assertEquals(DrawerConversationDateGroup.YESTERDAY, drawerDateGroup(friday.minusDays(1), friday))
        assertEquals(DrawerConversationDateGroup.EARLIER, drawerDateGroup(monday.minusDays(1), friday))
        assertEquals(DrawerConversationDateGroup.EARLIER, drawerDateGroup(monday.minusYears(1), friday))
    }

    @Test fun datesUseLocalTimeRatherThanUtc() {
        val conversation = conversation(Instant.parse("2026-10-15T21:30:00Z"))
        val header = drawerConversationHeader(null, conversation, ConversationSortOrder.UPDATE_TIME, friday, zone)
        assertEquals("Сегодня", (header as ConversationListItem.DateHeader).label)
    }

    @Test fun consecutiveDatesInTheSameBucketDoNotCreateDuplicateHeaders() {
        val before = conversation(monday.atStartOfDay(zone).toInstant())
        val after = conversation(monday.plusDays(1).atStartOfDay(zone).toInstant())
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

    @Test fun allDateGroupHeaderKeysAreDifferentAcrossWeekBoundaries() {
        for (today in listOf(monday, monday.plusDays(1), friday, monday.plusDays(6))) {
            val keys = DrawerConversationDateGroup.entries.map { it.headerDate(today) }
            assertEquals(keys.size, keys.toSet().size)
        }
    }

    private fun conversation(updateAt: Instant) = Conversation(
        assistantId = Uuid.random(), messageNodes = emptyList(), updateAt = updateAt,
    )
}
