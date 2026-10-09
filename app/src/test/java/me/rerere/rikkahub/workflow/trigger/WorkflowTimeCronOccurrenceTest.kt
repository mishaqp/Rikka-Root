package me.rerere.rikkahub.workflow.trigger

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WorkflowTimeCronOccurrenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var now = ZonedDateTime.parse("2026-10-09T03:00:00Z").toInstant().toEpochMilli()
    private val scheduling = RecordingScheduling()
    private var live: WorkflowDefinition? = workflow()
    private var fires = 0

    @Before fun reset() {
        assertTrue(context.getSharedPreferences("workflow_timecron_occurrences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun family() = TimeCronTriggerFamily(context, CoroutineScope(Dispatchers.Default), scheduling,
        WorkflowTimeCronOccurrenceStore(context), { now }, { live })

    private fun workflow(spec: TriggerSpec.TimeCron = TriggerSpec.TimeCron(timeOfDay = "04:00", timezone = "UTC"), revision: Long = 1L) =
        WorkflowDefinition("wf", "Scheduled", trigger = spec, actions = emptyList(), createdAtMs = 0L, updatedAtMs = revision)

    private fun callback() = TriggerFireCallback { _, _ -> fires++ }

    @Test fun `enabled schedule records exact next occurrence and persists its identity`() = runBlocking {
        family().sync(listOf(live!!), callback())
        val occurrence = scheduling.scheduled.single().second
        assertEquals(ZonedDateTime.parse("2026-10-09T04:00:00Z").toInstant().toEpochMilli(), occurrence.atMillis)
        assertEquals(1L, occurrence.revision)
        assertEquals(occurrence, WorkflowTimeCronOccurrenceStore(context).get("wf"))
    }

    @Test fun `repeat is durably advanced and scheduled before awaited engine execution`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), TriggerFireCallback { _, _ ->
            fires++
            val pending = WorkflowTimeCronOccurrenceStore(context).get("wf")!!
            assertTrue(pending.atMillis > now)
            assertEquals(pending, scheduling.scheduled.last().second)
        })
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        assertTrue(family.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(1, fires)
        assertEquals(ZonedDateTime.parse("2026-10-10T04:00:00Z").toInstant().toEpochMilli(), scheduling.scheduled.last().second.atMillis)
    }

    @Test fun `alarm and fallback duplicates cannot fire again after process recreation`() = runBlocking {
        val firstFamily = family()
        firstFamily.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        assertTrue(firstFamily.onWorkerFired("wf", first.atMillis, first.revision))
        assertFalse(firstFamily.onWorkerFired("wf", first.atMillis, first.revision))
        val restored = family()
        restored.restore(listOf(live!!), callback(), resetForClockChange = false)
        assertFalse(restored.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(1, fires)
    }

    @Test fun `boot recovery retains a due occurrence rather than silently skipping it`() = runBlocking {
        family().sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis + 120_000L
        val restored = family()
        restored.restore(listOf(live!!), callback(), resetForClockChange = false)
        assertEquals(first, scheduling.scheduled.last().second)
        assertTrue(restored.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(1, fires)
    }

    @Test fun `callback failure keeps the already persisted next alarm and never replays actions`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), TriggerFireCallback { _, _ -> fires++; throw IllegalStateException("engine failed") })
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        val error = runCatching { family.onWorkerFired("wf", first.atMillis, first.revision) }.exceptionOrNull()
        assertEquals("engine failed", error?.message)
        assertEquals(1, fires)
        assertTrue(WorkflowTimeCronOccurrenceStore(context).get("wf")!!.atMillis > now)
        assertFalse(family.onWorkerFired("wf", first.atMillis, first.revision))
    }

    @Test fun `disabled live workflow cancels both scheduling paths without firing`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        live = null
        assertFalse(family.onWorkerFired("wf", first.atMillis, first.revision))
        assertNull(WorkflowTimeCronOccurrenceStore(context).get("wf"))
        assertTrue(scheduling.cancelledTimers.contains("wf" to first))
        assertTrue("wf" in scheduling.cancelledAll)
        assertEquals(0, fires)
    }

    @Test fun `edited workflow rejects old occurrence even before repository sync`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        live = workflow(TriggerSpec.TimeCron(timeOfDay = "06:00", timezone = "UTC"), revision = 2L)
        assertFalse(family.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(0, fires)
        val new = WorkflowTimeCronOccurrenceStore(context).get("wf")!!
        assertEquals(2L, new.revision)
        assertEquals(ZonedDateTime.parse("2026-10-09T06:00:00Z").toInstant().toEpochMilli(), new.atMillis)
    }

    @Test fun `delayed interval worker retains cadence and skips missed periods`() = runBlocking {
        live = workflow(TriggerSpec.TimeCron(cron = "@every 1m", timezone = "UTC"))
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis + 135_000L
        assertTrue(family.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(first.atMillis + 180_000L, scheduling.scheduled.last().second.atMillis)
    }

    @Test fun `clock timezone change cancels old alarm and computes new local wall clock`() = runBlocking {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            now = ZonedDateTime.parse("2026-10-09T00:00:00Z").toInstant().toEpochMilli()
            live = workflow(TriggerSpec.TimeCron(timeOfDay = "04:00"))
            val family = family()
            family.sync(listOf(live!!), callback())
            val old = scheduling.scheduled.single().second
            assertEquals(ZonedDateTime.parse("2026-10-09T04:00:00+02:00[Europe/Berlin]").toInstant().toEpochMilli(), old.atMillis)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            family.restore(listOf(live!!), callback(), resetForClockChange = true)
            assertTrue(scheduling.cancelledTimers.contains("wf" to old))
            assertEquals(ZonedDateTime.parse("2026-10-09T04:00:00-10:00[Pacific/Honolulu]").toInstant().toEpochMilli(), scheduling.scheduled.last().second.atMillis)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun `atomic claim across family instances uses persisted current occurrence`() = runBlocking {
        val firstFamily = family()
        firstFamily.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        val secondFamily = family()
        secondFamily.restore(listOf(live!!), callback(), resetForClockChange = false)
        now = first.atMillis
        assertTrue(firstFamily.onWorkerFired("wf", first.atMillis, first.revision))
        assertFalse(secondFamily.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(1, fires)
    }

    @Test fun `future occurrence cannot execute early`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        assertFalse(family.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(first, WorkflowTimeCronOccurrenceStore(context).get("wf"))
        assertEquals(0, fires)
    }

    @Test fun `clock only recovery never cancels a running canonical executor`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        assertTrue(family.onWorkerFired("wf", first.atMillis, first.revision))
        scheduling.cancelledAll.clear()
        family.restore(listOf(live!!), callback(), resetForClockChange = true)
        assertTrue("Changing the clock must replace timers without cancelling actions already running", scheduling.cancelledAll.isEmpty())
    }

    @Test fun `clock rollback never recreates an already consumed occurrence`() = runBlocking {
        val family = family()
        family.sync(listOf(live!!), callback())
        val first = scheduling.scheduled.single().second
        now = first.atMillis
        assertTrue(family.onWorkerFired("wf", first.atMillis, first.revision))
        now = first.atMillis - 3_600_000L
        val restored = family()
        restored.restore(listOf(live!!), callback(), resetForClockChange = true)
        now = first.atMillis
        assertFalse("An alarm/fallback for a consumed token cannot become executable after clock rollback",
            restored.onWorkerFired("wf", first.atMillis, first.revision))
        assertEquals(1, fires)
        assertTrue(WorkflowTimeCronOccurrenceStore(context).get("wf")!!.atMillis > first.atMillis)
    }

    @Test fun `non advancing interval never creates an exact alarm loop`() = runBlocking {
        live = workflow(TriggerSpec.TimeCron(cron = "@every 0m", timezone = "UTC"))
        val error = runCatching { family().sync(listOf(live!!), callback()) }.exceptionOrNull()
        assertNotNull("An exact schedule must reject a zero interval before registering a timer", error)
        assertTrue(error!!.message.orEmpty().contains("позже"))
        assertTrue(scheduling.scheduled.isEmpty())
        assertNull(WorkflowTimeCronOccurrenceStore(context).get("wf"))
        assertEquals(0, fires)
    }

    private class RecordingScheduling : WorkflowTimeCronScheduling {
        val scheduled = mutableListOf<Pair<String, WorkflowTimeCronOccurrence>>()
        val cancelledTimers = mutableListOf<Pair<String, WorkflowTimeCronOccurrence>>()
        val cancelledAll = mutableListOf<String>()
        override fun schedule(workflowId: String, occurrence: WorkflowTimeCronOccurrence) { scheduled += workflowId to occurrence }
        override fun cancelTimer(workflowId: String, occurrence: WorkflowTimeCronOccurrence) { cancelledTimers += workflowId to occurrence }
        override fun cancelAll(workflowId: String) { cancelledAll += workflowId }
    }
}
