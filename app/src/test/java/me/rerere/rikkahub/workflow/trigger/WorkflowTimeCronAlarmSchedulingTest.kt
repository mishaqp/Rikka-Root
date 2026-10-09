package me.rerere.rikkahub.workflow.trigger

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import me.rerere.rikkahub.service.scheduling.WorkManagerTestFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.scheduling.ExactAlarmScheduler
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WorkflowTimeCronAlarmSchedulingTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManagerImpl
    private var now = System.currentTimeMillis()
    private val workflow = WorkflowDefinition("wf-alarm", "Alarm", actions = emptyList(),
        trigger = TriggerSpec.TimeCron(cron = "@every 1h", timezone = "UTC"), updatedAtMs = 7L)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        assertTrue(context.getSharedPreferences("workflow_timecron_occurrences", Context.MODE_PRIVATE).edit().clear().commit())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        manager = WorkManagerTestFixture.create(context, Configuration.Builder().build())
        WorkManagerImpl.setDelegate(manager)
    }

    @After fun tearDown() {
        WorkManagerTestFixture.close(manager)
        WorkManagerImpl.setDelegate(null)
    }

    private fun family() = TimeCronTriggerFamily(context, CoroutineScope(Dispatchers.Default),
        nowMillis = { now }, lookupEnabled = { workflow })

    @Test fun `next workflow occurrence arms exact wakeup alarm and one shot fallback`() = runBlocking {
        family().sync(listOf(workflow), TriggerFireCallback { _, _ -> })
        val occurrence = WorkflowTimeCronOccurrenceStore(context).get(workflow.id)!!
        val alarm = workflowAlarms().single()
        assertEquals(now + 3_600_000L, occurrence.atMillis)
        assertEquals(occurrence.atMillis, alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.allowWhileIdle)
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals(workflow.id, intent.getStringExtra(ExactAlarmScheduler.KEY))
        assertEquals(occurrence.atMillis, intent.getLongExtra(ExactAlarmScheduler.AT_MILLIS, -1L))
        assertEquals(7L, intent.getLongExtra(ExactAlarmScheduler.REVISION, -1L))
        // Await the real enqueue transaction before querying the WorkManager database.
        manager.getWorkInfosForUniqueWork(AndroidWorkflowTimeCronScheduling.fallbackName(workflow.id, occurrence.atMillis, occurrence.revision)).get()
        val fallback = withContext(Dispatchers.IO) {
            val specs = manager.workDatabase.workSpecDao().getWorkSpecIdAndStatesForName(
                AndroidWorkflowTimeCronScheduling.fallbackName(workflow.id, occurrence.atMillis, occurrence.revision),
            )
            manager.workDatabase.workSpecDao().getWorkSpec(specs.single().id)!!
        }
        assertEquals(WorkflowTimeCronFallbackWorker::class.java.name, fallback.workerClassName)
        assertEquals(0L, fallback.intervalDuration)
        assertTrue(fallback.initialDelay > 0L)
    }

    @Test fun `fired repeating workflow replaces only its timer with next exact occurrence`() = runBlocking {
        var calls = 0
        val family = family()
        family.sync(listOf(workflow), TriggerFireCallback { _, _ -> calls++ })
        val first = WorkflowTimeCronOccurrenceStore(context).get(workflow.id)!!
        now = first.atMillis
        assertTrue(family.onWorkerFired(workflow.id, first.atMillis, first.revision))
        val next = WorkflowTimeCronOccurrenceStore(context).get(workflow.id)!!
        assertEquals(first.atMillis + 3_600_000L, next.atMillis)
        assertEquals(1, calls)
        val alarms = workflowAlarms()
        assertEquals(1, alarms.size)
        assertEquals(next.atMillis, alarms.single().triggerAtTime)
        assertTrue(alarms.single().allowWhileIdle)
    }

    private fun workflowAlarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.component?.className == WorkflowTimeCronAlarmReceiver::class.java.name
    }
}
