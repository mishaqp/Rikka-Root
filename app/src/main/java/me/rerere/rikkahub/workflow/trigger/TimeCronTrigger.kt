package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exact one-shot alarms determine time; WorkManager owns execution and delayed fallback. */
internal class TimeCronTriggerFamily(
    context: Context,
    scope: CoroutineScope,
    private val scheduling: WorkflowTimeCronScheduling = AndroidWorkflowTimeCronScheduling(context),
    private val occurrences: WorkflowTimeCronOccurrenceStore = WorkflowTimeCronOccurrenceStore(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val lookupEnabled: suspend (String) -> WorkflowDefinition? = { id ->
        TimeCronWorkerHelper.repositoryLookup(id)?.takeIf { it.entity.enabled }?.let {
            it.definition.copy(enabled = true, updatedAtMs = it.entity.updatedAtMs)
        }
    },
) : WorkflowTriggerFamily {
    override val name = "time_cron"
    private val mutex = Mutex()
    private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) = mutex.withLock {
        reconcile(matching, callback, rearmAll = false, resetForClockChange = false)
    }

    suspend fun restore(matching: List<WorkflowDefinition>, callback: TriggerFireCallback, resetForClockChange: Boolean) = mutex.withLock {
        reconcile(matching, callback, rearmAll = true, resetForClockChange = resetForClockChange)
    }

    private fun reconcile(matching: List<WorkflowDefinition>, callback: TriggerFireCallback, rearmAll: Boolean, resetForClockChange: Boolean) {
        fireCallback = callback
        val previousIds = lastSnapshot.map { it.id }.toSet()
        val current = matching.filter { it.trigger is TriggerSpec.TimeCron }.associateBy { it.id }
        for (id in (previousIds + occurrences.ids()) - current.keys) cancelWork(id)
        for ((id, wf) in current) {
            val old = occurrences.get(id)
            val fingerprint = fingerprint(wf)
            val definitionChanged = old == null || old.revision != wf.updatedAtMs || old.triggerFingerprint != fingerprint
            val zoneChanged = old != null && old.zoneId != effectiveZone(wf.trigger as TriggerSpec.TimeCron).id
            if (definitionChanged || zoneChanged || resetForClockChange) {
                old?.let { scheduling.cancelTimer(id, it) }
                // A wall-clock adjustment replaces future timers; it never cancels
                // canonical executors whose actions have already started.
                if (definitionChanged) scheduling.cancelAll(id)
                val next = nextOccurrence(wf, nowMillis(),
                    lastConsumedAtMillis = if (definitionChanged) null else old?.lastConsumedAtMillis)
                occurrences.put(id, next)
                scheduling.schedule(id, next)
            } else if (rearmAll || id !in previousIds) {
                // Preserve a due occurrence on boot/process restore; the worker's durable
                // claim, not a fresh now-based timer, decides whether it may execute.
                scheduling.schedule(id, old!!)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() = mutex.withLock {
        for (id in lastSnapshot.map { it.id }.toSet() + occurrences.ids()) cancelWork(id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    private fun cancelWork(workflowId: String) {
        occurrences.get(workflowId)?.let { scheduling.cancelTimer(workflowId, it) }
        occurrences.remove(workflowId)
        scheduling.cancelAll(workflowId)
    }

    /** Claim + next occurrence commit before actions; an interrupted run is never replayed. */
    suspend fun onWorkerFired(
        workflowId: String,
        atMillis: Long,
        revision: Long,
        callback: TriggerFireCallback? = fireCallback,
    ): Boolean {
        val fire = callback ?: return false
        val definition = mutex.withLock {
            val pending = occurrences.get(workflowId) ?: return@withLock null
            if (pending.atMillis != atMillis || pending.revision != revision || nowMillis() < atMillis) return@withLock null
            val wf = lookupEnabled(workflowId)
            if (wf == null || wf.trigger !is TriggerSpec.TimeCron) {
                cancelWork(workflowId)
                return@withLock null
            }
            val definitionChanged = wf.updatedAtMs != revision || fingerprint(wf) != pending.triggerFingerprint
            val zoneChanged = effectiveZone(wf.trigger as TriggerSpec.TimeCron).id != pending.zoneId
            if (definitionChanged || zoneChanged) {
                scheduling.cancelTimer(workflowId, pending)
                if (definitionChanged) scheduling.cancelAll(workflowId)
                val next = nextOccurrence(wf, nowMillis(),
                    lastConsumedAtMillis = if (definitionChanged) null else pending.lastConsumedAtMillis)
                occurrences.put(workflowId, next)
                scheduling.schedule(workflowId, next)
                return@withLock null
            }
            val next = nextOccurrence(wf, nowMillis().coerceAtLeast(atMillis), intervalAnchor = atMillis, lastConsumedAtMillis = atMillis)
            // A synchronous checked commit is both the occurrence claim and durable
            // next-run recovery plan. Both alarm/fallback workers use this same CAS.
            if (!occurrences.advance(workflowId, pending, next)) return@withLock null
            scheduling.cancelTimer(workflowId, pending)
            scheduling.schedule(workflowId, next)
            wf
        } ?: return false
        // Await the engine in the WorkManager lifecycle. Its existing enabled/owner,
        // conditions, cooldown, tool permissions and HARDLINE checks remain in force.
        fire.onFire(definition.id, definition.trigger)
        return true
    }

    private fun nextOccurrence(
        wf: WorkflowDefinition,
        afterMillis: Long,
        intervalAnchor: Long? = null,
        lastConsumedAtMillis: Long? = null,
    ): WorkflowTimeCronOccurrence {
        val spec = wf.trigger as TriggerSpec.TimeCron
        val zone = effectiveZone(spec)
        // Never recreate a consumed logical occurrence when the user sets the clock back.
        val after = afterMillis.coerceAtLeast(lastConsumedAtMillis ?: afterMillis)
        val interval = if (spec.cron?.trim()?.startsWith("@every") == true) derivePeriodMs(spec) else null
        val atMillis = if (intervalAnchor != null && interval != null && interval > 0L) {
            val periods = Math.addExact(Math.subtractExact(after, intervalAnchor) / interval, 1L)
            Math.addExact(intervalAnchor, Math.multiplyExact(periods, interval))
        } else computeNextFireMs(spec, zone, after)
        require(atMillis > after) { "Следующий запуск должен быть позже текущего времени; проверьте интервал расписания." }
        return WorkflowTimeCronOccurrence(atMillis, wf.updatedAtMs, fingerprint(wf), zone.id, lastConsumedAtMillis)
    }

    private fun fingerprint(wf: WorkflowDefinition): String = wf.trigger.toString()

    private fun effectiveZone(spec: TriggerSpec.TimeCron): ZoneId =
        spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    companion object {
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_OCCURRENCE_AT_MS = "occurrence_at_ms"
        const val KEY_REVISION = "workflow_revision"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"

        /**
         * Returns null for arbitrary cron (one-shot path) or the period in ms for the
         * supported subset. Daily HH:mm = 24h for dialect validation only. @every Ns = N seconds. @hourly = 1h.
         * @daily = 24h. Calendar scheduling itself always computes a one-shot occurrence in its timezone.
         */
        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            if (!spec.timeOfDay.isNullOrBlank()) return 24L * 60 * 60 * 1000
            val cron = spec.cron?.trim() ?: return null
            // @every Ns
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLong()
                val unit = every.groupValues[2]
                return when (unit) {
                    // Sub-minute not supported (matches CronExpressionParser's @every Ns
                    // floor) — without this, WorkflowJson.validate() treats any positive
                    // n as valid since this function never returns null for "s", and the
                    // schedule silently degrades to an invalid one-shot interval
                    // instead of surfacing a validation error at creation time.
                    "s" -> if (n < 60) null else n * 1000
                    "m" -> n * 60 * 1000
                    "h" -> n * 60 * 60 * 1000
                    "d" -> n * 24 * 60 * 60 * 1000
                    else -> null
                }
            }
            return when (cron) {
                "@hourly" -> 60L * 60 * 1000
                "@daily", "@midnight" -> 24L * 60 * 60 * 1000
                "@weekly" -> 7L * 24 * 60 * 60 * 1000
                else -> null  // 5-field cron — fall back to one-shot
            }
        }

        /** Compute the next calendar occurrence in its effective timezone. */
        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            // time_of_day + optional days_of_week
            if (!spec.timeOfDay.isNullOrBlank()) {
                val (h, m) = spec.timeOfDay.split(":").let { it[0].toInt() to it[1].toInt() }
                val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                // Rebuild each date from HH:mm. Carrying an atZone() DST-gap adjustment
                // into plusDays() would move Monday 02:30 to 03:30 after a skipped Sunday.
                for (daysAhead in 0L..8L) {
                    val date = now.toLocalDate().plusDays(daysAhead)
                    if (allowed.isNotEmpty() && date.dayOfWeek !in allowed) continue
                    val candidate = date.atTime(LocalTime.of(h, m)).atZone(zone)
                    if (candidate.isAfter(now)) return candidate.toInstant().toEpochMilli()
                }
                error("Не удалось определить следующий день расписания.")
            }
            // @every Ns
            if (spec.cron?.trim()?.startsWith("@every") == true) {
                derivePeriodMs(spec)?.let { return Math.addExact(nowMs, it) }
            }
            // 5-field cron: compute the exact next execution via the shared parser
            // (same dialect as scheduled jobs). Without this, "0 9 * * 1" style
            // expressions silently degraded to hourly fires.
            spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
                me.rerere.rikkahub.service.CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                    me.rerere.rikkahub.service.CronExpressionParser.nextExecution(parsed, nowMs, zone)
                        ?.let { return it }
                }
            }
            // Fallback for unsupported cron — fire roughly hourly so the user gets
            // something useful even with an exotic schedule. Worker will re-evaluate.
            return nowMs + 60L * 60 * 1000
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
