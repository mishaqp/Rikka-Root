package me.rerere.rikkahub.root

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootAutomaticToolTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fakeSu(): File {
        val bin = temp.newFolder()
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '0\\n'\n"); setExecutable(true) }
        return temp.newFile().apply {
            writeText("#!/bin/sh\nif [ \"${'$'}2\" = 'id -u' ]; then printf '0\\n'; exit; fi\nPATH='${bin.path}':${'$'}PATH\nexport PATH\nexec /bin/sh -c \"${'$'}2\"\n")
            assertTrue(setExecutable(true))
        }
    }

    @Test fun cancellationWhileDurableBeginReturnsFinishesJournalWithoutLaunching() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutomatic("assistant-a", true)
        val target = File(temp.root, "cancelled-begin-must-not-launch")
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val blockerEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = async(dispatcher) {
            // The blocker runs when beginCommand suspends for real disk IO, before its return.
            executor.submit {
                blockerEntered.countDown()
                release.await()
            }
            tool.execute(buildJsonObject { put("command", "touch '${target.path}'") })
        }
        try {
            assertTrue("disk suspension must release the invocation dispatcher", blockerEntered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) {
                while (RootAccessStore(directory).entries.value.isEmpty()) delay(10)
            }
            job.cancel(CancellationException("stop while begin returns"))
            release.countDown()
            job.join()
            assertEquals("cancelled", RootAccessStore(directory).entries.value.single().status)
            assertFalse("a cancelled invocation cannot launch after journaling", target.exists())
        } finally {
            release.countDown()
            job.cancelAndJoin()
            dispatcher.close()
        }
    }

    @Test fun automaticProvenanceCannotBecomeManualWhenRevokedBeforeToolEntry() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        store.setAutomatic("assistant-a", true)
        val target = File(temp.root, "revoked-command-must-not-run")
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val output = withContext(RootInvocation(automatic = true)) {
            store.setAutomatic("assistant-a", false)
            tool.execute(buildJsonObject { put("command", "touch '${target.path}'") })
        }
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("root_approval_required", result["error"]?.jsonPrimitive?.content)
        assertFalse("revoked Auto must not masquerade as manually Approved", target.exists())
    }

    @Test fun nonzeroExitIsLoggedAsFailedWithoutOutputSecrets() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        store.setAutomatic("assistant-a", true)
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val output = tool.execute(buildJsonObject { put("command", "printf private-failed-output; exit 9") })
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(9, result["exit_code"]!!.jsonPrimitive.int)
        assertEquals("private-failed-output", result["stdout"]!!.jsonPrimitive.content)
        assertEquals("failed", store.entries.value.single().status)
        assertFalse(store.entries.value.single().command.contains("private-failed-output"))
    }

    @Test fun failedFinalJournalWritePreservesActualOutputAndExit() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutomatic("assistant-a", true)
        val started = File(temp.root, "journal-failure-started")
        val finish = File(temp.root, "journal-failure-finish")
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val job = async {
            tool.execute(buildJsonObject {
                put("command", "printf private-completed-output; touch '${started.path}'; while [ ! -f '${finish.path}' ]; do sleep 0.01; done; exit 9")
            })
        }
        try {
            withTimeout(3_000) { while (!started.exists()) delay(10) }
            java.nio.file.Files.move(directory.toPath(), File(temp.root, "old-journal-success").toPath())
            directory.writeText("block new journal saves")
            finish.writeText("finish")
            val result = Json.parseToJsonElement((job.await().single() as UIMessagePart.Text).text).jsonObject
            assertEquals(9, result["exit_code"]!!.jsonPrimitive.int)
            assertEquals("private-completed-output", result["stdout"]!!.jsonPrimitive.content)
            assertEquals("root_journal_save_failed", result["journal_error"]!!.jsonPrimitive.content)
            assertEquals("failed", store.entries.value.single().status)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun failedCancellationJournalWriteDoesNotReplaceCancellation() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutomatic("assistant-a", true)
        val started = File(temp.root, "journal-failure-cancel-started")
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val job = async {
            tool.execute(buildJsonObject { put("command", "touch '${started.path}'; sleep 30") })
        }
        try {
            withTimeout(3_000) { while (!started.exists()) delay(10) }
            java.nio.file.Files.move(directory.toPath(), File(temp.root, "old-journal-cancel").toPath())
            directory.writeText("block new journal saves")
            job.cancel(CancellationException("user stopped"))
            try {
                job.await()
                fail("cancellation must propagate")
            } catch (_: CancellationException) {}
            assertEquals("cancelled", store.entries.value.single().status)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun modeIsLivePerAssistantAndDangerousCallsStillNeedApproval() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        val manager = RootShellManager(suExecutable = "/nonexistent/root-su")
        val tool = buildRootTool(manager, store, "assistant-a")
        val args = buildJsonObject { put("command", "id") }
        assertTrue(tool.needsApproval(args))
        store.setAutomatic("assistant-b", true)
        assertTrue(tool.needsApproval(args))
        store.setAutomatic("assistant-a", true)
        assertFalse(tool.needsApproval(args))
        assertTrue(tool.needsApproval(buildJsonObject { put("command", "rm -rf /system") }))
        assertTrue(tool.needsApproval(JsonNull))
        store.setAutomatic("assistant-a", false)
        assertTrue(tool.needsApproval(args))
    }

    @Test fun automaticUsesVisiblePolicyWhileOrdinaryRetainsGuard() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        val manager = RootShellManager(suExecutable = fakeSu().path)
        val tool = buildRootTool(manager, store, "assistant-a")
        val args = buildJsonObject { put("command", "echo 'rm -rf /system'") }
        val blocked = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("root_command_blocked", blocked["error"]!!.jsonPrimitive.content)
        assertTrue(store.entries.value.isEmpty())
        store.setAutomatic("assistant-a", true)
        assertFalse(tool.needsApproval(args))
        val executed = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(executed["success"]!!.jsonPrimitive.boolean)
        assertEquals("completed", RootAccessStore(directory).entries.value.single().status)
    }

    @Test fun cancellationDurablyFinishesRedactedJournal() = runBlocking {
        val directory = temp.newFolder()
        val store = RootAccessStore(directory)
        store.setAutomatic("assistant-a", true)
        val tool = buildRootTool(RootShellManager(suExecutable = fakeSu().path), store, "assistant-a")
        val started = File(temp.root, "command-started")
        val args = buildJsonObject { put("command", "printf private-output; touch '${started.path}'; sleep 30") }
        val job = async { tool.execute(args) }
        try {
            withTimeout(3_000) { while (!started.exists()) delay(10) }
            assertFalse("a launched call must already have a journal entry", store.entries.value.isEmpty())
        } finally {
            job.cancelAndJoin()
        }
        val entry = RootAccessStore(directory).entries.value.single()
        assertEquals("cancelled", entry.status)
        assertFalse(entry.command.contains("private-output"))
    }
}
