package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class DeviceToolFailureReasonTest {
    @Test fun appAuthoredRussianReasonsAreShownAsIs() {
        val reason = "Нет записи в выбранной папке. Сначала вызовите grant_directory_access."
        assertEquals(reason, deviceToolFailureReason(IllegalArgumentException(reason)))
        assertEquals("Назначение уже существует. Укажите overwrite=true.",
            deviceToolFailureReason(IllegalStateException("Назначение уже существует. Укажите overwrite=true.")))
    }

    @Test fun platformExceptionTextIsNeverExposedOnlyItsKind() {
        val private = "/storage/emulated/0/Секретная папка/паспорт.jpg: open failed: EACCES (Permission denied)"
        assertEquals("Нет доступа к файлу или папке.",
            deviceToolFailureReason(FileNotFoundException("/data/user/0/secret.txt: open failed: EACCES (Permission denied)")))
        assertEquals("Файл или папка не найдены.", deviceToolFailureReason(FileNotFoundException("/sdcard/private.txt")))
        assertEquals("Ошибка чтения или записи.", deviceToolFailureReason(IOException("token=abc123 broken pipe")))
        assertEquals("Функция не поддерживается на этом устройстве.", deviceToolFailureReason(UnsupportedOperationException("x")))
        assertEquals("Функция недоступна на устройстве или Android отклонил запрос.",
            deviceToolFailureReason(IllegalStateException("Camera service error: user@example.com")))
        // A long or multi-line message is not treated as an app-authored reason.
        assertEquals("Функция недоступна на устройстве или Android отклонил запрос.",
            deviceToolFailureReason(IllegalStateException("Ошибка\n$private")))
    }

    @Test fun toolsReturnTheReasonAsAnErrorResult() = runBlocking {
        val parts = deviceToolParts { throw IllegalArgumentException("Путь вне scratch, upload или выбранного workspace.") }
        val text = (parts.single() as UIMessagePart.Text).text
        assertEquals("""{"error":"Путь вне scratch, upload или выбранного workspace."}""", text)
        assertFalse(text.contains("Exception"))
    }

    private suspend fun fileFailure(name: String, failure: Exception): String =
        (fileJsonTool(name, "test", fileSchema()) { throw failure }
            .execute(buildJsonObject {}).single() as UIMessagePart.Text).text

    @Test fun safCopySecurityFailureNamesOperationWithoutProviderText() = runBlocking {
        val result = fileFailure("copy_file", SafFileOperationException(SecurityException(
            "/sdcard/Download/Секрет.txt denied token=private")))
        assertEquals("""{"error":"Android не разрешил создать копию в выбранной папке."}""", result)
        assertFalse(result.contains("Секрет"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("Exception"))
    }

    @Test fun safMoveUnsupportedFailureNamesOperationAndCauseKind() = runBlocking {
        assertEquals("""{"error":"Перемещение файла в выбранной папке: Android не поддерживает эту операцию."}""",
            fileFailure("move_file", SafFileOperationException(UnsupportedOperationException("/private/path"))))
    }

    @Test fun safWriteIoFailureDoesNotExposeEvenShortRussianProviderMessage() = runBlocking {
        assertEquals("""{"error":"Запись файла в выбранной папке: ошибка чтения или записи."}""",
            fileFailure("write_text_file", SafFileOperationException(IOException("Не открыть /sdcard/Секрет.txt"))))
    }

    @Test fun safReadNotFoundFailureNamesTheReason() = runBlocking {
        assertEquals("""{"error":"Чтение файла в выбранной папке: файл или папка не найдены."}""",
            fileFailure("read_file", SafFileOperationException(FileNotFoundException("/private/not-found"))))
    }

    @Test fun safUnknownCopyFailureUsesOperationInsteadOfGenericDeviceFallback() = runBlocking {
        assertEquals("""{"error":"Копирование файла в выбранной папке: Android отклонил запрос; тип ошибки указан в отчёте."}""",
            fileFailure("copy_file", SafFileOperationException(IllegalStateException("short private text"))))
    }

    @Test fun nonSafSecurityReasonAndCancellationRemainUnchanged() = runBlocking {
        assertEquals("""{"error":"Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента."}""",
            fileFailure("copy_file", SecurityException("private")))
        val cancelled = CancellationException("private cancellation")
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { fileFailure("copy_file", cancelled) }
        }
        assertTrue(actual === cancelled || actual.cause === cancelled)
    }

    @Test fun wrappedSafCancellationStillPropagatesAsCancellation() = runBlocking {
        val cancelled = CancellationException("private cancellation")
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { fileFailure("copy_file", SafFileOperationException(cancelled)) }
        }
        assertTrue(actual === cancelled || actual.cause === cancelled)
    }
}
