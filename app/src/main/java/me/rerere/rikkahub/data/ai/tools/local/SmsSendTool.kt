// Adapted from ExTV/rikkahub-agent, local/SmsSendTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.ui.activity.SmsSentReceiver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class SmsRecipientState(val value: String, val terminal: Boolean) {
    Pending("pending", false), Delivered("delivered", true), Failed("delivery_error", true),
    Unconfirmed("unconfirmed", true)
}

internal data class SmsRecipientReceipt(
    val androidResultCode: Int,
    val reportStatus: Int?,
    val format: String?,
    val state: SmsRecipientState
)

internal data class SmsDeliveryResult(
    val sentParts: Int,
    val unknownParts: Int,
    val failedParts: Map<Int, Int>,
    val modemErrors: Map<Int, Int> = emptyMap(),
    val deliveryReports: Map<Int, SmsRecipientReceipt> = emptyMap(),
    val timedOut: Boolean = false,
    val nativeErrorType: String? = null
) {
    val success: Boolean get() = unknownParts == 0 && failedParts.isEmpty() && nativeErrorType == null
    val recipientDeliveryConfirmed: Boolean get() {
        val total = sentParts + unknownParts + failedParts.size
        return total > 0 && deliveryReports.size == total &&
            deliveryReports.values.all { it.state == SmsRecipientState.Delivered }
    }
}

/** SmsMessage.status uses GSM TP-Status or a CDMA status shifted into bits 31–16. */
internal fun smsRecipientState(code: Int, status: Int?, format: String?): SmsRecipientState {
    if (code != -1) return SmsRecipientState.Failed
    if (status == null || status < 0) return SmsRecipientState.Unconfirmed
    return when (format) {
        "3gpp" -> when (status) {
            0 -> SmsRecipientState.Delivered
            in 0x20..0x3f -> SmsRecipientState.Pending
            in 0x40..0x7f -> SmsRecipientState.Failed
            else -> SmsRecipientState.Unconfirmed
        }
        "3gpp2" -> when {
            status == (2 shl 16) -> SmsRecipientState.Delivered
            status == 0 || ((status ushr 24) and 0x03) == 2 -> SmsRecipientState.Pending
            status == (3 shl 16) || ((status ushr 24) and 0x03) == 3 -> SmsRecipientState.Failed
            else -> SmsRecipientState.Unconfirmed
        }
        else -> SmsRecipientState.Unconfirmed
    }
}

/** Exception messages and arbitrary class names can contain private data. */
internal fun smsNativeExceptionType(error: Exception): String = when (error) {
    is SecurityException -> "SecurityException"
    is IllegalArgumentException -> "IllegalArgumentException"
    is UnsupportedOperationException -> "UnsupportedOperationException"
    is IllegalStateException -> "IllegalStateException"
    is NullPointerException -> "NullPointerException"
    is RuntimeException -> "RuntimeException"
    else -> "Exception"
}

internal class SmsReceipt(private val total: Int) {
    private val outcomes = mutableMapOf<Int, Int>()
    private val modemErrors = mutableMapOf<Int, Int>()
    private val deliveryReports = mutableMapOf<Int, SmsRecipientReceipt>()
    private val result = CompletableDeferred<SmsDeliveryResult>()
    private val deliveryResult = CompletableDeferred<SmsDeliveryResult>()

    @Synchronized fun acknowledge(part: Int, code: Int, modemCode: Int? = null): Boolean {
        if (part !in 0 until total || part in outcomes) return false
        outcomes[part] = code
        if (code != -1 && modemCode != null) modemErrors[part] = modemCode
        if (outcomes.size == total) result.complete(snapshot())
        return true
    }

    @Synchronized fun acknowledgeDelivery(part: Int, code: Int, status: Int?, format: String?): Boolean {
        if (part !in 0 until total || deliveryReports[part]?.state?.terminal == true) return false
        val report = SmsRecipientReceipt(code, status, format, smsRecipientState(code, status, format))
        if (deliveryReports[part] == report) return false
        deliveryReports[part] = report
        if (deliveryReports.size == total && deliveryReports.values.all { it.state.terminal }) {
            deliveryResult.complete(snapshot())
        }
        return true
    }

