package me.rerere.rikkahub.costguards

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import kotlin.uuid.Uuid
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation

/** Adapted from rikkahub-agent costguards/TokenBudgetTracker.kt; accounts selected branches. */
object TokenBudgetTracker {
    data class Totals(val inputTokens: Long, val outputTokens: Long, val totalTokens: Long,
                      val perMessageMax: Long, val messageCount: Int)
    enum class BudgetStatus { UNDER_SOFT, WARN, OVER_HARD, NO_BUDGET }
    data class Snapshot(val totals: Totals, val softCap: Int?, val hardCap: Int?, val status: BudgetStatus)

    fun saturatedAdd(a: Long, b: Long): Long {
        val left = a.coerceAtLeast(0)
        val right = b.coerceAtLeast(0)
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    internal fun usageTotal(usage: TokenUsage): Long = maxOf(
        usage.totalTokens.toLong().coerceAtLeast(0),
        saturatedAdd(usage.promptTokens.toLong(), usage.completionTokens.toLong()),
    )

    fun aggregate(conversation: Conversation): Totals = aggregate(
        conversation.messageNodes.mapNotNull { it.messages.getOrNull(it.selectIndex) })

    fun aggregate(messages: List<UIMessage>): Totals {
        var input = 0L
        var output = 0L
        var total = 0L
        var perMax = 0L
        var count = 0
        for (message in messages) {
            val usage = message.usage ?: continue
            input = saturatedAdd(input, usage.promptTokens.toLong())
            output = saturatedAdd(output, usage.completionTokens.toLong())
            val thisTotal = usageTotal(usage)
            total = saturatedAdd(total, thisTotal)
            perMax = maxOf(perMax, thisTotal)
            count++
        }
        return Totals(input, output, total, perMax, count)
    }

    fun classify(totals: Totals, softCap: Int?, hardCap: Int?): BudgetStatus = when {
        hardCap != null && totals.totalTokens >= hardCap.toLong().coerceAtLeast(0) -> BudgetStatus.OVER_HARD
        softCap != null && totals.totalTokens >= softCap.toLong().coerceAtLeast(0) -> BudgetStatus.WARN
        softCap == null && hardCap == null -> BudgetStatus.NO_BUDGET
        else -> BudgetStatus.UNDER_SOFT
    }

    fun snapshot(conversation: Conversation, softCap: Int?, hardCap: Int?): Snapshot {
        val totals = aggregate(conversation)
        return Snapshot(totals, softCap, hardCap, classify(totals, softCap, hardCap))
    }
}

class TokenBudgetExceededException : IllegalStateException("Достигнут предел токенов; продолжение остановлено.")
class TokenBudgetPersistenceException(message: String, cause: Throwable) : IOException(message, cause)
class TokenBudgetOwnerUnavailableException : IllegalStateException("Владелец бюджета больше недоступен; запуск остановлен.")

/**
 * One monotonic run ledger, shared by the parent and its children. Provider usage is late:
 * input estimates and a provider ignoring its output limit can overshoot one in-flight request.
 * Reservations prevent concurrent children spending the same remaining capacity. This is an
 * execution guard, not an exact billing boundary. Never recreate it for a retry/child.
 */
class TokenBudgetLedger(enabled: Boolean, softCap: Int? = null, hardCap: Int? = null,
                        initialMessages: List<UIMessage> = emptyList(), initialSpentTokens: Long = 0,
                        private val configurationSource: (() -> Configuration)? = null,
                        private val restoreSpent: (() -> Long)? = null,
                        private val durableCallback: ((spent: Long, submittedOutstanding: Long) -> Unit)? = null) {
    @Volatile var enabled: Boolean = enabled
        private set
    @Volatile var softCap: Int? = softCap
        private set
    @Volatile var hardCap: Int? = hardCap
        private set
    data class Configuration(val enabled: Boolean, val softCap: Int?, val hardCap: Int?)
    private var restored = restoreSpent == null
    data class State(val spentTokens: Long, val reservedTokens: Long, val remainingTokens: Long?,
                     val status: TokenBudgetTracker.BudgetStatus)
    private var spent = maxOf(initialSpentTokens.coerceAtLeast(0), TokenBudgetTracker.aggregate(initialMessages).totalTokens)
    private var limit = hardCap?.toLong()?.coerceAtLeast(0)
    private var durableFailure: IOException? = null
    private val reservations = mutableSetOf<Reservation>()

    private fun reservedTotal(submittedOnly: Boolean = false): Long = reservations.fold(0L) { total, request ->
        if (submittedOnly && !request.submitted) total else TokenBudgetTracker.saturatedAdd(total, request.remaining)
    }

    /** The ledger lock serializes writes with all provider and child accounting mutations. */
    private fun persist() {
        durableFailure?.let { throw it }
        try {
            durableCallback?.invoke(spent, reservedTotal(submittedOnly = true))
        } catch (error: Exception) {
            val failure = TokenBudgetPersistenceException("Не удалось сохранить бюджет токенов; новые запросы остановлены.", error)
            durableFailure = failure
            throw failure
        }
    }

    private fun refreshConfiguration() {
        configurationSource?.invoke()?.let { updateConfiguration(it.enabled, it.softCap, it.hardCap) }
    }

    @Synchronized fun updateConfiguration(enabled: Boolean, softCap: Int?, hardCap: Int?,
                                          initialMessages: List<UIMessage> = emptyList(), spentFloor: Long = 0) {
        if (enabled) durableFailure?.let { throw it }
        val recovered = if (enabled && !restored) {
            val value = restoreSpent?.invoke() ?: 0L
            restored = true
            value
        } else 0L
        this.enabled = enabled
        this.softCap = softCap
        this.hardCap = hardCap
        limit = hardCap?.toLong()?.coerceAtLeast(0)
        val historical = maxOf(TokenBudgetTracker.aggregate(initialMessages).totalTokens,
            spentFloor.coerceAtLeast(0), recovered.coerceAtLeast(0))
        if (historical > spent) {
            spent = historical
            if (enabled) persist()
        }
    }

    @Synchronized fun snapshot(): State {
        refreshConfiguration()
        val reserved = reservedTotal()
        val status = if (!enabled) TokenBudgetTracker.BudgetStatus.NO_BUDGET else
            TokenBudgetTracker.classify(TokenBudgetTracker.Totals(0, 0, spent, 0, 0), softCap, hardCap)
        return State(spent, reserved, if (enabled) limit?.let {
            val afterSpend = it - spent.coerceAtMost(it)
            afterSpend - reserved.coerceAtMost(afterSpend)
        } else null, status)
    }

    @Synchronized fun ensureCanContinue() {
        refreshConfiguration()
        if (!enabled) return
        durableFailure?.let { throw it }
        if (enabled && limit != null && spent >= limit!!) {
            persist()
            throw TokenBudgetExceededException()
        }
    }

    @Synchronized fun reserve(estimatedInputTokens: Long, requestedMaxTokens: Int?): Reservation {
        refreshConfiguration()
        if (!enabled) return Reservation(requestedMaxTokens, 0, false)
        durableFailure?.let { throw it }
        ensureCanContinue()
        val input = estimatedInputTokens.coerceAtLeast(0)
        val available = snapshot().remainingTokens
        if (available != null && input >= available) throw TokenBudgetExceededException()
        val output = if (available != null) {
            minOf((available - input).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                requestedMaxTokens?.coerceAtLeast(1) ?: Int.MAX_VALUE)
        } else requestedMaxTokens
        // An unbounded submitted request has no safe recovery estimate. Its conservative
        // outstanding upper bound is Long.MAX_VALUE until measured successful completion.
        val amount = if (output == null) Long.MAX_VALUE else
            TokenBudgetTracker.saturatedAdd(input, output.toLong().coerceAtLeast(0))
        return Reservation(output, amount, true).also { reservations.add(it) }
    }

    inner class Reservation internal constructor(val maxTokens: Int?, internal var remaining: Long,
                                                  private var accountingEnabled: Boolean) : AutoCloseable {
        private var input = 0L
        private var output = 0L
        private var observed = 0L
        private var hasUsage = false
        internal var submitted = false
        private var completed = false
        private var closed = false

        /** Persists an in-flight numeric reservation BEFORE the provider may bill. */
        fun markSubmitted() = synchronized(this@TokenBudgetLedger) {
            check(!closed) { "Reservation already closed" }
            refreshConfiguration()
            if (submitted) return@synchronized
            // A request admitted without caps cannot bill after a live enable: its params
            // were never bounded. Retry through a fresh reservation instead.
            if (enabled && !accountingEnabled) throw TokenBudgetExceededException()
            if (!enabled) {
                accountingEnabled = false
                remaining = 0
                reservations.remove(this)
                submitted = true
                return@synchronized
            }
            durableFailure?.let { throw it }
            if (accountingEnabled && enabled && limit != null &&
                (spent >= limit!! || reservedTotal() > limit!! - spent)) {
                throw TokenBudgetExceededException()
            }
            submitted = true
            persist()
        }

        /** Only a normally completed provider request may release unused capacity. */
        fun markCompleted() = synchronized(this@TokenBudgetLedger) {
            check(!closed) { "Reservation already closed" }
            completed = true
        }

        /** Providers emit cumulative snapshots, sometimes split across input/output events. */
        fun observe(usage: TokenUsage?) = synchronized(this@TokenBudgetLedger) {
            if (usage == null || closed || !accountingEnabled) return@synchronized
            hasUsage = true
            input = maxOf(input, usage.promptTokens.toLong().coerceAtLeast(0))
            output = maxOf(output, usage.completionTokens.toLong().coerceAtLeast(0))
            val next = maxOf(observed, usage.totalTokens.toLong().coerceAtLeast(0),
                TokenBudgetTracker.saturatedAdd(input, output))
            val delta = next - observed
            observed = next
            spent = TokenBudgetTracker.saturatedAdd(spent, delta)
            remaining -= minOf(delta, remaining)
            persist()
        }

        /** Cleanup may fail to persist, but must never replace cancellation/provider failure. */
        fun closePreserving(primary: Throwable?): Boolean {
            try {
                close()
                return true
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (cleanup !== primary) primary.addSuppressed(cleanup)
                return false
            }
        }

        override fun close() = synchronized(this@TokenBudgetLedger) {
            if (closed) return@synchronized
            // Failed/cancelled/no-metric requests retain their unknown output charge. A crash
            // before close is recovered equivalently from spent + submitted outstanding.
            if (accountingEnabled && submitted && (!completed || !hasUsage)) {
                spent = TokenBudgetTracker.saturatedAdd(spent, remaining)
            }
            remaining = 0
            closed = true
            reservations.remove(this)
            if (accountingEnabled) persist()
        }
    }
}

class TokenBudgetContext(val ledger: TokenBudgetLedger) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TokenBudgetContext>
}

