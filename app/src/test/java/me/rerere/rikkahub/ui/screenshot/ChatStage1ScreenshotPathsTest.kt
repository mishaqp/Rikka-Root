package me.rerere.rikkahub.ui.screenshot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatStage1ScreenshotPathsTest {
    private val properties = listOf(
        "roborazzi.test.record", "chat.stage1.record", "chat.stage1.record.dir",
        "chat.audit.record", "chat.audit.record.dir",
    )
    private lateinit var previous: Map<String, String?>

    @Before fun isolateRecordProperties() {
        previous = properties.associateWith(System::getProperty)
        properties.forEach(System::clearProperty)
    }

    @After fun restoreRecordProperties() {
        previous.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test fun dailyBuildGlobalRecordStillVerifiesAfter() {
        System.setProperty("roborazzi.test.record", "true")
        System.setProperty("chat.stage1.record.dir", "/tmp/chat-stage1-unused-review")
        val paths = ChatStage1ScreenshotPaths("light", 1f)
        assertFalse(paths.record)
        assertEquals(File("src/test/screenshots/chat-after-stage1-fixes/light_font1.0/overview.png"), paths.baseline("overview"))
    }

    @Test fun explicitRecordAndLegacyAuditAliasTargetAfter() {
        listOf("chat.stage1.record", "chat.audit.record").forEach { property ->
            System.setProperty(property, "true")
            val paths = ChatStage1ScreenshotPaths("dark", 1.3f)
            assertTrue(paths.record)
            assertEquals(File("src/test/screenshots/chat-after-stage1-fixes/dark_font1.3/overview.png").canonicalFile, paths.baseline("overview"))
            System.clearProperty(property)
        }
    }

    @Test fun intentionalReviewDirectoryIsSupported() {
        System.setProperty("chat.stage1.record", "true")
        System.setProperty("chat.stage1.record.dir", "/tmp/chat-stage1-review")
        assertEquals(File("/tmp/chat-stage1-review/amoled_font1.3/plus_sheet.png").canonicalFile,
            ChatStage1ScreenshotPaths("amoled", 1.3f).baseline("plus_sheet"))
    }

    @Test fun recordCannotOverwriteHistoricalBefore() {
        System.setProperty("chat.stage1.record", "true")
        listOf("src/test/screenshots/chat-before", "src/test/screenshots/chat-before/light_font1.0", "src/test/screenshots/chat-after-stage1", "src/test/screenshots/chat-after-stage1/light_font1.0").forEach { directory ->
            System.setProperty("chat.stage1.record.dir", directory)
            val result = runCatching { ChatStage1ScreenshotPaths("light", 1f) }
            assertTrue("Historical recording must be rejected: $directory", result.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
