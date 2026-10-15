package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import io.mockk.mockk
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadPolicyTest {
    @Test fun missingOrInvalidDownloadParametersExplainRecovery() = runBlocking {
        val tool = downloadTool(mockk(), mockk())
        listOf("{}" to "Укажите адрес файла в url.",
            """{"url":"https://example.com/file","max_bytes":"invalid"}""" to "Укажите max_bytes целым числом.")
            .forEach { (input, expected) ->
                val output = (tool.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text
                assertEquals(expected, Json.parseToJsonElement(output).jsonObject["error"]!!.jsonPrimitive.content)
            }
    }

    @Test fun rejectsLocalSourcesCredentialsAndMissingHosts() {
        listOf("file:///etc/passwd", "content://docs/file", "https://user:password@example.com/x", "https:///x").forEach {
            try { validatedDownloadUrl(it); fail("accepted forbidden URL") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://example.com/track.mp3", validatedDownloadUrl("https://example.com/track.mp3"))
    }

    @Test fun filenamesCannotEscapeOrHideTheDestination() {
        listOf("../secret", "..", "a/b", "a\\b", "\u0000bad", ".hidden").forEach {
            try { validatedDownloadFilename(it); fail("accepted unsafe filename") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("песня.mp3", validatedDownloadFilename("песня.mp3"))
    }

    @Test fun unknownLengthStillHonorsTheStreamingLimit() {
        val output = ByteArrayOutputStream()
        try {
            copyDownloadBounded(ByteArrayInputStream(ByteArray(9)), output, maxBytes = 8) {}
            fail("oversized stream succeeded")
        } catch (_: IOException) { }
        assertTrue(output.size() <= 8)
    }

    @Test fun exactLimitAndZeroByteFilesAreAccepted() {
        val output = ByteArrayOutputStream()
        assertEquals(8L, copyDownloadBounded(ByteArrayInputStream(ByteArray(8)), output, 8) {})
        assertEquals(0L, copyDownloadBounded(ByteArrayInputStream(byteArrayOf()), output, 8) {})
    }

    @Test fun cancellationIsCheckedBeforeAnyWrite() {
        val output = ByteArrayOutputStream()
        try {
            copyDownloadBounded(ByteArrayInputStream(ByteArray(8)), output, 8) { throw CancellationException() }
            fail("cancellation was ignored")
        } catch (_: CancellationException) { }
        assertEquals(0, output.size())
    }

    @Test fun negativeOrExcessiveByteLimitsAreRejected() {
        listOf(0L, -1L, MAX_DOWNLOAD_BYTES + 1).forEach {
            try { validatedDownloadLimit(it); fail("accepted unsafe limit") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
