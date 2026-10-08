package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** All ownership checks, slot reservations, and job attachment use one lock. */
class SubAgentRegistry {
    private val runs = linkedMapOf<String, SubAgentRun>()
    private val jobs = mutableMapOf<String, Job>()
    @Synchronized fun reserve(run: SubAgentRun, perAssistantCap: Int): Boolean {
        if (perAssistantCap !in 1..8 || runs.containsKey(run.id)) return false
        val active = runs.values.filterNot { it.status.terminal }
        if (active.size >= 30 || active.count { it.owner.assistantId == run.owner.assistantId } >= perAssistantCap) return false
        while (runs.size >= 100) {
            val oldest = runs.values.firstOrNull { it.status.terminal } ?: return false
            runs.remove(oldest.id)
        }
        runs[run.id] = run
        return true
    }
    @Synchronized fun get(owner: SubAgentOwner, id: String): SubAgentRun? = runs[id]?.takeIf { it.owner == owner }
    @Synchronized fun list(owner: SubAgentOwner, activeOnly: Boolean = false): List<SubAgentRun> =
        runs.values.filter { it.owner == owner && (!activeOnly || !it.status.terminal) }
    @Synchronized fun attach(owner: SubAgentOwner, id: String, job: Job): Boolean {
        val run = get(owner, id)
        if (run == null || run.status.terminal || jobs.containsKey(id)) { job.cancel(); return false }
        jobs[id] = job
        runs[id] = run.copy(status = SubAgentStatus.RUNNING)
        return true
    }
    @Synchronized fun finish(owner: SubAgentOwner, id: String, transform: (SubAgentRun) -> SubAgentRun) {
        val run = get(owner, id) ?: return
        if (run.status.terminal) return
        val updated = transform(run)
        require(updated.id == id && updated.owner == owner && updated.status.terminal)
        runs[id] = updated.copy(finishedAtMs = updated.finishedAtMs ?: System.currentTimeMillis())
        jobs.remove(id)
    }
    @Synchronized fun cancel(owner: SubAgentOwner, id: String): Boolean {
        val run = get(owner, id)?.takeUnless { it.status.terminal } ?: return false
        val job = jobs[id]
        if (job == null) {
            runs[id] = run.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis())
        } else {
            // Keep its slot until coroutine cleanup records the truthful terminal state.
            job.cancel()
        }
        return true
    }
    @Synchronized fun cancelAll(owner: SubAgentOwner): Int = list(owner, activeOnly = true).count { cancel(owner, it.id) }
    /** Snapshot and cancel under the ownership lock; await actual cleanup without holding that lock. */
    suspend fun cancelAllAndJoin(owner: SubAgentOwner): Int {
        val (count, attached) = synchronized(this) {
            val owned = runs.values.filter { it.owner == owner && !it.status.terminal }
            val snapshot = owned.mapNotNull { jobs[it.id] }
            owned.forEach { cancel(owner, it.id) }
            owned.size to snapshot
        }
        withContext(NonCancellable) { attached.forEach { it.join() } }
        return count
    }

}
