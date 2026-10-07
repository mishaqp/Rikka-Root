package me.rerere.rikkahub.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class RootExecutionJournalTest {
    private fun approved() = UIMessagePart.Tool("call-root", "root_exec", "{\"command\":\"id\"}", approvalState = ToolApprovalState.Approved)

    @Test fun `fresh automatic root call checkpoints and executes when live policy allows`() = runBlocking {
        val events = mutableListOf<String>()
        var checkpoint: UIMessagePart.Tool? = null
        val automatic = approved().copy(approvalState = ToolApprovalState.Auto)
        val result = executeRootToolOnce(automatic, {
            checkpoint = it
            events += "persist"
        }, automaticAllowed = { true }) {
            events += "execute"
            listOf(UIMessagePart.Text("automatic result"))
        }
        assertEquals(listOf("persist", "execute"), events)
        assertEquals("automatic result", (result.single() as UIMessagePart.Text).text)
        assertTrue(checkpoint!!.isExecuted)
        assertFalse(canResumeRootTool(checkpoint!!))
        assertEquals(ToolApprovalState.Auto, checkpoint!!.approvalState)
    }

    @Test fun `automatic permission revoked during durable write prevents launch`() = runBlocking {
        var allowed = true
        var executed = false
        var checkpoint: UIMessagePart.Tool? = null
        try {
            executeRootToolOnce(approved().copy(approvalState = ToolApprovalState.Auto), {
                checkpoint = it
                allowed = false
            }, automaticAllowed = { allowed }) {
                executed = true
                emptyList()
            }
            fail("revoked automatic permission must prevent launch")
        } catch (_: IllegalStateException) { }
        assertNotNull("revocation happened after checkpoint persistence", checkpoint)
        assertTrue(checkpoint!!.isExecuted)
        assertFalse(executed)
    }

    @Test fun `mixed approval batch retains fresh automatic root siblings only`() {
        val automatic = approved().copy(approvalState = ToolApprovalState.Auto)
        val manual = approved()
        val batch = listOf(automatic, manual)
        val resumable = batch.filter { it.canResumeExecution || canResumeRootTool(it) }
        assertEquals(batch, resumable)
        assertFalse(canResumeRootTool(automatic.copy(toolName = "workspace_shell")))
        assertFalse(canResumeRootTool(automatic.copy(approvalState = ToolApprovalState.Pending)))
        assertFalse(canResumeRootTool(automatic.copy(output = listOf(UIMessagePart.Text("checkpoint")))))
    }

    @Test fun `persists a nonresumable marker before launching the approved command`() = runBlocking {
        val events = mutableListOf<String>()
        var checkpoint: UIMessagePart.Tool? = null
        val output = executeRootToolOnce(approved(), { marked ->
            checkpoint = marked
            events += "persist"
        }) {
            assertEquals(listOf("persist"), events)
            events += "execute"
            listOf(UIMessagePart.Text("completed"))
        }
        assertEquals(listOf("persist", "execute"), events)
        assertEquals(listOf(UIMessagePart.Text("completed")), output)
        assertFalse(checkpoint!!.canResumeExecution)
        assertTrue((checkpoint!!.output.single() as UIMessagePart.Text).text.contains("root_execution_indeterminate"))
        assertFalse(approved().copy(output = output).canResumeExecution)
    }

    @Test fun `interrupted automatic and approved root commands never replay`() = runBlocking {
        for (state in listOf(ToolApprovalState.Auto, ToolApprovalState.Approved)) {
            var checkpoint: UIMessagePart.Tool? = null
            var launches = 0
            try {
                executeRootToolOnce(approved().copy(approvalState = state), { checkpoint = it },
                    automaticAllowed = { true }) { launches++; throw CancellationException("process loss") }
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertNotNull(checkpoint)
            assertTrue(checkpoint!!.isExecuted)
            assertFalse(checkpoint!!.canResumeExecution)
            assertFalse(canResumeRootTool(checkpoint!!))
            try {
                executeRootToolOnce(checkpoint!!, {}, automaticAllowed = { true }) {
                    launches++; emptyList()
                }
                fail("previously started call must never replay")
            } catch (_: IllegalStateException) { }
            assertEquals(1, launches)
        }
    }

    @Test fun `failed durable checkpoint prevents launch`() = runBlocking {
        var executed = false
        try {
            executeRootToolOnce(approved(), { error("disk full") }) { executed = true; emptyList() }
            fail("persistence failure must propagate")
        } catch (error: IllegalStateException) { assertEquals("disk full", error.message) }
        assertFalse(executed)
    }

    @Test fun `only a fresh approved unexecuted root call can launch`() = runBlocking {
        val calls = listOf(approved().copy(approvalState = ToolApprovalState.Auto), approved().copy(output = listOf(UIMessagePart.Text("indeterminate"))))
        calls.forEach { call ->
            var executed = false
            try {
                executeRootToolOnce(call, {}) { executed = true; emptyList() }
                fail("must reject an unapproved or previously started call")
            } catch (_: IllegalStateException) {}
            assertFalse(executed)
        }
    }

    @Test fun `buffered older messages precede the durable marker and cannot overwrite it on cancellation`() = runBlocking {
        val chunks = Channel<Pair<UIMessagePart.Tool, CompletableDeferred<Unit>?>>(2)
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var stored = approved()
        var executed = false
        val producer = async {
            chunks.send(approved() to null)
            executeRootToolOnce(approved(), { marked ->
                awaitRootCheckpoint { ack -> chunks.send(marked to ack) }
            }) {
                executed = true
                awaitCancellation()
            }
        }
        yield() // Both older approved messages and the root checkpoint are buffered.
        val collector = async {
            stored = chunks.receive().first
            val (marked, ack) = chunks.receive()
            persistRootCheckpoint(ack!!) {
                writeStarted.complete(Unit)
                finishWrite.await()
                stored = marked
            }
        }
        writeStarted.await()
        assertFalse("a buffered checkpoint is not a completed durable write", executed)
        assertTrue(stored.canResumeExecution) // No command has launched yet.
        finishWrite.complete(Unit)
        collector.await()
        yield()
        assertTrue(executed)
        producer.cancelAndJoin()
        assertFalse("completion/cancellation saves the already marked snapshot", stored.canResumeExecution)
        assertTrue(stored.isExecuted)
    }

    @Test fun `cancellation before checkpoint acknowledgment cannot launch a root command`() = runBlocking {
        val checkpoint = CompletableDeferred<CompletableDeferred<Unit>>()
        var executed = false
        val producer = async {
            executeRootToolOnce(approved(), {
                awaitRootCheckpoint { ack -> checkpoint.complete(ack) }
            }) { executed = true; emptyList() }
        }
        val ack = checkpoint.await()
        producer.cancelAndJoin()
        ack.complete(Unit) // A late database acknowledgment cannot revive a cancelled launch.
        assertFalse(executed)
    }

    @Test fun `collector persistence failure rejects its acknowledgment and prevents launch`() = runBlocking {
        val checkpoint = CompletableDeferred<CompletableDeferred<Unit>>()
        var executed = false
        val producer = async {
            try {
                executeRootToolOnce(approved(), {
                    awaitRootCheckpoint { ack -> checkpoint.complete(ack) }
                }) { executed = true; emptyList() }
                false
            } catch (error: IllegalStateException) { error.message == "disk full" }
        }
        try { persistRootCheckpoint(checkpoint.await()) { error("disk full") } } catch (_: IllegalStateException) {}
        assertTrue(producer.await())
        assertFalse(executed)
    }
}
