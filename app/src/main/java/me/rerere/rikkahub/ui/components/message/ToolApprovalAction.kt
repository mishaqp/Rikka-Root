package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.service.ChatService.ApprovalScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The four choices rendered on Agent's approval card, in the same order. */
internal enum class ToolApprovalAction(val label: String, val approved: Boolean, val scope: ApprovalScope) {
    Once("✓ Один раз", true, ApprovalScope.Once),
    Always("∞ Всегда", true, ApprovalScope.Always),
    ChatScope("Для этого чата", true, ApprovalScope.ChatScope),
    Deny("✗ Отклонить", false, ApprovalScope.Once),
}

/** Debounce until the service job finishes; a failed save leaves the card retryable. */
internal class ToolApprovalSubmission {
    var inFlight by mutableStateOf(false)
        private set

    fun submit(scope: CoroutineScope, action: () -> Job) {
        if (inFlight) return
        inFlight = true
        try {
            val job = action()
            scope.launch {
                try { job.join() } finally { inFlight = false }
            }
        } catch (error: Exception) {
            inFlight = false
            throw error
        }
    }
}
