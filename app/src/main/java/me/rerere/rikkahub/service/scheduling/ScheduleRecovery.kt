package me.rerere.rikkahub.service.scheduling

import android.content.Intent
import kotlinx.coroutines.CancellationException

internal enum class ScheduleRecoveryReason(val clockChanged: Boolean = false) {
    BOOT, APP_REPLACED, TIME_CHANGED(true), TIMEZONE_CHANGED(true), EXACT_ACCESS_GRANTED;

    companion object {
        const val EXACT_ACCESS_GRANTED_ACTION = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        fun fromAction(action: String?): ScheduleRecoveryReason? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> APP_REPLACED
            Intent.ACTION_TIME_CHANGED -> TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> TIMEZONE_CHANGED
            EXACT_ACCESS_GRANTED_ACTION -> EXACT_ACCESS_GRANTED
            else -> null
        }
    }
}

/** Recover both domains independently; recovery only installs schedules and never claims work. */
internal suspend fun recoverSchedules(
    reason: ScheduleRecoveryReason,
    restoreCron: suspend (Boolean) -> Unit,
    restoreWorkflows: suspend (Boolean) -> Unit,
    dispatchBoot: () -> Unit,
) {
    try {
        restoreCron(reason.clockChanged)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // A damaged/unavailable cron repository must not block workflow recovery.
    }
    try {
        restoreWorkflows(reason.clockChanged)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // A future process start independently retries both durable schedules.
    }
    if (reason == ScheduleRecoveryReason.BOOT || reason == ScheduleRecoveryReason.APP_REPLACED) {
        dispatchBoot()
    }
}
