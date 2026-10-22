package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent

internal data class WorkflowBootEvent(
    val id: String,
    val workflowIds: List<String>?,
    val claimed: Set<String>,
    val complete: Boolean,
)

/** IDs only, outside system backups. A checked atomic write precedes every possible effect. */
internal class WorkflowBootOutbox(context: Context) {
    private val file = File(context.noBackupFilesDir, "workflow-boot-outbox.json")

    fun accept(id: String): WorkflowBootEvent = update { all ->
        all[id] ?: WorkflowBootEvent(id, null, emptySet(), false).also { all[id] = it }
    }
    fun get(id: String): WorkflowBootEvent? = synchronized(lock) { read()[id] }
    fun pending(): List<WorkflowBootEvent> = synchronized(lock) { read().values.filterNot { it.complete } }

    /** Snapshot once; later retries must not adopt workflows created after this event. */
    fun snapshot(id: String, workflowIds: List<String>): WorkflowBootEvent = update { all ->
        val event = all[id] ?: throw IOException("Событие загрузки не найдено.")
        if (event.workflowIds != null || event.complete) event else
            event.copy(workflowIds = workflowIds.distinct()).also { all[id] = it }
    }

    /** A claim is monotonic, including interruption between this commit and engine.beginRun. */
    fun claim(id: String, workflowId: String): Boolean = update { all ->
        val event = all[id] ?: return@update false
        if (event.complete || workflowId !in event.workflowIds.orEmpty() || workflowId in event.claimed) false
        else {
            all[id] = event.copy(claimed = event.claimed + workflowId)
            true
        }
    }
    fun finish(id: String) = update { all ->
        val event = all[id] ?: throw IOException("Событие загрузки не найдено.")
        check(event.workflowIds != null && event.workflowIds.all { it in event.claimed })
        all[id] = event.copy(complete = true)
    }

    private fun <T> update(block: (LinkedHashMap<String, WorkflowBootEvent>) -> T): T = synchronized(lock) {
        val all = read()
        val before = all.toMap()
        val result = block(all)
        if (all != before) write(all)
        result
    }
    private fun read(): LinkedHashMap<String, WorkflowBootEvent> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return linkedMapOf()
        return try {
            val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
            linkedMapOf<String, WorkflowBootEvent>().apply {
                root.keys().forEach { id ->
                    require(id.matches(Regex("[0-9a-f]{64}")))
                    val value = root.getJSONObject(id)
                    require(value.has("workflows"))
                    fun strings(key: String): List<String> = value.getJSONArray(key).let { array ->
                        (0 until array.length()).map {
                            val item = array.get(it)
                            require(item is String)
                            item
                        }
                    }
                    val ids = if (value.isNull("workflows")) null else strings("workflows")
                    val claimed = strings("claimed").toSet()
                    require(claimed.all { it in ids.orEmpty() })
                    val complete = value.getBoolean("complete")
                    require(!complete || (ids != null && ids.all { it in claimed }))
                    put(id, WorkflowBootEvent(id, ids, claimed, complete))
                }
            }
        } catch (_: Exception) {
            // Never treat damaged/unreadable receipts as an empty queue: that could replay effects.
            throw IOException("Не удалось прочитать журнал запуска сценариев после загрузки.")
        }
    }
    private fun write(all: Map<String, WorkflowBootEvent>) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val text = JSONObject().apply { all.forEach { (id, event) ->
            put(id, JSONObject().put("workflows", event.workflowIds?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("claimed", JSONArray(event.claimed.toList())).put("complete", event.complete))
        } }.toString()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        var committed = false
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            committed = true
            // AtomicFile logs some rename failures instead of throwing. Check the committed value.
            check(atomic.openRead().bufferedReader().use { it.readText() } == text)
        } catch (_: Exception) {
            // На Android 26–29 failWrite после finishWrite удаляет единственный committed файл.
            if (!committed) atomic.failWrite(stream)
            throw IOException("Не удалось сохранить журнал запуска сценариев после загрузки.")
        }
    }
    private companion object { val lock = Any() }
}

/** The receiver awaits only this durable handoff; Android owns the eventual worker lifetime. */
internal class WorkflowBootScheduling(private val context: Context) {
    private val outbox = WorkflowBootOutbox(context)

