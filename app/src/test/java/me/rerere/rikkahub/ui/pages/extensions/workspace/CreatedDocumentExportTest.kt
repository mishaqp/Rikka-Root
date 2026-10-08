package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CreatedDocumentExportTest {
    @Test fun cancelledCopyClosesStreamAndRemovesOnlyCreatedDestination() = runBlocking {
        var closed=false; val deleted=mutableListOf<String>(); val entered=CompletableDeferred<Unit>()
        val job=launch {
            exportCreatedDocument(create={ "created" }, open={ object: ByteArrayOutputStream() { override fun close() { closed=true; super.close() } } },
                write={ it.write(7); entered.complete(Unit); awaitCancellation() }, delete={ deleted.add(it) })
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(closed); assertEquals(listOf("created"),deleted)
    }
    @Test fun cancellationBeforeCreationNeverCreatesEmptyDocument() = runBlocking {
        var creates=0
        val job=launch(start=CoroutineStart.LAZY) { exportCreatedDocument(create={ creates++; "created" },open={ ByteArrayOutputStream() },write={ },delete={ }) }
        job.cancel(); job.join(); assertEquals(0,creates)
    }
    @Test fun failedOpenDeletesNewDocumentAndSuccessfulWriteKeepsIt() = runBlocking {
        val deleted=mutableListOf<String>()
        try { exportCreatedDocument(create={ "failed" },open={ null },write={ },delete={ deleted.add(it) }); fail() } catch (_: IllegalStateException) { }
        var closed=false
        exportCreatedDocument(create={ "kept" },open={ object:ByteArrayOutputStream() { override fun close() { closed=true } } },write={ it.write(1) },delete={ deleted.add(it) })
        assertEquals(listOf("failed"),deleted); assertTrue(closed)
    }
    @Test fun cancellationImmediatelyAfterCreationRemovesDocumentBeforeOpening() = runBlocking {
        var opened=false; val deleted=mutableListOf<String>()
        val job=launch {
            val caller=currentCoroutineContext()
            exportCreatedDocument(create={ caller.cancel(); "created" },open={ opened=true; ByteArrayOutputStream() },write={ },delete={ deleted.add(it) })
        }
        job.join(); assertFalse(opened); assertEquals(listOf("created"),deleted)
    }
    @Test fun cancellationDuringCreationDoesNotLoseOwnershipOfTheReturnedDocument() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val finish=CompletableDeferred<Unit>(); val deleted=mutableListOf<String>()
        val job=launch {
            exportCreatedDocument(create={ entered.complete(Unit); finish.await(); "created" },open={ ByteArrayOutputStream() },write={ },delete={ deleted.add(it) })
        }
        entered.await(); job.cancel(); finish.complete(Unit); job.join()
        assertEquals(listOf("created"),deleted)
    }
}
