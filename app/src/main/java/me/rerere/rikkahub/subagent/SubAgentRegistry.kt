package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** All ownership checks, slot reservations, and job attachment use one lock. */
class SubAgentRegistry(
    directory: java.io.File? = null,
    cipher: me.rerere.rikkahub.data.repository.CronPayloadCipher? = null,
    private val processId: String = PROCESS_TOKEN,
) {
    private val store = if (directory != null && cipher != null) SubAgentRunStore(directory, cipher) else null
    private var loaded = false
    private val runs = linkedMapOf<String, SubAgentRun>()
    private val jobs = mutableMapOf<String, Job>()
    // Keep the computed receipt until its atomic write succeeds; retries must never recompute a task.
    private val pendingTerminalWrites = mutableSetOf<String>()

    private fun visibleRun(run: SubAgentRun): SubAgentRun {
        if (run.id in pendingTerminalWrites) {
            // A later status poll can outlive a brief disk/KeyStore outage. Retry the exact receipt only.
            try {
                persistTerminal(run.id)
            } catch (_: java.io.IOException) {
                return run.copy(errorCode = "result_storage_unavailable")
            }
        }
        return run
    }

    private fun persistTerminal(id: String) {
        store?.write(runs.getValue(id), processId)
        pendingTerminalWrites.remove(id)
        jobs.remove(id)
    }
    /** Reads are lazy so DI construction does not perform disk or KeyStore work on the UI thread. */
    private fun ensureLoaded() {
        if (loaded) return
        val restored = store?.readAll().orEmpty().sortedBy { it.run.startedAtMs }.takeLast(100)
        val recoveredRuns = linkedMapOf<String, SubAgentRun>()
        for (entry in restored) {
            val run = entry.run
            val recovered = if (entry.processId != processId && !run.status.terminal) run.copy(
                status = SubAgentStatus.INDETERMINATE, errorCode = "process_interrupted",
                finishedAtMs = System.currentTimeMillis()) else run
            if (recovered != run) store?.write(recovered, processId)
            recoveredRuns[run.id] = recovered
        }
        // A failed scan or recovery write leaves initialization retryable and publishes no partial history.
        runs.putAll(recoveredRuns)
        loaded = true
    }

    @Synchronized internal fun snapshot(): List<SubAgentRun> { ensureLoaded(); return runs.values.map(::visibleRun) }

    @Synchronized fun recoverInterrupted() { ensureLoaded() }

    @Synchronized fun reserve(run: SubAgentRun, perAssistantCap: Int): Boolean {
        ensureLoaded()
        if (perAssistantCap !in 1..8 || runs.containsKey(run.id)) return false
        val active = runs.values.filterNot { it.status.terminal }
        if (active.size >= 30 || active.count { it.owner.assistantId == run.owner.assistantId } >= perAssistantCap) return false
        val oldest = if (runs.size >= 100) {
            runs.values.firstOrNull { it.status.terminal && it.id !in pendingTerminalWrites } ?: return false
        } else null
        store?.write(run, processId)
        if (oldest != null) {
            store?.delete(oldest.id)
            runs.remove(oldest.id)
        }
        runs[run.id] = run
        return true
    }
    @Synchronized fun get(owner: SubAgentOwner, id: String): SubAgentRun? {
        ensureLoaded()
        return runs[id]?.takeIf { it.owner == owner }?.let(::visibleRun)
    }
    @Synchronized fun list(owner: SubAgentOwner, activeOnly: Boolean = false): List<SubAgentRun> {
        ensureLoaded()
        return runs.values.filter { it.owner == owner && (!activeOnly || !it.status.terminal) }.map(::visibleRun)
    }
    @Synchronized fun attach(owner: SubAgentOwner, id: String, job: Job): Boolean {
        val run = get(owner, id)
        if (run == null || run.status.terminal || jobs.containsKey(id)) { job.cancel(); return false }
        val running = run.copy(status = SubAgentStatus.RUNNING)
        try { store?.write(running, processId) } catch (error: Exception) { job.cancel(); throw error }
        jobs[id] = job
        runs[id] = running
        return true
    }
    @Synchronized fun finish(owner: SubAgentOwner, id: String, transform: (SubAgentRun) -> SubAgentRun) {
        ensureLoaded()
        val run = runs[id]?.takeIf { it.owner == owner } ?: return
        if (run.status.terminal) {
            if (id in pendingTerminalWrites) persistTerminal(id)
            return
        }
        val updated = transform(run)
        require(updated.id == id && updated.owner == owner && updated.status.terminal)
        runs[id] = updated.copy(finishedAtMs = updated.finishedAtMs ?: System.currentTimeMillis())
        pendingTerminalWrites.add(id)
        persistTerminal(id)
    }
    @Synchronized fun cancel(owner: SubAgentOwner, id: String): Boolean {
        ensureLoaded()
        val run = runs[id]?.takeIf { it.owner == owner } ?: return false
        if (run.status.terminal) {
            if (id !in pendingTerminalWrites) return false
            persistTerminal(id)
            return run.status == SubAgentStatus.CANCELLED
        }
        val job = jobs[id]
        if (job == null) {
            runs[id] = run.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis())
            pendingTerminalWrites.add(id)
            persistTerminal(id)
        } else {
            // Keep its slot until coroutine cleanup records the truthful terminal state.
            job.cancel()
        }
        return true
    }
    @Synchronized fun cancelAll(owner: SubAgentOwner): Int = list(owner, activeOnly = true).count { cancel(owner, it.id) }
    /** Snapshot and cancel under the ownership lock; await actual cleanup without holding that lock. */
    suspend fun cancelAllAndJoin(owner: SubAgentOwner): Int = withContext(NonCancellable + Dispatchers.IO) {
        val (count, attached) = synchronized(this) {
            ensureLoaded()
            val owned = runs.values.filter { it.owner == owner && !it.status.terminal }
            val snapshot = owned.mapNotNull { jobs[it.id] }
            owned.forEach { cancel(owner, it.id) }
            owned.size to snapshot
        }
        withContext(NonCancellable) { attached.forEach { it.join() } }
        count
    }

    companion object { private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString() }
}
