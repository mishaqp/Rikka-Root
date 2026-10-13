package me.rerere.rikkahub.ui.screenshot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatStage1ScreenshotPathsTest {
    @Test fun narrowScreensHaveSeparateBaselinesWithoutMoving411dpReferences() {
        assertEquals(File("src/test/screenshots/chat-after-stage5/light_font1.3_width320/overview.png"),
            ChatStage1ScreenshotPaths("light", 1.3f, 320).baseline("overview"))
        assertEquals(File("src/test/screenshots/chat-after-stage5/light_font1.3/overview.png"),
            ChatStage1ScreenshotPaths("light", 1.3f, 411).baseline("overview"))
    }

    private val properties = listOf(
        "roborazzi.test.record", "chat.stage1.record", "chat.stage1.record.dir",
        "chat.stage2.record", "chat.stage2.record.dir",
        "chat.stage2fixes.record", "chat.stage2fixes.record.dir",
        "chat.stage3.record", "chat.stage3.record.dir",
        "chat.stage4.record", "chat.stage4.record.dir",
        "chat.stage5.record", "chat.stage5.record.dir",
        "chat.audit.record", "chat.audit.record.dir",
        "ui.screenshots.dir",
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
        System.setProperty("chat.stage2.record.dir", "/tmp/chat-stage2-unused-review")
        val paths = ChatStage1ScreenshotPaths("light", 1f)
        assertFalse(paths.record)
        assertEquals(File("src/test/screenshots/chat-after-stage5/light_font1.0/overview.png"), paths.baseline("overview"))
    }

    @Test fun onlyExplicitStage5FlagCanRecord() {
        listOf("chat.stage4.record", "chat.stage3.record", "chat.stage2fixes.record", "chat.stage2.record", "chat.stage1.record", "chat.audit.record").forEach { property ->
            System.setProperty(property, "true")
            val paths = ChatStage1ScreenshotPaths("dark", 1.3f)
            assertFalse(paths.record)
            assertEquals(File("src/test/screenshots/chat-after-stage5/dark_font1.3/overview.png"), paths.baseline("overview"))
            System.clearProperty(property)
        }
        System.setProperty("chat.stage5.record", "true")
        assertTrue(ChatStage1ScreenshotPaths("dark", 1.3f).record)
    }

    @Test fun intentionalReviewDirectoryIsSupported() {
        System.setProperty("chat.stage5.record", "true")
        System.setProperty("chat.stage5.record.dir", "/tmp/chat-stage5-review")
        System.setProperty("chat.stage4.record.dir", "/tmp/chat-stage4-review")
        assertEquals(File("/tmp/chat-stage5-review/amoled_font1.3/plus_sheet.png").canonicalFile,
            ChatStage1ScreenshotPaths("amoled", 1.3f).baseline("plus_sheet"))
    }

    @Test fun stage5DirectoryTakesPrecedenceOverLegacyAliases() {
        System.setProperty("chat.stage5.record", "true")
        System.setProperty("chat.stage5.record.dir", "/tmp/chat-stage5-review")
        System.setProperty("chat.stage2fixes.record.dir", "/tmp/chat-stage2-fixes-review")
        System.setProperty("chat.stage2.record.dir", "/tmp/chat-stage2-review")
        System.setProperty("chat.stage1.record.dir", "/tmp/chat-stage1-review")
        System.setProperty("chat.audit.record.dir", "/tmp/chat-audit-review")
        assertEquals(File("/tmp/chat-stage5-review/dynamic_font1.0/overview.png").canonicalFile,
            ChatStage1ScreenshotPaths("dynamic", 1f).baseline("overview"))
    }

    @Test fun oldDirectoryPropertyCannotRedirectStage5Recording() {
        System.setProperty("chat.stage5.record", "true")
        System.setProperty("chat.stage4.record.dir", "src/test/screenshots/chat-after-stage4")
        System.setProperty("chat.stage2fixes.record.dir", "/tmp/chat-legacy-review")
        assertEquals(File("src/test/screenshots/chat-after-stage5/light_font1.0/overview.png").canonicalFile,
            ChatStage1ScreenshotPaths("light", 1f).baseline("overview"))
    }

    @Test fun recordCannotOverwriteAnyHistoricalArchiveOrChild() {
        System.setProperty("chat.stage5.record", "true")
        listOf("chat-before", "chat-after-stage1", "chat-after-stage1-fixes", "chat-after-stage2", "chat-after-stage2-fixes", "chat-after-stage3", "chat-after-stage4").flatMap { archive ->
            listOf("src/test/screenshots/$archive", "src/test/screenshots/$archive/light_font1.0",
                "src/test/screenshots/chat-after-stage2/../$archive/light_font1.0")
        }.forEach { directory ->
            System.setProperty("chat.stage5.record.dir", directory)
            val result = runCatching { ChatStage1ScreenshotPaths("light", 1f) }
            assertTrue("Historical recording must be rejected: $directory", result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun artifactsCannotBePublishedInsideHistoricalArchives() {
        listOf("chat-before", "chat-after-stage1", "chat-after-stage1-fixes", "chat-after-stage2", "chat-after-stage2-fixes", "chat-after-stage3", "chat-after-stage4").forEach { archive ->
            System.setProperty("ui.screenshots.dir", "src/test/screenshots/$archive")
            val paths = ChatStage1ScreenshotPaths("light", 1f)
            assertTrue(runCatching { paths.artifact("overview") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { paths.publishBeforeReferences() }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
