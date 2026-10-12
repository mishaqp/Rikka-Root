package me.rerere.rikkahub.ui.screenshot

import java.io.File

/** Legacy helper name retained for CI; live stage-2 captures never overwrite an archive. */
internal class ChatStage1ScreenshotPaths(theme: String, fontScale: Float) {
    private val case = "${theme}_font$fontScale"
    private val beforeDirectory = File("src/test/screenshots/chat-before").canonicalFile
    private val preservedDirectories = listOf(
        beforeDirectory,
        File("src/test/screenshots/chat-after-stage1").canonicalFile,
        File("src/test/screenshots/chat-after-stage1-fixes").canonicalFile,
        File("src/test/screenshots/chat-after-stage2").canonicalFile,
    )
    private val outputDirectory = File(System.getProperty("ui.screenshots.dir", "build/outputs/roborazzi"))
    val record = System.getProperty("chat.stage2fixes.record").toBoolean() ||
        System.getProperty("chat.stage2.record").toBoolean() ||
        System.getProperty("chat.stage1.record").toBoolean() ||
        System.getProperty("chat.audit.record").toBoolean()
    private val baselineDirectory: File = if (record) {
        val directory = System.getProperty("chat.stage2fixes.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: System.getProperty("chat.stage2.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: System.getProperty("chat.stage1.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: System.getProperty("chat.audit.record.dir").orEmpty().takeIf(String::isNotBlank)
            ?: "src/test/screenshots/chat-after-stage2-fixes"
        File(directory).canonicalFile.also(::requireLiveDirectory)
    } else {
        // Deliberately independent of roborazzi.test.record: Daily Build must verify.
        File("src/test/screenshots/chat-after-stage2-fixes")
    }

    fun baseline(name: String) = File(baselineDirectory, "$case/$name.png")

    fun artifact(name: String): File = File(outputDirectory, "chat-after-stage2-fixes/$case/$name.png").also {
        requireLiveDirectory(requireNotNull(it.parentFile))
        requireNotNull(it.parentFile).mkdirs()
    }

    fun publishBeforeReferences() {
        val references = File(beforeDirectory, case).listFiles { file -> file.extension == "png" }.orEmpty()
        require(references.size == 26) { "Expected 26 preserved BEFORE references for $case, found ${references.size}" }
        val artifacts = File(outputDirectory, "chat-before/$case").apply {
            requireLiveDirectory(this)
            mkdirs()
        }
        references.forEach { it.copyTo(File(artifacts, it.name), overwrite = true) }
    }

    private fun requireLiveDirectory(directory: File) {
        val path = directory.canonicalFile.toPath()
        require(preservedDirectories.none { archive -> path.startsWith(archive.toPath()) }) {
            "Historical chat images cannot be overwritten; record corrections and artifacts in a separate directory."
        }
    }
}
