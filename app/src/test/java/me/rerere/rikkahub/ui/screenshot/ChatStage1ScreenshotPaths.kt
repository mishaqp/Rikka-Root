package me.rerere.rikkahub.ui.screenshot

import java.io.File

/** Separate live stage-1 baselines from the preserved audit reference images. */
internal class ChatStage1ScreenshotPaths(theme: String, fontScale: Float) {
    private val case = "${theme}_font$fontScale"
    private val beforeDirectory = File("src/test/screenshots/chat-before").canonicalFile
    private val outputDirectory = File(System.getProperty("ui.screenshots.dir", "build/outputs/roborazzi"))
    val record = System.getProperty("chat.stage1.record").toBoolean() ||
        System.getProperty("chat.audit.record").toBoolean()
    private val baselineDirectory: File = if (record) {
        val directory = System.getProperty("chat.stage1.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: System.getProperty("chat.audit.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: "src/test/screenshots/chat-after-stage1"
        File(directory).canonicalFile.also {
            require(it != beforeDirectory && !it.toPath().startsWith(beforeDirectory.toPath())) {
                "Historical chat-before images cannot be overwritten; record stage 1 in a separate directory."
            }
        }
    } else {
        // Deliberately independent of roborazzi.test.record: Daily Build must verify.
        File("src/test/screenshots/chat-after-stage1")
    }

    fun baseline(name: String) = File(baselineDirectory, "$case/$name.png")

    fun artifact(name: String): File = File(outputDirectory, "chat-after-stage1/$case/$name.png").also {
        requireNotNull(it.parentFile).mkdirs()
    }

    fun publishBeforeReferences() {
        val references = File(beforeDirectory, case).listFiles { file -> file.extension == "png" }.orEmpty()
        require(references.size == 26) { "Expected 26 preserved BEFORE references for $case, found ${references.size}" }
        val artifacts = File(outputDirectory, "chat-before/$case").apply { mkdirs() }
        references.forEach { it.copyTo(File(artifacts, it.name), overwrite = true) }
    }
}
