package me.rerere.rikkahub.data.datastore

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class SettingsCorruptionPreservationPart3Test {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedBackupMustNotAuthorizeReplacingRecoverableOriginalWithEmptySettings() {
        val original = temporary.newFile("settings.preferences_pb")
        val bytes = byteArrayOf(0xff.toByte(), 0x7f) + "recoverable settings payload".toByteArray()
        original.writeBytes(bytes)
        var attempts = 0
        val result = runCatching { recoverCorruptSettings(original) { source ->
            attempts++
            assertEquals(original, source)
            throw IOException("Адресный отказ резервной копии")
        } }
        assertEquals(1, attempts)
        assertTrue("Обработчик не должен разрешать DataStore записать пустую замену после отказа копирования", result.isFailure)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun backupCancellationIsNotConvertedToSuccessfulReset() {
        val original = temporary.newFile("settings.preferences_pb").apply { writeText("исходные байты") }
        val cancelled = kotlinx.coroutines.CancellationException("отмена резервной копии")
        val result = runCatching { recoverCorruptSettings(original) { throw cancelled } }
        assertSame(cancelled, result.exceptionOrNull())
        assertEquals("исходные байты", original.readText())
    }

    @Test fun successfulBackupPermitsEmptyResetAndPreservesEveryOriginalByte() {
        val original = temporary.newFile("settings.preferences_pb")
        val bytes = byteArrayOf(0xff.toByte(), 0x7f) + "recoverable settings payload".toByteArray()
        original.writeBytes(bytes)
        val backup = temporary.root.resolve("settings.corrupt")
        var attempts = 0
        val replacement = recoverCorruptSettings(original) { source ->
            attempts++
            source.copyTo(backup)
        }
        assertEquals(1, attempts)
        assertEquals(emptyPreferences(), replacement)
        assertArrayEquals(bytes, backup.readBytes())
        assertArrayEquals(bytes, original.readBytes())
    }
}
