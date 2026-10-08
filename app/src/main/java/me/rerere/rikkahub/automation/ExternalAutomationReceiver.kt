package me.rerere.rikkahub.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Manifest-registered receiver for RUN_TASK callers that share their OS identity.
 *
 * Why a separate receiver in addition to the activity: Android 14+ blocks a manifest
 * broadcast receiver from launching an activity in the background (target SDK 34+ enforces
 * this). The activity path is preferred for app-callers; the receiver path is preserved
 * only when Android 14+ provides both sentFromPackage and sentFromUid. Below Android 14,
 * or if the sender did not share its identity, the call is unverified and rejected.
 * A label supplied by Intent extras or a synthetic "<adb>" identity cannot establish trust.
 */
class ExternalAutomationReceiver : BroadcastReceiver(), KoinComponent {

    private val dispatcher: ExternalAutomationDispatcher by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val callerPackage = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ExternalAutomationDispatcher.verifiedBroadcastCaller(sentFromPackage, sentFromUid,
                android.os.Build.VERSION.SDK_INT)
        } else null
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handle(intent, callerPackage)
            } catch (t: Throwable) {
                Log.w(TAG, "receiver dispatch failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent, callerPackage: String?) {
        val action = intent.action
        val requestId = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_REQUEST_ID)
        val returnAction = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_ACTION)
        val returnPackage = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_PACKAGE)
        val callerLabel = callerPackage.orEmpty()

        when (dispatcher.classifyCaller(callerPackage)) {
            is ExternalAutomationDispatcher.TrustResult.Disabled -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "feature_disabled")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.PendingUserApproval -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "untrusted_caller")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.Trusted -> { /* proceed */ }
        }

        when (action) {
            ExternalAutomationDispatcher.ACTION_RUN_TASK -> {
                val prompt = ExternalAutomationDispatcher.extractPrompt(
                    intent,
                    ExternalAutomationDispatcher.EXTRA_TASK,
                    ExternalAutomationDispatcher.EXTRA_TASK_B64,
                )
                if (prompt.isNullOrBlank()) {
                    dispatcher.rejectAndCallback(callerLabel, action, requestId, returnAction, returnPackage, "missing_prompt")
                    return
                }
                dispatcher.dispatchTask(prompt, callerLabel, requestId, returnAction, returnPackage)
            }
            else -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "unsupported_action_for_broadcast")
            }
        }
    }

    companion object {
        private const val TAG = "ExtAutomationRecv"
    }
}
