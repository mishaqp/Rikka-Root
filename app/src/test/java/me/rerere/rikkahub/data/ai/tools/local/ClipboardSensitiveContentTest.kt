package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ClipboardSensitiveContentTest {
    private fun read(text: String, reveal: Boolean = false): JsonObject = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("test", text))
        val parts = buildClipboardTool(context).execute(buildJsonObject {
            put("action", "read"); if (reveal) put("reveal", true)
        })
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun sensitiveClipboardReadMasksSecretsAndWarnsWithoutRepeatingThem() {
        for ((secret, hidden) in listOf("sk-abcdefghijklmnopqrstuvwxyz" to "sk-abcdefghijklmnopqrstuvwxyz",
            "Карта 4111 1111 1111 1111 до 12/30" to "4111 1111 1111", "test@example.com:password123" to "password123")) {
            val result = read(secret)
            val text = result["text"]?.jsonPrimitive?.content.orEmpty()
            assertFalse("Masked read must not carry $hidden: $text", text.contains(hidden))
            assertTrue(text, text.contains("••••"))
            assertEquals(true, result["masked"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("Detected clipboard secrets must be flagged", true,
                result["sensitive_content_detected"]?.jsonPrimitive?.booleanOrNull)
            val warning = result["warning"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(warning, warning.contains("конфиденциаль", ignoreCase = true))
            assertFalse("Warnings must not copy the sensitive payload", warning.contains(secret))
        }
        assertTrue(read("4111 1111 1111 1111")["text"]?.jsonPrimitive?.content.orEmpty().endsWith("1111"))
    }

    @Test fun revealReturnsTheExactValueAndNeedsApproval() {
        val result = read("4111 1111 1111 1111", reveal = true)
        assertEquals("4111 1111 1111 1111", result["text"]?.jsonPrimitive?.content)
        assertEquals(false, result["masked"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(result["warning"]?.jsonPrimitive?.content.orEmpty().contains("не повторяйте", ignoreCase = true))
        val policy = me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
        assertFalse(policy.sideEffect("clipboard_tool", buildJsonObject { put("action", "read") }))
        assertTrue(policy.sideEffect("clipboard_tool", buildJsonObject { put("action", "read"); put("reveal", true) }))
    }

    @Test fun ordinaryAndEmptyClipboardReadsDoNotProduceWarnings() {
        for (text in listOf("Купить молоко", "")) {
            val result = read(text)
            assertEquals(text, result["text"]?.jsonPrimitive?.content)
            assertFalse(result["sensitive_content_detected"]?.jsonPrimitive?.booleanOrNull == true)
            assertFalse(result.containsKey("warning"))
        }
    }
}