/**
 * All supported providers merge custom bodies AFTER params, recursively. Bound both top-level
 * aliases and Google's nested configs; replace scalar/null whole-config overlays as well.
 */
fun boundedCustomBodies(bodies: List<CustomBody>, maxTokens: Int?): List<CustomBody> {
    if (maxTokens == null) return bodies
    val limitKeys = setOf("max_tokens", "max_completion_tokens", "max_output_tokens", "maxOutputTokens")
    fun bounded(value: kotlinx.serialization.json.JsonElement): JsonPrimitive {
        val requested = (value as? JsonPrimitive)?.intOrNull
        return JsonPrimitive(requested?.takeIf { it > 0 }?.coerceAtMost(maxTokens) ?: maxTokens)
    }
    return bodies.map { body -> when {
        body.key in limitKeys -> body.copy(value = bounded(body.value))
        body.key == "generationConfig" || body.key == "generation_config" -> {
            val values = (body.value as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            for (key in limitKeys) values[key]?.let { values[key] = bounded(it) }
            val key = if (body.key == "generationConfig") "maxOutputTokens" else "max_output_tokens"
            values[key] = values[key]?.let(::bounded) ?: JsonPrimitive(maxTokens)
            body.copy(value = JsonObject(values))
        }
        else -> body
    } }
}

/**
 * Private numeric-only store. Supply a directory BELOW Context.noBackupFilesDir so token counts
 * never enter backups. UUID filenames bind assistant + conversation; records contain no text,
 * tools, credentials, settings, or prompts. One singleton owns the cached conversation ledgers.
 */
class TokenBudgetStore(private val directory: File,
                       private val assistantSource: ((Uuid) -> Assistant?)? = null,
                       private val writeRecord: ((File, String) -> Unit)? = null) {
    private val ledgers = mutableMapOf<Pair<Uuid, Uuid>, TokenBudgetLedger>()

    @Synchronized fun getLedger(assistant: Assistant, conversationId: Uuid,
                                initialMessages: List<UIMessage>): TokenBudgetLedger {
        val ownerId = assistant.id
        val key = ownerId to conversationId
        fun latestAssistant(): Assistant {
            val latest = if (assistantSource == null) assistant else assistantSource.invoke(ownerId)
            if (latest == null || latest.id != ownerId) throw TokenBudgetOwnerUnavailableException()
            return latest
        }
        val current = latestAssistant()
        val enabled = current.localTools.contains(LocalToolOption.CostGuards)
        val file = File(directory, "${assistant.id}_$conversationId.tokens")
        ledgers[key]?.let {
            it.updateConfiguration(enabled, current.tokenBudgetSoftCap, current.tokenBudgetHardCap, initialMessages)
            return it
        }
        // A ledger's source reads only current immutable settings. It never acquires this
        // store's lock. Lazy first-enable recovery also runs under that ledger's lock.
        val recovered = if (enabled) readRecord(file) else 0L
        val source = if (assistantSource == null) null else {
            { val latest = assistantSource.invoke(ownerId)
                if (latest == null || latest.id != ownerId) throw TokenBudgetOwnerUnavailableException()
                TokenBudgetLedger.Configuration(latest.localTools.contains(LocalToolOption.CostGuards),
                    latest.tokenBudgetSoftCap, latest.tokenBudgetHardCap)
            }
        }
        return TokenBudgetLedger(enabled, current.tokenBudgetSoftCap, current.tokenBudgetHardCap,
            initialMessages, recovered, configurationSource = source,
            restoreSpent = if (enabled) null else ({ readRecord(file) }),
            durableCallback = { spent, pending ->
                val record = "1\n$spent\n$pending\n"
                if (writeRecord != null) writeRecord.invoke(file, record) else atomicWrite(file, record)
            }).also { ledgers[key] = it }
    }

    private fun readRecord(file: File): Long {
        if (!file.exists()) return 0
        try {
            if (!file.isFile || file.length() > 128) throw IOException("Invalid token budget record")
            val fields = file.readText(Charsets.US_ASCII).split('\n')
            if (fields.size != 4 || fields[0] != "1" || fields[3].isNotEmpty()) throw IOException("Invalid token budget record")
            val spent = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid token budget record")
            val pending = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid token budget record")
            return TokenBudgetTracker.saturatedAdd(spent, pending)
        } catch (error: Exception) {
            throw TokenBudgetPersistenceException("Не удалось прочитать бюджет токенов; новые запросы остановлены.", error)
        }
    }

    private fun atomicWrite(file: File, record: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create token budget directory")
        val temporary = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(record.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            // Same-directory atomic rename after fsync is the AtomicFile-equivalent boundary.
            // No non-atomic fallback: failure must stop the next provider call.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
