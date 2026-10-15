package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.*
import org.junit.Test

class DeviceProducerErrorsTest {
    private val context = mockk<Context>(relaxed = true)
    private suspend fun result(tool: Tool, input: String): JsonObject = Json.parseToJsonElement(
        (tool.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text
    ).jsonObject

    @Test fun clipboardValidationExplainsTheRequiredActionWithoutEchoingUnknownInput() = runBlocking {
        val tool = buildClipboardTool(context)
        assertTrue(result(tool, "{}").getValue("error").jsonPrimitive.content.contains("action"))
        assertTrue(result(tool, """{"action":"write"}""").getValue("error").jsonPrimitive.content.contains("text"))
        val unknown = result(tool, """{"action":"private-token"}""").getValue("error").jsonPrimitive.content
        assertTrue(unknown.contains("Укажите"))
        assertTrue(unknown.contains("read") && unknown.contains("write"))
        assertFalse(unknown.contains("private-token"))
    }

    @Test fun memoryValidationReturnsRussianErrorsBeforeCallingTheRepository() = runBlocking {
        val tool = buildMemoryTools(Json, { error("Repository must not be called") },
            { _, _ -> error("Repository must not be called") }, { error("Repository must not be called") }).single()
        listOf("{}", """{"action":"create"}""", """{"action":"edit","content":"text"}""",
            """{"action":"delete"}""", """{"action":"private-token"}""").forEach { input ->
            val error = result(tool, input).getValue("error").jsonPrimitive.content
            assertTrue(error, error.contains("Укажите"))
            assertFalse(error.contains("private-token"))
        }
    }

    @Test fun memoryRepositoryFailureDoesNotExposeNativeExceptionTextAndCancellationPropagates() = runBlocking {
        val private = "database failed: /data/Секрет.db token=private"
        val tool = buildMemoryTools(Json, { throw java.io.IOException(private) },
            { id, content -> AssistantMemory(id, content) }, {}).single()
        val error = result(tool, """{"action":"create","content":"text"}""").getValue("error").jsonPrimitive.content
        assertEquals("Ошибка чтения или записи. Проверьте доступ и свободное место.", error)
        assertFalse(error.contains("Секрет") || error.contains("token="))
        val cancelled = CancellationException("private")
        val cancellable = buildMemoryTools(Json, { throw cancelled },
            { id, content -> AssistantMemory(id, content) }, {}).single()
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { result(cancellable, """{"action":"create","content":"text"}""") }
        }
        assertTrue(actual === cancelled || actual.cause === cancelled)
    }

    @Test fun ttsMissingTextReturnsAnActionableRussianResult() = runBlocking {
        val tool = buildTextToSpeechTool(AppEventBus(), mockk(), mockk())
        val error = result(tool, "{}").getValue("error").jsonPrimitive.content
        assertTrue(error.contains("Укажите") && error.contains("text"))
    }

    @Test fun calendarKeepsErrorCodesAndReplacesRawDateInputWithFormatGuidance() = runBlocking {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        val query = buildCalendarQueryTool(context)
        val invalidQuery = result(query, """{"begin":"private-token"}""")
        assertEquals("INVALID_TIME", invalidQuery.getValue("error").jsonPrimitive.content)
        val message = invalidQuery.getValue("message").jsonPrimitive.content
        assertTrue(message.contains("begin") && message.contains("ISO-8601"))
        assertFalse(message.contains("private-token"))
        val create = buildCalendarCreateTool(context)
        val invalidCreate = result(create, """{"title":"title","start":"private-token"}""")
        assertEquals("INVALID_TIME", invalidCreate.getValue("error").jsonPrimitive.content)
        assertTrue(invalidCreate.getValue("message").jsonPrimitive.content.contains("start"))
        assertFalse(invalidCreate.toString().contains("private-token"))
        val missing = result(create, "{}")
        assertEquals("MISSING_REQUIRED", missing.getValue("error").jsonPrimitive.content)
        assertTrue(missing.getValue("message").jsonPrimitive.content.contains("Укажите"))
    }

