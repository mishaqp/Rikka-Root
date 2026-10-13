package me.rerere.rikkahub.ui.screenshot

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.CaptureResult
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/** Publishes and verifies one native frame, after the same backend warms its offscreen layers. */
@OptIn(ExperimentalRoborazziApi::class)
internal fun captureRoboFrame(
    artifact: File,
    baseline: File,
    options: RoborazziOptions,
    captureUi: (File, RoborazziOptions) -> Unit,
) {
    // Publishing an actual frame must never overwrite the expected pixels
    // before Verify reads them, including when a path contains ../ aliases.
    require(artifact.canonicalFile != baseline.canonicalFile) {
        "The current screenshot artifact must have a different canonical path from its baseline: $baseline"
    }
    // Callers obtain artifact from ChatStage1ScreenshotPaths, whose canonical
    // directory guard keeps this temporary draw outside historical archives.
    val warmup = File.createTempFile("rikka-native-warmup-", ".png", requireNotNull(artifact.parentFile))
    val warmupOptions = RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        reportOptions = RoborazziOptions.ReportOptions(
            captureResultReporter = object : RoborazziOptions.CaptureResultReporter {
                override fun report(captureResult: CaptureResult, taskType: RoborazziTaskType) = Unit
            },
        ),
    )
    try {
        // The first native draw after an offscreen layer changes size can omit
        // its text. Discard that draw; no test or Android clock advances here.
        captureUi(warmup, warmupOptions)
    } finally {
        warmup.delete()
    }
    captureUi(artifact, RoborazziOptions(taskType = RoborazziTaskType.Record))
    val frame = requireNotNull(BitmapFactory.decodeFile(artifact.absolutePath)) {
        "The current native screenshot must decode: $artifact"
    }
    try {
        // Decode only this lossless actual PNG. Bitmap.captureRoboImage uses the
        // unchanged comparator without another UI draw, so the published artifact
        // is the exact frame being recorded or verified, including real windows.
        frame.captureRoboImage(baseline.absolutePath, roborazziOptions = options)
    } finally {
        frame.recycle()
    }
}
