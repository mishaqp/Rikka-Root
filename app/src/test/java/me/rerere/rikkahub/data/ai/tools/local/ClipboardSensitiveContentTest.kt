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
    private fun read(text: String): JsonObject = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("test", text))
        val parts = buildClipboardTool(context).execute(buildJsonObject { put("action", "read") })
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun sensitiveClipboardReadPreservesTextAndWarnsWithoutRepeatingTheSecret() {
        for (secret in listOf("sk-abcdefghijklmnopqrstuvwxyz", "4111 1111 1111 1111", "test@example.com:password123")) {
            val result = read(secret)
            assertEquals(secret, result["text"]?.jsonPrimitive?.content)
            assertEquals("Detected clipboard secrets must be flagged", true,
                result["sensitive_content_detected"]?.jsonPrimitive?.booleanOrNull)
            val warning = result["warning"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(warning, warning.contains("конфиденциаль", ignoreCase = true))
            assertTrue(warning, warning.contains("не повторяйте", ignoreCase = true))
            assertFalse("Warnings must not copy the sensitive payload", warning.contains(secret))
        }
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
