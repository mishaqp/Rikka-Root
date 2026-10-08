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

internal data class SmsDeliveryResult(val sentParts: Int, val unknownParts: Int, val failedParts: Map<Int, Int>) {
    val success: Boolean get() = unknownParts == 0 && failedParts.isEmpty()
}
internal class SmsReceipt(private val total: Int) {
    private val outcomes = mutableMapOf<Int, Int>()
    private val result = CompletableDeferred<SmsDeliveryResult>()
    @Synchronized fun acknowledge(part: Int, code: Int): Boolean {
        if (part !in 0 until total || part in outcomes) return false
        outcomes[part] = code
        if (outcomes.size == total) result.complete(snapshot())
        return true
    }
    @Synchronized fun snapshot(): SmsDeliveryResult = SmsDeliveryResult(outcomes.values.count { it == -1 }, total - outcomes.size, outcomes.filterValues { it != -1 }.toMap())
    suspend fun await(): SmsDeliveryResult = result.await()
}
internal class SmsDeliveryBuffer {
    private val pending = ConcurrentHashMap<String, SmsReceipt>()
    fun register(id: String, parts: Int): SmsReceipt {
        require(id.isNotBlank() && id.length <= 64 && parts in 1..64)
        val receipt = SmsReceipt(parts)
        check(pending.putIfAbsent(id, receipt) == null)
        return receipt
    }
    fun acknowledge(id: String, part: Int, code: Int): Boolean = pending[id]?.acknowledge(part, code) ?: false
    fun remove(id: String) { pending.remove(id) }
}
internal val sharedSmsDeliveries = SmsDeliveryBuffer()
internal fun smsOutcomeJson(outcome: SmsDeliveryResult, total: Int): JsonObject = buildJsonObject {
    put("success", outcome.success); put("parts_total", total); put("parts_sent", outcome.sentParts)
    put("parts_unknown", outcome.unknownParts); put("recipient_delivery_confirmed", false)
    put("automatic_retry_allowed", false)
    put("failed_parts", buildJsonArray { outcome.failedParts.forEach { (part, code) -> addJsonObject { put("part", part + 1); put("android_result_code", code) } } })
    if (!outcome.success) put("error", "Отправка не подтверждена полностью. Некоторые части уже могли уйти. Не отправляйте сообщение повторно без проверки и нового решения пользователя.")
}

fun smsSendTool(context: Context): Tool = personalJsonTool(context, "send_sms",
    "Send one SMS to one phone number (body at most 4096 characters). Long messages split into parts. Requires SEND_SMS; operator charges may apply. Optional subscription_id for SIM choice, otherwise Android default SMS SIM. Waits for sent callbacks for each part (60 seconds); success confirms submission to the network, not recipient delivery. Partial/unknown results must not be retried automatically.", LocalToolOption.SmsSend,
    InputSchema.Obj(buildJsonObject {
        put("recipient", buildJsonObject { put("type", "string"); put("maxLength", 48) })
        put("body", buildJsonObject { put("type", "string"); put("maxLength", 4096) })
        put("subscription_id", buildJsonObject { put("type", "integer"); put("minimum", 0) })
    }, listOf("recipient", "body")), ::validateSmsArguments, read = { args ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return@personalJsonTool deviceToolError("Разрешение отправки SMS отозвано.", Manifest.permission.SEND_SMS)
        val subscription = args.subscriptionId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subscription)) return@personalJsonTool deviceToolError("В Android не выбрана SIM для SMS. Выберите её в настройках Android или укажите subscription_id.")
        val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscription)
            else { @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription) }
        if (manager == null) return@personalJsonTool deviceToolError("Отправка SMS на устройстве недоступна.")
        val parts = manager.divideMessage(args.body)
        if (parts.isNullOrEmpty() || parts.size > 64) return@personalJsonTool deviceToolError("Сообщение не удалось разделить на допустимое число частей.")
        val id = UUID.randomUUID().toString()
        val receipt = sharedSmsDeliveries.register(id, parts.size)
        val sent = ArrayList<PendingIntent>()
        try {
            parts.indices.forEach { part ->
                val intent = Intent(context, SmsSentReceiver::class.java).apply {
                    data = Uri.parse("rikka-sms://$id/$part")
                    putExtra(SmsSentReceiver.EXTRA_REQUEST_ID, id); putExtra(SmsSentReceiver.EXTRA_PART, part)
                }
                sent += PendingIntent.getBroadcast(context, part, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            // GenerationLoop checkpointed this call before launch, so interrupted calls never auto-repeat.
            try {
                if (parts.size == 1) manager.sendTextMessage(args.recipient, null, parts[0], sent[0], null)
                else manager.sendMultipartTextMessage(args.recipient, null, parts, sent, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // A native call can fail after submitting some parts. Do not encourage a duplicate.
                return@personalJsonTool smsOutcomeJson(receipt.snapshot(),parts.size)
            }
            val outcome = withTimeoutOrNull(60000) { receipt.await() } ?: receipt.snapshot()
            smsOutcomeJson(outcome,parts.size)
        } finally {
            sharedSmsDeliveries.remove(id)
            sent.forEach { it.cancel() }
        }
    })