    @Test fun nativeAppProviderArgumentFailureNeverReturnsTheRawExceptionMessage() = runBlocking {
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        @Suppress("DEPRECATION")
        every { pm.getPackageInfo("org.example", PackageManager.GET_ACTIVITIES) } throws
            IllegalArgumentException("package failed: /data/Секрет token=private")
        val error = result(appLauncherTools(context).single { it.name == "list_app_activities" },
            """{"package_name":"org.example"}""").getValue("error").jsonPrimitive.content
        assertFalse(error.contains("token=") || error.contains("Секрет"))
        assertTrue(error.any { it in 'А'..'я' })
    }

    @Test fun personalValidationUsesSafeFailureReasonsAndKeepsAuthoredParameterGuidance() = runBlocking {
        val unsafe = personalJsonTool(context, "test", "test", LocalToolOption.Contacts,
            me.rerere.ai.core.InputSchema.Obj(buildJsonObject {}), validate = {
                throw IllegalArgumentException("validation failed: /data/Секрет token=private")
            }, read = { buildJsonObject {} })
        val error = result(unsafe, "{}").getValue("error").jsonPrimitive.content
        assertFalse(error.contains("token=") || error.contains("Секрет"))
        val authored = personalJsonTool(context, "test", "test", LocalToolOption.Contacts,
            me.rerere.ai.core.InputSchema.Obj(buildJsonObject {}), validate = {
                throw IllegalArgumentException("Укажите limit от 1 до 200.")
            }, read = { buildJsonObject {} })
        assertEquals("Укажите limit от 1 до 200.", result(authored, "{}").getValue("error").jsonPrimitive.content)
    }

    @Test fun mediaValidationNamesTheParameterAndTheRecoveryAction() = runBlocking {
        val error = result(seekMediaTool(context), "{}").getValue("error").jsonPrimitive.content
        assertTrue(error.contains("Укажите") && error.contains("position_ms"))
    }

    @Test fun smsPartialFailureExplainsRadioStateAndKeepsEveryReceiptField() {
        val json = smsOutcomeJson(SmsDeliveryResult(1, 1, mapOf(1 to 2), mapOf(1 to 41)), 3)
        assertEquals("android_error", json.getValue("status").jsonPrimitive.content)
        assertEquals("1", json.getValue("parts_sent").toString())
        assertEquals("1", json.getValue("parts_unknown").toString())
        assertEquals("false", json.getValue("automatic_retry_allowed").toString())
        val failed = json.getValue("failed_parts").jsonArray.single().jsonObject
        assertEquals("2", failed.getValue("android_result_code").toString())
        assertEquals("41", failed.getValue("modem_error_code").toString())
        val error = json.getValue("error").jsonPrimitive.content
        assertTrue(error.contains("сотовую связь"))
        assertTrue(error.contains("Некоторые части уже могли уйти") && error.contains("Не отправляйте"))
    }

    @Test fun smsDeliveryFailureAndTimeoutWarnWithoutChangingSubmissionSuccess() {
        val cases = listOf(
            SmsDeliveryResult(1, 0, emptyMap(), deliveryReports = mapOf(0 to SmsRecipientReceipt(-1, 0x40, "3gpp", SmsRecipientState.Failed))),
            SmsDeliveryResult(1, 0, emptyMap(), timedOut = true)
        )
        cases.forEach { outcome ->
            val json = smsOutcomeJson(outcome, 1)
            assertEquals("true", json.getValue("success").toString())
            assertEquals("sent", json.getValue("status").jsonPrimitive.content)
            assertEquals("false", json.getValue("automatic_retry_allowed").toString())
            val warning = json.getValue("warning").jsonPrimitive.content
            assertTrue(warning.contains("доставк") && warning.contains("Не отправляйте"))
        }
        val delivered = SmsDeliveryResult(1, 0, emptyMap(), deliveryReports = mapOf(
            0 to SmsRecipientReceipt(-1, 0, "3gpp", SmsRecipientState.Delivered)))
        assertFalse(smsOutcomeJson(delivered, 1).containsKey("warning"))
    }
}