    @Synchronized fun snapshot(timedOut: Boolean = false): SmsDeliveryResult = SmsDeliveryResult(
        sentParts = outcomes.values.count { it == -1 },
        unknownParts = total - outcomes.size,
        failedParts = outcomes.filterValues { it != -1 }.toMap(),
        modemErrors = modemErrors.toMap(),
        deliveryReports = deliveryReports.toMap(),
        timedOut = timedOut
    )

    suspend fun await(): SmsDeliveryResult = result.await()
    suspend fun awaitDelivery(): SmsDeliveryResult {
        deliveryResult.await()
        return snapshot()
    }
}
internal class SmsDeliveryBuffer {
    private val pending = ConcurrentHashMap<String, SmsReceipt>()
    fun register(id: String, parts: Int): SmsReceipt {
        require(id.isNotBlank() && id.length <= 64 && parts in 1..64)
        val receipt = SmsReceipt(parts)
        check(pending.putIfAbsent(id, receipt) == null)
        return receipt
    }
    fun acknowledge(id: String, part: Int, code: Int, modemCode: Int? = null): Boolean =
        pending[id]?.acknowledge(part, code, modemCode) ?: false
    fun acknowledgeDelivery(id: String, part: Int, code: Int, status: Int?, format: String?): Boolean =
        pending[id]?.acknowledgeDelivery(part, code, status, format) ?: false
    fun remove(id: String) { pending.remove(id) }
}
internal val sharedSmsDeliveries = SmsDeliveryBuffer()
internal fun smsOutcomeJson(outcome: SmsDeliveryResult, total: Int): JsonObject = buildJsonObject {
    put("success", outcome.success)
    put("status", when {
        outcome.nativeErrorType != null -> "native_error"
        outcome.failedParts.isNotEmpty() -> "android_error"
        outcome.unknownParts > 0 -> "timeout"
        else -> "sent"
    })
    put("parts_total", total)
    put("parts_sent", outcome.sentParts)
    put("parts_unknown", outcome.unknownParts)
    put("sending_timed_out", outcome.timedOut && outcome.unknownParts > 0)
    put("recipient_delivery_confirmed", outcome.recipientDeliveryConfirmed)
    put("automatic_retry_allowed", false)
    outcome.nativeErrorType?.let { put("native_error_type", it) }
    put("failed_parts", buildJsonArray {
        outcome.failedParts.toSortedMap().forEach { (part, code) -> addJsonObject {
            put("part", part + 1)
            put("android_result_code", code)
            outcome.modemErrors[part]?.let { put("modem_error_code", it) }
        } }
    })
    val delivered = outcome.deliveryReports.values.count { it.state == SmsRecipientState.Delivered }
    val deliveryFailed = outcome.deliveryReports.values.count { it.state == SmsRecipientState.Failed }
    val deliveryUnconfirmed = outcome.deliveryReports.values.count { it.state == SmsRecipientState.Unconfirmed }
    val deliveryPending = total - delivered - deliveryFailed - deliveryUnconfirmed
    put("parts_delivery_confirmed", delivered)
    put("parts_delivery_failed", deliveryFailed)
    put("parts_delivery_unconfirmed", deliveryUnconfirmed)
    put("parts_delivery_pending", deliveryPending)
    put("delivery_status", when {
        outcome.recipientDeliveryConfirmed -> "delivered"
        deliveryFailed > 0 -> "delivery_error"
        outcome.timedOut && deliveryPending > 0 -> "timeout"
        deliveryPending > 0 -> "pending"
        else -> "unconfirmed"
    })
    put("delivery_parts", buildJsonArray {
        outcome.deliveryReports.toSortedMap().forEach { (part, report) -> addJsonObject {
            put("part", part + 1)
            put("status", report.state.value)
            put("android_result_code", report.androidResultCode)
            report.reportStatus?.let { put("report_status", it) }
            report.format?.let { put("format", it) }
        } }
    })
    if (!outcome.success) put("error", "Отправка не подтверждена полностью. Некоторые части уже могли уйти. Не отправляйте сообщение повторно без проверки и нового решения пользователя.")
}