    suspend fun accept(source: String) {
        require(source == "BOOT" || source == "APP_REPLACED")
        // A package update must not turn a duplicate BOOT broadcast into a new boot event.
        // BOOT_COUNT is stable across process death and clock changes within the same boot.
        val identity = if (source == "BOOT") {
            "$source/${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)}"
        } else {
            "$source/${context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime}"
        }
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val event = handoffMutex.withLock {
            var accepted = runInterruptible(Dispatchers.IO) { outbox.accept(id) }
            if (accepted.complete) return@withLock accepted
            if (accepted.workflowIds == null) {
                try {
                    // Fast empty-set detection is optional. A slow DB must leave the receiver
                    // enough time to commit Android ownership; the worker then takes its snapshot.
                    val ids = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
                        KoinJavaComponent.getKoin().get<WorkflowRepository>().listEnabledForBootExecution()
                            .filter { it.definition.trigger is TriggerSpec.BootCompleted }.map { it.definition.id }
                    }
                    if (ids != null) accepted = runInterruptible(Dispatchers.IO) { outbox.snapshot(id, ids) }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // The accepted event remains durable; its worker can retry dependency/DB lookup.
                }
            }
            if (accepted.workflowIds?.isEmpty() == true) {
                runInterruptible(Dispatchers.IO) { outbox.finish(id) }
                accepted = accepted.copy(complete = true)
            }
            accepted
        }
        if (!event.complete) enqueue(id)
    }
    suspend fun restoreQueued() {
        // Do not race an empty receiver snapshot into an unnecessary Android executor.
        val pending = handoffMutex.withLock { runInterruptible(Dispatchers.IO) { outbox.pending() } }
        for (event in pending) enqueue(event.id)
    }
    private suspend fun enqueue(id: String) = runInterruptible(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<WorkflowBootWorker>()
            .setInputData(Data.Builder().putString(EVENT_ID, id).build())
            .addTag("workflow_boot_events")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()
        WorkManager.getInstance(context).enqueueUniqueWork("workflow_boot:$id", ExistingWorkPolicy.KEEP, request)
            .result.get(6L, TimeUnit.SECONDS)
        Unit
    }
    companion object {
        const val EVENT_ID = "workflow_boot_event_id"
        private const val SNAPSHOT_TIMEOUT_MILLIS = 250L
        private val handoffMutex = Mutex()
    }
}

/** Retries may settle storage or execute unclaimed workflows; claimed effects are never replayed. */
class WorkflowBootWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters), KoinComponent {
    private val repository: WorkflowRepository by inject()
    private val engine: WorkflowEngine by inject()

    override suspend fun doWork(): Result {
        val id = inputData.getString(WorkflowBootScheduling.EVENT_ID)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return Result.failure()
        val outbox = WorkflowBootOutbox(applicationContext)
        return try {
            currentCoroutineContext().ensureActive()
            var event = outbox.get(id) ?: return Result.success()
            if (event.complete) return Result.success()
            if (event.workflowIds?.all { it in event.claimed } == true) {
                // Only the final journal commit remains, even if DI/Room is unavailable now.
                outbox.finish(id)
                return Result.success()
            }
            // Resolve dependencies and close old engine receipts before any new claim.
            val executor = engine
            val source = repository
            source.recoverInterrupted()
            if (event.workflowIds == null) {
                val ids = source.listEnabledForBootExecution().filter { it.definition.trigger is TriggerSpec.BootCompleted }
                    .map { it.definition.id }
                event = outbox.snapshot(id, ids)
            }
            for (workflowId in event.workflowIds.orEmpty()) {
                currentCoroutineContext().ensureActive()
                if (workflowId in event.claimed) continue
                val live = source.getForBootExecution(workflowId)
                if (!outbox.claim(id, workflowId)) continue
                if (live?.definition?.trigger !is TriggerSpec.BootCompleted) continue
                try {
                    // Re-checks enabled/owner, conditions, cooldown, approvals and HARDLINE.
                    // FAILED/uncertain results remain claimed even when history persistence failed.
                    executor.fire(workflowId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w("WorkflowTrigger", "Запуск сценария после загрузки прерван (${error.javaClass.simpleName}); отметка запуска сохранена")
                }
            }
            outbox.finish(id)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Claim commits survive this retry, including completed/uncertain engine outcomes.
            Result.retry()
        }
    }
}
