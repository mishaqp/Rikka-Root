// Sent-receipt handling for the adapted Agent SMS tool (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.data.ai.tools.local.sharedSmsDeliveries

/** Explicit immutable PendingIntents only; the manifest keeps this receiver non-exported. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        sharedSmsDeliveries.acknowledge(id, intent.getIntExtra(EXTRA_PART, -1), resultCode)
    }
    companion object {
        const val EXTRA_REQUEST_ID = "sms_request_id"
        const val EXTRA_PART = "sms_part"
    }
}
