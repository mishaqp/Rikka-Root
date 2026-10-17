package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBitmapFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class QrCodeToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun useRealImageDecoding() { ShadowBitmapFactory.setAllowInvalidImageData(false) }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scratch by lazy { temporary.newFolder("scratch") }
    private val upload by lazy { temporary.newFolder("upload") }
    private fun access(images: List<String> = emptyList()) = LocalFileAccess(scratch, upload, chatImages = images)
    private fun tools(access: LocalFileAccess = access(), vision: Boolean = false) = qrCodeTools(context, access, vision)
    private fun tool(name: String, access: LocalFileAccess = access(), vision: Boolean = false): Tool =
        tools(access, vision).single { it.name == name }
    private fun invoke(tool: Tool, input: JsonElement): List<UIMessagePart> = runBlocking { tool.execute(input) }
    private fun metadata(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun scan(path: String, access: LocalFileAccess = access()): JsonObject = metadata(invoke(tool("qr_scan", access), buildJsonObject { put("path", path) }))
    private fun generated(text: String = "Привет, мир! https://example.com/путь", size: Int = 512, vision: Boolean = false): Pair<List<UIMessagePart>, JsonObject> {
        val parts = invoke(tool("qr_generate", vision = vision), buildJsonObject { put("text", text); put("size", size) })
        return parts to metadata(parts)
    }
    private fun assertSuccess(result: JsonObject) { assertEquals(result.toString(), true, result["success"]?.jsonPrimitive?.booleanOrNull) }
    private fun assertRussianError(result: JsonObject) {
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]{3}").containsMatchIn(result["error"]?.jsonPrimitive?.content.orEmpty()))
    }

    @Test fun factoryHasBothToolsWithReadAndWriteApprovalSemantics() {
        assertEquals(setOf("qr_generate", "qr_scan"), tools().map { it.name }.toSet())
        assertTrue(tool("qr_generate").needsApproval(buildJsonObject {}))
        assertFalse(tool("qr_scan").needsApproval(buildJsonObject {}))
    }

    @Test fun generatedPngRoundTripsUnicodeAndSurvivesTheCallWithoutVision() {
        val expected = "Привет, мир! https://example.com/путь"
        val (parts, result) = generated(expected, 256)
        assertSuccess(result)
        assertTrue(parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertEquals(false, result["visible_to_you"]?.jsonPrimitive?.booleanOrNull)
        val path = result.getValue("path").jsonPrimitive.content
        assertTrue(path.startsWith("/scratch/"))
        val file = File(scratch, path.removePrefix("/scratch/"))
        val png = ImageIO.read(file)
        assertNotNull("Generated file must be actual PNG", png)
        assertEquals(256, png.width)
        assertEquals(256, png.height)
        val scanned = scan(path)
        assertSuccess(scanned)
        assertEquals(expected, scanned.getValue("codes").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(file.isFile)
    }

    @Test fun visionModelReceivesAnImageAndTextModelDoesNot() {
        val (parts, result) = generated(vision = true)
        assertSuccess(result)
        assertEquals(true, result["visible_to_you"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        val image = parts.filterIsInstance<UIMessagePart.Image>().single()
        assertTrue(image.url.startsWith("data:image/png;base64,") || image.url.startsWith("file://"))
    }

    @Test fun scanReturnsEveryQrAndLinearBarcodeInOneRealImage() {
        val image = BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { it.color = java.awt.Color.WHITE; it.fillRect(0, 0, 900, 900); it.dispose() }
        drawCode(image, "first QR", BarcodeFormat.QR_CODE, 40, 40, 280, 280)
        drawCode(image, "второй QR", BarcodeFormat.QR_CODE, 560, 40, 280, 280)
        drawCode(image, "123456789012", BarcodeFormat.CODE_128, 100, 600, 700, 180)
        val source = File(scratch, "mixed.png")
        ImageIO.write(image, "png", source)
        val before = source.readBytes()
        val result = scan("/scratch/mixed.png")
        assertSuccess(result)
        val values = result.getValue("codes").jsonArray.map { it.jsonObject }.associate {
            it.getValue("text").jsonPrimitive.content to it.getValue("format").jsonPrimitive.content
        }
        assertEquals(mapOf("first QR" to "QR_CODE", "второй QR" to "QR_CODE", "123456789012" to "CODE_128"), values)
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun emptyImageReturnsAnEmptyCodesArray() {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "png", File(scratch, "empty.png"))
        val result = scan("/scratch/empty.png")
        assertSuccess(result)
        assertEquals(0, result.getValue("codes").jsonArray.size)
    }

    @Test fun generationEnforcesStrictInputLimitsAndKeepsExistingFiles() {
        val existing = File(scratch, "keep.txt").apply { writeText("keep") }
        val invalid = listOf("""{"text":""}""", """{"text":42}""", """{"text":"ok","size":"256"}""",
            """{"text":"ok","size":127}""", """{"text":"ok","size":1025}""", """{"text":"ok","size":256.5}""",
            buildJsonObject { put("text", "x".repeat(2001)) }.toString(), "[]")
        invalid.forEach { assertRussianError(metadata(invoke(tool("qr_generate"), Json.parseToJsonElement(it)))) }
        assertEquals(listOf(existing.name), scratch.listFiles()!!.map { it.name })
        assertEquals("keep", existing.readText())
        for (size in listOf(128, 1024)) assertSuccess(generated("ok", size).second)
        assertSuccess(generated("x".repeat(2000), 512).second)
    }

    @Test fun invalidAndOversizedInputsAreRejectedWithoutChangingTheSource() {
        File(scratch, "broken.png").writeText("not an image")
        assertRussianError(scan("/scratch/broken.png"))
        val large = File(scratch, "too-large.png").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(16_777_217) } }
        assertRussianError(scan("/scratch/too-large.png"))
        assertEquals(16_777_217L, large.length())
        assertRussianError(metadata(invoke(tool("qr_scan"), buildJsonObject { put("path", 3) })))
    }

    @Test fun oversizedImageDimensionsAreRejectedBeforePixelAllocation() {
        val bytes = codePng("dimensions")
        // PNG IHDR bounds are real and have a valid CRC, but request billions of pixels.
        ByteBuffer.wrap(bytes).putInt(16, 100_000).putInt(20, 100_000)
        val crc = CRC32().apply { update(bytes, 12, 17) }
        ByteBuffer.wrap(bytes).putInt(29, crc.value.toInt())
        File(scratch, "huge-dimensions.png").writeBytes(bytes)
        val result = scan("/scratch/huge-dimensions.png")
        assertRussianError(result)
    }

    @Test fun attachmentCapabilitiesCanReadSelectedFileAndContentWithoutGrantingArbitraryFiles() {
        val outside = temporary.newFile("attached.png").apply { writeBytes(codePng("attachment")) }
        val uri = Uri.parse("content://qr-fixture/photo/1")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(codePng("content attachment")) }
        val selected = access(listOf(outside.toURI().toString(), uri.toString()))
        assertSuccess(scan("attachment:1", selected))
        assertSuccess(scan("attachment:2", selected))
        assertRussianError(scan(outside.absolutePath))
        assertRussianError(scan(uri.toString()))
        assertRussianError(scan("attachment:3", selected))
    }

    @Test fun workspaceResolutionUsesThePassedConversationAccess() {
        val workspace = temporary.newFolder("workspace")
        File(workspace, "picture.png").writeBytes(codePng("workspace"))
        val selected = LocalFileAccess(scratch, upload, workspaceCwd = "/workspace", resolveWorkspacePath = {
            File(workspace, it.removePrefix("/workspace").trimStart('/'))
        })
        assertSuccess(scan("picture.png", selected))
        assertRussianError(scan("/scratch/../private.png", selected))
        assertRussianError(scan("https://example.com/image.png", selected))
    }

    @Test fun reusedScanToolDoesNotRetainRevokedSharedStorageAccess() {
        val primary = temporary.newFolder("primary-storage")
        val file = File(primary, "code.png").apply { writeBytes(codePng("shared")) }
        var enabled = true
        var granted = true
        val policy = SharedStorageAccess({ enabled }, { granted }, { mapOf("primary" to primary) })
        val scoped = LocalFileAccess(scratch, upload, sharedStorageAccess = policy)
        val scanner = tool("qr_scan", scoped)
        val input = buildJsonObject { put("path", file.absolutePath) }
        assertSuccess(metadata(invoke(scanner, input)))
        enabled = false
        assertRussianError(metadata(invoke(scanner, input)))
        enabled = true
        granted = false
        assertRussianError(metadata(invoke(scanner, input)))
        assertArrayEquals(codePng("shared"), file.readBytes())
    }

    @Test fun onlyNamedScreenshotCacheFilesGetReadCapabilityAndOrdinaryFileAccessStaysUnchanged() {
        val screenshot = File(context.cacheDir, "screenshots/screen-123.png").apply { parentFile!!.mkdirs(); writeBytes(codePng("screen")) }
        val browser = File(context.cacheDir, "browser-shots/screenshot-456.png").apply { parentFile!!.mkdirs(); writeBytes(codePng("browser")) }
        val other = File(context.cacheDir, "screenshots/secret.png").apply { writeBytes(codePng("private")) }
        val arbitrary = File(context.cacheDir, "other/screen-123.png").apply { parentFile!!.mkdirs(); writeBytes(codePng("private")) }
        try {
            assertSuccess(scan(screenshot.absolutePath))
            assertSuccess(scan(browser.toURI().toString()))
            assertRussianError(scan(other.absolutePath))
            assertRussianError(scan(arbitrary.absolutePath))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { access().resolve(screenshot.absolutePath) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { access().resolve(screenshot.absolutePath, write = true) } }
            val optIn = access().withQrScreenshotReads(context)
            val readable = runBlocking { optIn.resolve(screenshot.absolutePath) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { optIn.openOutput(readable) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { optIn.delete(readable) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { optIn.resolve(screenshot.absolutePath, write = true) } }
            assertArrayEquals(codePng("screen"), screenshot.readBytes())
        } finally { listOf(screenshot, browser, other, arbitrary).forEach { it.delete() } }
    }

    @Test fun screenshotAndScratchSymlinksCannotExposePrivateImages() {
        val outside = temporary.newFile("private.png").apply { writeBytes(codePng("private")) }
        val screenshotLink = File(context.cacheDir, "screenshots/screen-999.png").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(screenshotLink.toPath(), outside.toPath())
        Files.createSymbolicLink(File(scratch, "link.png").toPath(), outside.toPath())
        try {
            assertRussianError(scan(screenshotLink.absolutePath))
            assertRussianError(scan("/scratch/link.png"))
        } finally { screenshotLink.delete() }
    }

    @Test fun failedGenerationCleansOnlyTheNewOwnedScratchFile() {
        val existing = File(scratch, "keep.png").apply { writeBytes(codePng("keep")) }
        val failing = spyk(access())
        coEvery { failing.write(any(), any(), any(), any()) } throws IOException("disk full")
        val result = metadata(invoke(tool("qr_generate", failing), buildJsonObject { put("text", "failure") }))
        assertRussianError(result)
        assertEquals(listOf(existing.name), scratch.listFiles()!!.map { it.name })
        assertTrue(existing.isFile)
    }

    @Test fun cancelledGenerationCleansItsNewFileAndPropagatesCancellation() = runBlocking {
        val existing = File(scratch, "keep.txt").apply { writeText("keep") }
        val failing = spyk(access())
        lateinit var task: Job
        var attemptedWrite = false
        coEvery { failing.write(any(), any(), any(), any()) } coAnswers {
            attemptedWrite = true
            task.cancel()
            throw CancellationException("cancelled write")
        }
        task = launch { tool("qr_generate", failing).execute(buildJsonObject { put("text", "cancelled") }) }
        task.join()
        assertTrue("Cancellation must occur while writing the newly owned file", attemptedWrite)
        assertTrue(task.isCancelled)
        assertEquals(listOf(existing.name), scratch.listFiles()!!.map { it.name })
        assertEquals("keep", existing.readText())
    }

    private fun codePng(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        drawCode(image, text, BarcodeFormat.QR_CODE, 0, 0, 256, 256)
        ImageIO.write(image, "png", output)
    }.toByteArray()

    private fun drawCode(image: BufferedImage, text: String, format: BarcodeFormat, left: Int, top: Int, width: Int, height: Int) {
        val matrix = MultiFormatWriter().encode(text, format, width, height, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) image.setRGB(left + x, top + y, if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt())
    }

    /** On phones cacheDir is /data/user/0/<pkg>/cache, and /data/user/0 is a system link to /data/data. */
    @Test fun scanOfGeneratedScratchFileWorksWhenAppCacheSitsBehindSystemLink() {
        val realData = temporary.newFolder("data-data")
        val userLink = File(temporary.root, "user-0").toPath()
        Files.createSymbolicLink(userLink, realData.toPath())
        val linkedCache = File(userLink.toFile(), "cache").apply { mkdirs() }
        val phoneLike = object : android.content.ContextWrapper(context) {
            override fun getCacheDir(): File = linkedCache
            override fun getApplicationContext(): Context = this
        }
        val generate = qrCodeTools(phoneLike, access(), false).single { it.name == "qr_generate" }
        val created = metadata(invoke(generate, buildJsonObject { put("text", "Проверка Rikka 123"); put("size", 256) }))
        assertSuccess(created)
        val scanTool = qrCodeTools(phoneLike, access(), false).single { it.name == "qr_scan" }
        val result = metadata(invoke(scanTool, buildJsonObject { put("path", created.getValue("path").jsonPrimitive.content) }))
        assertSuccess(result)
        assertTrue(result.toString(), result.toString().contains("Проверка Rikka 123"))
    }
}
