package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolExecutionAbortedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager

class DeviceToolFailureReasonTest {
    @Test fun commandExitFailuresExplainRecoveryWithoutTreatingSuccessOrUnknownExitAsFailure() {
        assertEquals(null, commandExitFailureReason(null))
        assertEquals(null, commandExitFailureReason(0))
        listOf(1, 23, -1).forEach { code ->
            assertEquals("Команда завершилась с ошибкой (код $code). Проверьте вывод и результат перед новым запуском.",
                commandExitFailureReason(code))
        }
    }

    @Test fun missingProgramWithoutItsNameStillExplainsExit127() {
        val reason = commandExitFailureReason(127).orEmpty()
        assertTrue(reason, reason.contains("Программа"))
        assertTrue(reason, reason.contains("Установите"))
    }

    @Test fun rootMissingProgramKeepsStreamsAndExplainsAndroidEnvironment() = runBlocking {
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), any(), any(), any()) } returns RootProcessResult(
            exitCode = 127, stdout = "partial output", stderr = "/system/bin/sh: gh: inaccessible or not found\n")
        val tool = buildRootTool(manager)
        val parts = tool.execute(buildJsonObject { put("command", "gh --version") })
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        val reason = result["reason"]?.jsonPrimitive?.content.orEmpty()
        assertEquals("127", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("partial output", result["stdout"]?.jsonPrimitive?.content)
        assertEquals("/system/bin/sh: gh: inaccessible or not found\n", result["stderr"]?.jsonPrimitive?.content)
        assertTrue(reason, reason.contains("Программа gh"))
        assertTrue(reason, reason.contains("Android"))
        assertFalse(reason, reason.contains("apt install"))
        assertFalse(reason, reason.contains("pkg install"))
        assertTrue(tool.description.contains("updateJson"))
        assertTrue(tool.description.contains("api.github.com"))
    }

    @Test fun appAuthoredRussianReasonsAreShownAsIs() {
        val reason = "Нет записи в выбранной папке. Сначала вызовите grant_directory_access."
        assertEquals(reason, deviceToolFailureReason(IllegalArgumentException(reason)))
        assertEquals("Назначение уже существует. Укажите overwrite=true.",
            deviceToolFailureReason(IllegalStateException("Назначение уже существует. Укажите overwrite=true.")))
    }

    @Test fun platformExceptionTextIsNeverExposedOnlyItsKind() {
        val private = "/storage/emulated/0/Секретная папка/паспорт.jpg: open failed: EACCES (Permission denied)"
        assertEquals("Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку.",
            deviceToolFailureReason(FileNotFoundException("/data/user/0/secret.txt: open failed: EACCES (Permission denied)")))
        assertEquals("Файл или папка не найдены. Проверьте путь.", deviceToolFailureReason(FileNotFoundException("/sdcard/private.txt")))
        assertEquals("Ошибка чтения или записи. Проверьте доступ и свободное место.", deviceToolFailureReason(IOException("token=abc123 broken pipe")))
        assertEquals("Функция не поддерживается на этом устройстве. Выберите другой способ.", deviceToolFailureReason(UnsupportedOperationException("x")))
        assertEquals("Функция недоступна на устройстве или Android отклонил запрос. Проверьте настройки функции.",
            deviceToolFailureReason(IllegalStateException("Camera service error: user@example.com")))
        // A long or multi-line message is not treated as an app-authored reason.
        assertEquals("Функция недоступна на устройстве или Android отклонил запрос. Проверьте настройки функции.",
            deviceToolFailureReason(IllegalStateException("Ошибка\n$private")))
    }

    @Test fun russianPrivatePathsNeverMakeFileExceptionsAppAuthoredReasons() {
        val path = "/storage/emulated/0/Секретная папка/паспорт.jpg"
        assertEquals("Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку.",
            deviceToolFailureReason(FileNotFoundException("$path: open failed: EACCES (Permission denied)")))
        assertEquals("Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку.",
            deviceToolFailureReason(AccessDeniedException(path)))
        assertEquals("Файл или папка не найдены. Проверьте путь.",
            deviceToolFailureReason(NoSuchFileException(path)))
        assertEquals("Файл уже существует. Укажите другое имя или разрешите перезапись.",
            deviceToolFailureReason(FileAlreadyExistsException(path)))
    }

    @Test fun cyrillicInPlatformDetailsIsNotAnAuthoredRussianExplanation() {
        val private = "/storage/emulated/0/Секретная папка/паспорт.jpg"
        assertEquals("Ошибка чтения или записи. Проверьте доступ и свободное место.",
            deviceToolFailureReason(IOException("open failed: $private token=private")))
        assertEquals("Функция недоступна на устройстве или Android отклонил запрос. Проверьте настройки функции.",
            deviceToolFailureReason(IllegalStateException("Camera service error: $private")))
        assertEquals("Некорректные параметры. Проверьте названия, типы и значения параметров.",
            deviceToolFailureReason(IllegalArgumentException("$private: invalid input token=private")))
    }

    @Test fun appValidationAndRecoveryReasonsKeepTheirUsefulDetails() {
        val parameterReason = "max_bytes вне допустимого диапазона."
        assertEquals(parameterReason, deviceToolFailureReason(IllegalArgumentException(parameterReason)))
        val recovery = FileRestoreFailedException(java.io.File("/scratch/backup-17.bin"), IOException("private token"))
        assertEquals("Не удалось восстановить файл. Резервная копия сохранена: /scratch/backup-17.bin",
            deviceToolFailureReason(recovery))
        assertEquals("Копия сохранена, но источник не удалось полностью удалить.",
            deviceToolFailureReason(FileMoveSourceDeleteException(IOException("private token"))))
        assertEquals("Не удалось убрать созданный документ после ошибки доступа.",
            deviceToolFailureReason(FileCreationCleanupFailedException(
                LocalFileSource.Local(java.io.File("/scratch/created")), IOException("private token"))))
        assertEquals("Сервер отклонил загрузку (HTTP 429).",
            deviceToolFailureReason(IOException("Сервер отклонил загрузку (HTTP 429).")))
        assertEquals("Файл или папка не найдены в выбранной папке SAF.",
            deviceToolFailureReason(IllegalArgumentException("Файл или папка не найдены в выбранной папке SAF.")))
    }

    @Test fun networkFailuresExplainWhatToCheckWithoutHostOrCertificateDetails() {
        assertEquals("Сервер не найден. Проверьте адрес и подключение к интернету.",
            deviceToolFailureReason(java.net.UnknownHostException("секретный.рф")))
        assertEquals("Не удалось подключиться к серверу. Проверьте адрес и подключение к интернету.",
            deviceToolFailureReason(java.net.ConnectException("Failed to connect to секретный.рф")))
        assertEquals("Сервер не ответил вовремя. Повторите попытку.",
            deviceToolFailureReason(java.net.SocketTimeoutException("секретный.рф timed out")))
        assertEquals("Не удалось установить защищённое соединение. Проверьте дату и время устройства и сертификат сервера.",
            deviceToolFailureReason(javax.net.ssl.SSLException("Certificate: Секретный сервер")))
    }

    @Test fun malformedArgumentsHaveActionableRussianReasonWithoutInput() {
        assertEquals("Некорректные параметры. Проверьте названия, типы и значения параметров.",
            deviceToolFailureReason(IllegalArgumentException("Invalid value: /private/Секрет.txt")))
        assertEquals("Некорректный JSON. Проверьте формат и типы значений.",
            deviceToolFailureReason(kotlinx.serialization.SerializationException("Unexpected token at /private/Секрет.txt")))
        assertEquals("Некорректный адрес. Проверьте формат URI.",
            deviceToolFailureReason(java.net.URISyntaxException("/private/Секрет.txt", "Expected scheme")))
    }

    @Test fun cryptoAndSecurityFailuresNeverExposePlatformMessages() {
        assertEquals("Не удалось расшифровать данные. Проверьте ключ, входные данные и параметры шифрования.",
            deviceToolFailureReason(javax.crypto.BadPaddingException("Секретный ключ: bad tag")))
        assertEquals("Ключ не подходит для этой операции. Проверьте ключ и его разрешённые назначения.",
            deviceToolFailureReason(java.security.InvalidKeyException("Секретный ключ: unsupported")))
        assertEquals("Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента.",
            deviceToolFailureReason(SecurityException("Permission denied: /private/Секрет.txt")))
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
        assertFileParentCancellation(wrapped = false)
    }

    private suspend fun assertFileParentCancellation(wrapped: Boolean) = coroutineScope {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        var returned = false
        val tool = fileJsonTool("copy_file", "test", fileSchema()) {
            try { started.complete(Unit); awaitCancellation() }
            catch (cancelled: CancellationException) {
                if (wrapped) throw SafFileOperationException(cancelled) else throw cancelled
            } finally { cleaned = true }
        }
        val job = launch { tool.execute(buildJsonObject {}); returned = true }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
        assertFalse(returned)
    }

    @Test fun wrappedSafCancellationStillPropagatesAsCancellation() = runBlocking {
        assertFileParentCancellation(wrapped = true)
    }

    private class PolicyAbortFixture(val outcome: String, val code: String, val payload: Any) :
        ToolExecutionAbortedException(code)

    @Test fun policyAbortIsNotConvertedToDeviceErrorEvenWhenSafWrapped() = runBlocking {
        for (wrapped in listOf(false, true)) {
            val payload = Any()
            val aborted = PolicyAbortFixture("blocked", "policy_denied", payload)
            val failure = if (wrapped) SafFileOperationException(aborted) else aborted
            val actual = runCatching { fileFailure("copy_file", failure) }.exceptionOrNull()
            assertTrue("The exact intentional policy signal must leave the device boundary", actual === aborted)
            val signal = actual as PolicyAbortFixture
            assertEquals("blocked", signal.outcome)
            assertEquals("policy_denied", signal.code)
            assertTrue("The policy result payload must be preserved", signal.payload === payload)
            assertTrue(currentCoroutineContext().isActive)
        }
    }
}
