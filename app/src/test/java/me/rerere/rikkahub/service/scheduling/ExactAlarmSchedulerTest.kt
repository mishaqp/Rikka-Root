package me.rerere.rikkahub.service.scheduling

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ExactAlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    private lateinit var scheduler: AndroidExactAlarmScheduler

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        alarms = context.getSystemService(AlarmManager::class.java)
        scheduler = AndroidExactAlarmScheduler(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
    }

    @Test fun nextOccurrenceUsesExactWakeupAlarmAllowedInDozeAtTheRequestedTime() {
        val at = 1_800_001_440_000L
        assertTrue(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "occurrence/123", at))
        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(at, alarm.triggerAtMs)
        assertEquals(0L, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals("me.rerere.rikkahub.service.CronJobAlarmReceiver", intent.component!!.className)
        assertEquals("occurrence/123", intent.getStringExtra(ExactAlarmScheduler.KEY))
        assertEquals(at, intent.getLongExtra(ExactAlarmScheduler.AT_MILLIS, -1))
        assertEquals(setOf(ExactAlarmScheduler.KEY, ExactAlarmScheduler.AT_MILLIS, ExactAlarmScheduler.REVISION), intent.extras!!.keySet())
        assertTrue(requireNotNull(alarm.operation).isImmutable)
    }

    @Test fun domainTimeAndRevisionGiveDistinctAlarmIdentitiesAndCancelOnlyOneOccurrence() {
        val at = 1_800_001_440_000L
        scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "shared", at)
        scheduler.schedule(ExactAlarmDomain.WORKFLOW_TIME_CRON, "shared", at, 9)
        scheduler.schedule(ExactAlarmDomain.WORKFLOW_TIME_CRON, "shared", at + 60_000, 9)
        scheduler.schedule(ExactAlarmDomain.WORKFLOW_TIME_CRON, "shared", at, 10)
        assertEquals(4, shadowOf(alarms).scheduledAlarms.size)
        scheduler.cancel(ExactAlarmDomain.WORKFLOW_TIME_CRON, "shared", at, 9)
        assertEquals(3, shadowOf(alarms).scheduledAlarms.size)
        assertTrue(shadowOf(alarms).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.component!!.className == "me.rerere.rikkahub.service.CronJobAlarmReceiver"
        })
    }

    @Test fun rearmingSameOccurrenceReplacesItsAlarmInsteadOfDuplicatingIt() {
        val at = 1_800_001_440_000L
        assertTrue(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "same", at))
        assertTrue(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "same", at))
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun missingExactAlarmGrantReturnsFallbackWithoutSchedulingAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(ExactAlarmAccess.canSchedule(context))
        assertFalse(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "fallback", 1_800_001_440_000L))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun androidBefore12DoesNotNeedExactAlarmSpecialAccess() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertTrue(ExactAlarmAccess.canSchedule(context))
        assertTrue(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "android-11", 1_800_001_440_000L))
        assertTrue(shadowOf(alarms).scheduledAlarms.single().isAllowWhileIdle)
    }

    @Test
    @Config(shadows = [SecurityExceptionAlarmManager::class])
    fun revocationBetweenCapabilityCheckAndScheduleReturnsFallbackInsteadOfCrashing() {
        assertTrue(ExactAlarmAccess.canSchedule(context))
        assertFalse(scheduler.schedule(ExactAlarmDomain.CRON_JOBS, "revoked", 1_800_001_440_000L))
    }
}

@Implements(AlarmManager::class)
class SecurityExceptionAlarmManager : ShadowAlarmManager() {
    @Implementation
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        throw SecurityException("exact alarm grant revoked")
    }
}
