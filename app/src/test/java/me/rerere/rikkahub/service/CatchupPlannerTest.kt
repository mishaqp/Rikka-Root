package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class CatchupPlannerTest {
    @Test fun skipAdvancesWithoutExecution() {
        val result = CatchupPlanner.plan(0L, 100L, "skip") { it + 10L }
        assertTrue(result.missed.isEmpty())
        assertEquals(110L, result.next)
    }
    @Test fun fireOnceChoosesMostRecentMissedOccurrence() {
        assertEquals(listOf(100L), CatchupPlanner.plan(0L, 100L, "fire_once") { it + 10L }.missed)
    }
    @Test fun fireAllIsBoundedAndRearmsFuture() {
        val result = CatchupPlanner.plan(0L, 100000L, "fire_all") { it + 10L }
        assertEquals(20, result.missed.size)
        assertEquals(100010L, result.next)
        assertEquals(100000L, result.missed.last())
    }
    @Test fun expiredOneShotHasNoNextAndExactlyOneCatchup() {
        assertEquals(CatchupPlanner.Plan(listOf(1L), null), CatchupPlanner.plan(1L, 5L, "fire_once") { null })
    }
}
