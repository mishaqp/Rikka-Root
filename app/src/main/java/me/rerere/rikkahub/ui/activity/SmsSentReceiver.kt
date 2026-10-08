// Sent-receipt handling for the adapted Agent SMS tool (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import me.rerere.rikkahub.data.ai.tools.local.sharedSmsDeliveries

/** Explicit SMS callbacks; the manifest keeps this receiver non-exported. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Mutable extras carry only Android's report; identity comes from fixed PendingIntent data.
        val uri = intent.data ?: return
        if (uri.scheme != "rikka-sms" || uri.host != "receipt") return
        val path = uri.pathSegments
        if (path.size != 3) return
        val id = path[0]
        if (id.isBlank() || id.length > 64) return
        val part = path[2].toIntOrNull() ?: return
        when (intent.action) {
            ACTION_SENT -> if (path[1] == "sent") {
                val modemCode = if (intent.hasExtra("errorCode")) intent.getIntExtra("errorCode", -1) else null
                sharedSmsDeliveries.acknowledge(id, part, resultCode, modemCode)
            }
            ACTION_DELIVERY -> if (path[1] == "delivery") {
                val format = intent.getStringExtra("format")?.takeIf { it == "3gpp" || it == "3gpp2" }
                val status = try {
                    val pdu = intent.getByteArrayExtra("pdu")
                    if (pdu == null || format == null) null
                    else SmsMessage.createFromPdu(pdu, format)?.takeIf { it.isStatusReportMessage }?.status
                } catch (_: Exception) {
                    // Never log a report PDU: it contains private addressing information.
                    null
                }
                sharedSmsDeliveries.acknowledgeDelivery(id, part, resultCode, status, format)
            }
        }
    }
    companion object {
        const val ACTION_SENT = "me.rerere.rikkahub.action.SMS_SENT"
        const val ACTION_DELIVERY = "me.rerere.rikkahub.action.SMS_DELIVERY"
    }
}
