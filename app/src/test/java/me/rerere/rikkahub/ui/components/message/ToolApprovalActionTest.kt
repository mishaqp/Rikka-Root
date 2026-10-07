package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.service.ChatService.ApprovalScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ToolApprovalActionTest {
    @Test fun fourButtonsDispatchDistinctApprovalChoicesInAgentOrder() {
        val buttons = ToolApprovalAction.entries
        assertEquals(listOf("✓ Один раз", "∞ Всегда", "Для этого чата", "✗ Отклонить"), buttons.map { it.label })
        assertEquals(listOf(
            true to ApprovalScope.Once,
            true to ApprovalScope.Always,
            true to ApprovalScope.ChatScope,
            false to ApprovalScope.Once,
        ), buttons.map { it.approved to it.scope })
    }

    @Test fun buttonsDebounceDoubleTapAndAllowRetryAfterSaveFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val submission = ToolApprovalSubmission()
        val first = CompletableDeferred<Unit>()
        var clicks = 0
        try {
            submission.submit(scope) { clicks++; first }
            submission.submit(scope) { clicks++; error("Double tap must not submit another approval") }
            assertEquals(1, clicks)
            assertTrue(submission.inFlight)
            first.completeExceptionally(IOException("Save failed"))
            assertFalse(submission.inFlight)
            submission.submit(scope) { clicks++; CompletableDeferred(Unit) }
            assertEquals(2, clicks)
            assertFalse(submission.inFlight)
        } finally { scope.cancel() }
    }
}
