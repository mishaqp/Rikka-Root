package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LocalBackupExportPart3Test : BackupVmPart3Fixture() {
    private val uri = Uri.parse("content://backup-test/target.zip")
    private fun target(output: OutputStream?) = object : ContextWrapper(context) {
        override fun getContentResolver(): ContentResolver = mockk<ContentResolver>().also { resolver -> every { resolver.openOutputStream(uri) } returns output }
    }
    private fun lastTime() = store.settingsFlow.value.backupReminderConfig.lastBackupTime
    private suspend fun archive() = temporary.newFile().also { file ->
        file.writeBytes("DUMMY BACKUP BYTES".encodeToByteArray()); coEvery { webdav.prepareBackupFile(any()) } returns file
    }
    @Test fun nullOutputFailsWithoutClaimingBackupOrDeletingRecoverySource() = runBlocking {
        val file = archive(); val error = runCatching { vm().exportToDocument(target(null), uri) }.exceptionOrNull()
        assertNotNull("Null stream cannot be a successful export", error)
        assertEquals(1L, lastTime()); assertTrue(file.exists())
    }
    @Test fun closeFailureDoesNotRecordBackupTimeAndPreservesArchive() = runBlocking {
        val file = archive(); var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; throw IOException("DUMMY close failure") } }
        val error = runCatching { vm().exportToDocument(target(output), uri) }.exceptionOrNull()
        assertTrue(closed); assertNotNull(error); assertEquals(1L, lastTime()); assertTrue(file.exists())
    }
    @Test fun copyRunsOffCallerThreadAndOnlyRecordsAfterSuccessfulClose() = runBlocking {
        val file = archive(); val caller = Thread.currentThread(); var copiedOnCaller = false; var timestampAtClose = -1L
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { copiedOnCaller = Thread.currentThread() == caller; super.write(bytes, offset, length) }
            override fun close() { timestampAtClose = lastTime(); super.close() }
        }
        vm().exportToDocument(target(output), uri)
        assertFalse("Archive copying must not block the UI caller", copiedOnCaller)
        assertEquals(1L, timestampAtClose)
        assertArrayEquals("DUMMY BACKUP BYTES".encodeToByteArray(), output.toByteArray()); assertTrue(lastTime() > 1); assertFalse(file.exists())
    }
    @Test fun cancellationDuringCopyPropagatesWithoutRecordingBackupTime() = runBlocking {
        val file = archive(); val cancelled = CancellationException("DUMMY parent cancellation")
        val output = object : OutputStream() { override fun write(value: Int) { throw cancelled }; override fun write(bytes: ByteArray, offset: Int, length: Int) { throw cancelled } }
        val error = runCatching { vm().exportToDocument(target(output), uri) }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(cancelled.message, error?.message); assertEquals(1L, lastTime()); assertTrue(file.exists())
    }
    @Test fun prepareFailurePreservesReminderAndDoesNotTouchTarget() = runBlocking {
        coEvery { webdav.prepareBackupFile(any()) } throws IOException("DUMMY prepare failure")
        val output = ByteArrayOutputStream()
        assertNotNull(runCatching { vm().exportToDocument(target(output), uri) }.exceptionOrNull())
        assertEquals(1L, lastTime()); assertEquals(0, output.size())
    }
}
