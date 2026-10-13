package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
