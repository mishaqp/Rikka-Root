package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual native pixels, alpha and failed verification prove the frame is never recaptured or substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class RoboScreenshotCaptureTest {
    @get:Rule val directory = TemporaryFolder()
    private val record = RoborazziOptions(taskType = RoborazziTaskType.Record)
    private val verify = RoborazziOptions(taskType = RoborazziTaskType.Verify,
        compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = .007f)))

    @Test fun recordPublishesTheAuthoritativePixelsWithAlphaAndSize() {
        val baseline = File(directory.root, "expected.png")
        val artifact = File(directory.root, "actual.png")
        val frame = createFrame()
        var captures = 0
        var warmup: File? = null
        try {
            captureRoboFrame(artifact, baseline, record) { file, options ->
                captures++
                if (captures == 1) {
                    warmup = file
                    val blank = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888)
                    try { blank.eraseColor(Color.MAGENTA); blank.captureRoboImage(file.absolutePath, roborazziOptions = options) }
                    finally { blank.recycle() }
                } else {
                    frame.captureRoboImage(file.absolutePath, roborazziOptions = options)
                }
            }
            assertEquals("Only warmup and actual read the UI boundary", 2, captures)
            assertFalse("The discarded warmup is removed", requireNotNull(warmup).exists())
            val actual = requireNotNull(BitmapFactory.decodeFile(artifact.absolutePath))
            val expected = requireNotNull(BitmapFactory.decodeFile(baseline.absolutePath))
            try {
                assertEquals(37, actual.width); assertEquals(23, actual.height)
                assertEquals(actual.width, expected.width); assertEquals(actual.height, expected.height)
                assertArrayEquals("Published actual and recorded baseline use identical RGBA pixels", pixels(actual), pixels(expected))
                assertEquals("Actual content survives the discarded blank warmup", Color.BLUE, actual.getPixel(20, 10))
                assertEquals("Partial alpha survives the lossless actual path", 128, Color.alpha(actual.getPixel(0, 0)))
                assertEquals("Transparent alpha survives the lossless actual path", 0, Color.alpha(actual.getPixel(1, 0)))
            } finally { actual.recycle(); expected.recycle() }
        } finally { frame.recycle() }
    }

    @Test fun aChangedActualPixelStillFailsTheOriginalComparatorWithoutRecapture() {
        val baseline = File(directory.root, "expected_change.png")
        val artifact = File(directory.root, "actual_change.png")
        val frame = createFrame()
        try {
            captureRoboFrame(artifact, baseline, record) { file, options -> frame.captureRoboImage(file.absolutePath, roborazziOptions = options) }
            frame.setPixel(20, 10, Color.RED)
            var captures = 0
            val failure = assertThrows(AssertionError::class.java) {
                captureRoboFrame(artifact, baseline, verify) { file, options ->
                    captures++
                    frame.captureRoboImage(file.absolutePath, roborazziOptions = options)
                }
            }
            assertTrue("The production Roborazzi verifier detects the intentional pixel change", failure.message.orEmpty().contains("is changed"))
            assertEquals("Verification does not draw the UI again", 2, captures)
            val actual = requireNotNull(BitmapFactory.decodeFile(artifact.absolutePath))
            val expected = requireNotNull(BitmapFactory.decodeFile(baseline.absolutePath))
            try {
                assertEquals(Color.RED, actual.getPixel(20, 10))
                assertEquals("Verification preserves the expected baseline", Color.BLUE, expected.getPixel(20, 10))
            } finally { actual.recycle(); expected.recycle() }
        } finally { frame.recycle() }
    }

    @Test fun verifyRefusesAnArtifactAliasOfItsBaselineBeforeAnyNativeDraw() {
        val baseline = File(directory.root, "expected_same.png")
        val frame = createFrame()
        try {
            frame.captureRoboImage(baseline.absolutePath, roborazziOptions = record)
            val originalBytes = baseline.readBytes()
            frame.setPixel(20, 10, Color.RED)
            val aliasDirectory = directory.newFolder("alias")
            val aliases = listOf(baseline, File(aliasDirectory, "../expected_same.png"))
            aliases.forEach { artifact ->
                assertEquals("Both test paths resolve to the expected baseline", baseline.canonicalFile, artifact.canonicalFile)
                var captures = 0
                val existingFiles = directory.root.listFiles().orEmpty().map(File::getName).toSet()
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    captureRoboFrame(artifact, baseline, verify) { file, options ->
                        captures++
                        frame.captureRoboImage(file.absolutePath, roborazziOptions = options)
                    }
                }
                assertTrue("The canonical path guard explains the rejected artifact", failure.message.orEmpty().contains("different canonical path"))
                assertEquals("An aliased artifact is rejected before warmup or actual capture", 0, captures)
                assertArrayEquals("Rejecting an aliased actual preserves the original baseline bytes", originalBytes, baseline.readBytes())
                assertEquals("Rejection creates no temporary warmup file", existingFiles,
                    directory.root.listFiles().orEmpty().map(File::getName).toSet())
            }
        } finally { frame.recycle() }
    }

    @Test fun failedWarmupIsRemovedAndNeverPublished() {
        val baseline = File(directory.root, "expected_failure.png")
        val artifact = File(directory.root, "actual_failure.png")
        var warmup: File? = null
        assertThrows(IllegalStateException::class.java) {
            captureRoboFrame(artifact, baseline, record) { file, _ ->
                warmup = file
                throw IllegalStateException("Native warmup failed")
            }
        }
        assertFalse(requireNotNull(warmup).exists())
        assertFalse(artifact.exists())
        assertFalse(baseline.exists())
    }

    private fun createFrame() = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
        setPixel(0, 0, 0x80FF0000.toInt())
        setPixel(1, 0, Color.TRANSPARENT)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
