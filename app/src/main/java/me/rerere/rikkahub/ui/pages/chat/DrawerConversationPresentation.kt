package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Conversation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Presentation state only: none of these values enter Settings or backups. */
enum class DrawerConversationSort(val label: String) {
    NEWEST("Сначала новые"),
    OLDEST("Сначала старые"),
    TITLE("По названию"),
    PINNED_FIRST("Закреплённые сверху"),
}

enum class DrawerConversationDateGroup(val label: String) {
    TODAY("Сегодня"),
    YESTERDAY("Вчера"),
    THIS_WEEK("На этой неделе"),
    EARLIER("Раньше");

    fun headerDate(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        YESTERDAY -> today.minusDays(1)
        // A key distinct from Today/Yesterday even on Monday and Tuesday.
        THIS_WEEK -> today.minusDays(2)
        EARLIER -> today.minusDays(7)
    }
}

internal fun drawerWeekStart(today: LocalDate): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun drawerDateGroup(date: LocalDate, today: LocalDate): DrawerConversationDateGroup = when {
    date >= today -> DrawerConversationDateGroup.TODAY
    date == today.minusDays(1) -> DrawerConversationDateGroup.YESTERDAY
    date >= drawerWeekStart(today) -> DrawerConversationDateGroup.THIS_WEEK
    else -> DrawerConversationDateGroup.EARLIER
}

internal fun Conversation.drawerSortDate(sortOrder: ConversationSortOrder, zone: ZoneId): LocalDate =
    (if (sortOrder == ConversationSortOrder.CREATE_TIME) createAt else updateAt).atZone(zone).toLocalDate()

fun drawerConversationHeader(
    before: Conversation?,
    after: Conversation?,
    sortOrder: ConversationSortOrder,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): ConversationListItem? {
    if (after == null) return null
    if (after.isPinned) return if (before?.isPinned != true) ConversationListItem.PinnedHeader else null
    val group = drawerDateGroup(after.drawerSortDate(sortOrder, zone), today)
    if (before != null && !before.isPinned &&
        drawerDateGroup(before.drawerSortDate(sortOrder, zone), today) == group
    ) return null
    return ConversationListItem.DateHeader(group.headerDate(today), group.label, sortOrder)
}
