package me.rerere.rikkahub.workflow.trigger

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WorkflowTimeCronRearmAuditTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("workflow_timecron_occurrences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `failure arming the following occurrence must not discard the already claimed due execution`() = runBlocking {
        var now = 1_800_000_000_000L
        val definition = WorkflowDefinition("rearm-fixture", "Rearm fixture",
            trigger = TriggerSpec.TimeCron(cron = "@every 1m", timezone = "UTC"), actions = emptyList(),
            createdAtMs = 0, updatedAtMs = 1)
        var failRearm = false
        val scheduling = object : WorkflowTimeCronScheduling {
            override fun schedule(workflowId: String, occurrence: WorkflowTimeCronOccurrence) {
                if (failRearm) throw IOException("simulated scheduling backend failure")
            }
            override fun cancelTimer(workflowId: String, occurrence: WorkflowTimeCronOccurrence) = Unit
            override fun cancelAll(workflowId: String) = Unit
        }
        val store = WorkflowTimeCronOccurrenceStore(context)
        var executions = 0
        val callback = TriggerFireCallback { _, _ -> executions++ }
        val family = TimeCronTriggerFamily(context, CoroutineScope(Dispatchers.Default), scheduling, store,
            { now }, { definition })
        family.sync(listOf(definition), callback)
        val due = requireNotNull(store.get(definition.id))
        now = due.atMillis
        failRearm = true

        runCatching { family.onWorkerFired(definition.id, due.atMillis, due.revision) }

        assertEquals("A backend failure for the next alarm must not lose today's consumed execution", 1, executions)
        val next = store.get(definition.id)
        assertNotNull("The next identity must remain durable for startup recovery", next)
        assertEquals(due.atMillis, next!!.lastConsumedAtMillis)
        assertFalse("The consumed execution must never replay", family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertEquals(1, executions)
    }
}
