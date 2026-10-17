package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileReviewRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(scratch: File, base64: String): JsonObject = runBlocking {
        val tool = fileManagerTools(LocalFileAccess(scratch, temp.newFolder())).first { it.name == "write_binary_file" }
        val parts = tool.execute(buildJsonObject { put("path", "/scratch/out.bin"); put("base64_content", base64) })
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `base64 with line breaks and base64url decodes to the same bytes`() {
        for (input in listOf("+/8=", "+/\n8=", "-_8=", "data:application/octet-stream;base64,+/8=")) {
            val scratch = temp.newFolder()
            val result = write(scratch, input)
            assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull)
            assertArrayEquals(input, byteArrayOf(0xFB.toByte(), 0xFF.toByte()), File(scratch, "out.bin").readBytes())
        }
    }

    @Test fun `corrupt base64 is an error instead of a silently wrong file`() {
        for (input in listOf("ab*c", "!!!!", "")) {
            val scratch = temp.newFolder()
            val result = write(scratch, input)
            assertNotNull(input, result["error"])
            assertFalse(input, File(scratch, "out.bin").exists())
        }
    }

    @Test fun `recursive single delete needs mandatory confirmation`() {
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation("delete_file", buildJsonObject { put("path", "/sdcard/DCIM"); put("recursive", true) }))
        assertFalse(ToolPermissionPolicy.mandatoryConfirmation("delete_file", buildJsonObject { put("path", "/scratch/a.txt") }))
    }
}