private fun smsCallbackIntent(context: Context, id: String, part: Int, delivered: Boolean): PendingIntent {
    val stage = if (delivered) "delivery" else "sent"
    val intent = Intent(context, SmsSentReceiver::class.java).apply {
        action = if (delivered) SmsSentReceiver.ACTION_DELIVERY else SmsSentReceiver.ACTION_SENT
        data = Uri.parse("rikka-sms://receipt/$id/$stage/$part")
    }
    // Android fills in modem errorCode / delivery PDU. The fixed explicit component, action and
    // unique data keep mutable callbacks scoped to this request without exposing recipient/body.
    val mutability = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(context, part, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
}

fun smsSendTool(context: Context): Tool = personalJsonTool(context, "send_sms",
    "Send one SMS to one phone number (body at most 4096 characters). Long messages split into parts. Requires SEND_SMS; operator charges may apply. Optional subscription_id for SIM choice, otherwise Android default SMS SIM. Waits at most 30 seconds total for separate sent and delivery callbacks. Success confirms network submission; recipient_delivery_confirmed requires delivery reports for every part. Reports Android result codes, modem codes when supplied, native exception types and timeout. Never retry automatically, including partial or unconfirmed sending.", LocalToolOption.SmsSend,
    InputSchema.Obj(buildJsonObject {
        put("recipient", buildJsonObject { put("type", "string"); put("maxLength", 48) })
        put("body", buildJsonObject { put("type", "string"); put("maxLength", 4096) })
        put("subscription_id", buildJsonObject { put("type", "integer"); put("minimum", 0) })
    }, listOf("recipient", "body")), ::validateSmsArguments, read = { args ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return@personalJsonTool deviceToolError("Разрешение отправки SMS отозвано.", Manifest.permission.SEND_SMS)
        var requestId: String? = null
        var receipt: SmsReceipt? = null
        var total = 0
        val sent = ArrayList<PendingIntent>()
        val delivery = ArrayList<PendingIntent>()
        try {
            val subscription = resolveSmsSubscriptionId(args.subscriptionId) { SubscriptionManager.getDefaultSmsSubscriptionId() }
                ?: return@personalJsonTool deviceToolError("В Android не выбрана SIM для SMS. Выберите её в настройках Android или укажите subscription_id.")
            val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscription)
                else { @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription) }
            if (manager == null) return@personalJsonTool deviceToolError("Отправка SMS на устройстве недоступна.")
            val parts = manager.divideMessage(args.body)
            if (parts.isNullOrEmpty() || parts.size > 64) return@personalJsonTool deviceToolError("Сообщение не удалось разделить на допустимое число частей.")
            total = parts.size
            val id = UUID.randomUUID().toString()
            requestId = id
            val activeReceipt = sharedSmsDeliveries.register(id, total)
            receipt = activeReceipt
            parts.indices.forEach { part ->
                sent += smsCallbackIntent(context, id, part, delivered = false)
                delivery += smsCallbackIntent(context, id, part, delivered = true)
            }
            // GenerationLoop checkpointed this call before launch, so interrupted calls never auto-repeat.
            if (total == 1) manager.sendTextMessage(args.recipient, null, parts[0], sent[0], delivery[0])
            else manager.sendMultipartTextMessage(args.recipient, null, parts, sent, delivery)
            val outcome = withTimeoutOrNull(30_000) {
                if (activeReceipt.await().success) activeReceipt.awaitDelivery()
                activeReceipt.snapshot()
            }
            smsOutcomeJson(outcome ?: activeReceipt.snapshot(timedOut = true), total)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A native call can fail after submitting some parts. Preserve receipts, without its text.
            val snapshot = receipt?.snapshot() ?: SmsDeliveryResult(0, total, emptyMap())
            smsOutcomeJson(snapshot.copy(nativeErrorType = smsNativeExceptionType(error)), total)
        } finally {
            requestId?.let(sharedSmsDeliveries::remove)
            sent.forEach { it.cancel() }
            delivery.forEach { it.cancel() }
        }
    })
