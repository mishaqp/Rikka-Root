package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class FileToolContractsPart2Test {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun access(scratch: File = temporary.newFolder()) = LocalFileAccess(scratch, temporary.newFolder())
    private fun catalog(access: LocalFileAccess): List<Tool> = fileManagerTools(access, context) + listOf(
        writeTextFileTool(access), zipFilesTool(context, access), unzipFileTool(context, access),
        listZipContentsTool(context, access), downloadTool(context, access), readDocumentTool(context, access),
    )
    private fun schema(tool: Tool) = tool.parameters() as InputSchema.Obj
    private fun property(tool: Tool, name: String) = schema(tool).properties.getValue(name).jsonObject
    private suspend fun result(tool: Tool, input: JsonObject): JsonObject {
        val text = (tool.execute(input).single() as UIMessagePart.Text).text
        assertFalse("Tool JSON must remain compact: ${tool.name}", text.contains('\n'))
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun fileTool(access: LocalFileAccess, name: String) = fileManagerTools(access, context).single { it.name == name }
    private fun assertRussianError(body: JsonObject) {
        assertTrue(body.toString(), body["error"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
        assertNotEquals(body.toString(), JsonPrimitive(true), body["success"])
    }
    private fun archive(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("entry.txt")); zip.write("NEW".toByteArray()); zip.closeEntry()
        }
    }

    @Test fun allTwentyFileToolsHaveRussianOperationDescriptions() {
        val tools = catalog(access())
        assertEquals(20, tools.size)
        tools.forEach { tool -> assertTrue("${tool.name}: ${tool.description}", Regex("^[А-Яа-яЁё]").containsMatchIn(tool.description)) }
    }

    @Test fun everyExposedParameterHasAnExplicitTypeAndRussianDescription() {
        catalog(access()).forEach { tool ->
            val contract = schema(tool)
            assertTrue(tool.name, contract.required.orEmpty().all { it in contract.properties })
            contract.properties.forEach { (name, value) ->
                val parameter = value.jsonObject
                assertTrue("${tool.name}.$name: $parameter", parameter["type"]?.jsonPrimitive?.content in setOf("string", "integer", "boolean", "array"))
                assertTrue("${tool.name}.$name lacks Russian meaning: $parameter", parameter["description"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
            }
        }
    }

    @Test fun rawDocumentAndImageToolsExplainTheirActualAlternatives() {
        val tools = catalog(access()).associateBy { it.name }
        assertTrue(tools.getValue("read_file").description, tools.getValue("read_file").description.contains("read_document"))
        assertTrue(tools.getValue("read_document").description, tools.getValue("read_document").description.contains("read_file"))
        assertTrue(tools.getValue("show_image").description, tools.getValue("show_image").description.contains("open_file"))
        assertTrue(tools.getValue("list_files").description, tools.getValue("list_files").description.contains("find_files"))
    }

    @Test fun listingAndReadSchemasDeclareTheirRealDefaultsAndBounds() {
        val tools = catalog(access()).associateBy { it.name }
        for (name in listOf("list_files", "find_files")) {
            val limit = property(tools.getValue(name), "limit")
            assertEquals(JsonPrimitive(1), limit["minimum"]); assertEquals(JsonPrimitive(500), limit["maximum"])
            assertEquals(JsonPrimitive(50), limit["default"])
            assertEquals(JsonPrimitive(name == "find_files"), property(tools.getValue(name), "recursive")["default"])
        }
        val bytes = property(tools.getValue("read_file"), "max_bytes")
        assertEquals(JsonPrimitive(1), bytes["minimum"]); assertEquals(JsonPrimitive(1048576), bytes["maximum"])
        assertEquals(JsonPrimitive(65536), bytes["default"])
        for (name in listOf("batch_copy", "batch_move", "batch_delete")) {
            val paths = property(tools.getValue(name), "paths")
            assertEquals(JsonPrimitive(1), paths["minItems"]); assertEquals(JsonPrimitive(500), paths["maxItems"])
            if (name != "batch_delete") assertTrue(name, "dst_dir" in schema(tools.getValue(name)).required.orEmpty())
        }
    }

    @Test fun archiveAndDownloadSchemasExposeTheActualLimitAndDefaultValues() {
        val tools = catalog(access()).associateBy { it.name }
        val zip = tools.getValue("zip_files")
        assertEquals(JsonPrimitive(1), property(zip, "sources")["minItems"])
        assertEquals(JsonPrimitive(10000), property(zip, "sources")["maxItems"])
        assertEquals(JsonPrimitive(6), property(zip, "compression_level")["default"])
        assertEquals(JsonPrimitive(false), property(tools.getValue("unzip_file"), "overwrite")["default"])
        val maximum = property(tools.getValue("download_file"), "max_bytes")
        assertEquals(JsonPrimitive(1), maximum["minimum"])
        assertEquals(JsonPrimitive(134217728), maximum["maximum"])
        assertEquals(JsonPrimitive(134217728), maximum["default"])
        assertEquals(JsonPrimitive(false), property(tools.getValue("download_file"), "overwrite")["default"])
        for (page in listOf("page_from", "page_to")) assertEquals(JsonPrimitive(Int.MAX_VALUE), property(tools.getValue("read_document"), page)["maximum"])
    }

    @Test fun nativeReadLimitReturnsExactPrefixAndTruncation() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "data.txt").writeText("abcdef")
        val body = result(fileTool(access(scratch), "read_file"), buildJsonObject { put("path", "data.txt"); put("max_bytes", 3) })
        assertEquals(body.toString(), "abc", body["content"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(3), body["bytes_read"]); assertEquals(JsonPrimitive(true), body["truncated"])
        assertEquals("abcdef", File(scratch, "data.txt").readText())
    }

    @Test fun nativeListingLimitAndRecursiveFlagReachTheRealDirectoryWalk() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "nested").mkdir()
        repeat(3) { File(scratch, "nested/$it.txt").writeText("DATA") }
        val body = result(fileTool(access(scratch), "list_files"), buildJsonObject {
            put("path", "/scratch"); put("pattern", "*.txt"); put("recursive", true); put("limit", 2)
        })
        assertEquals(body.toString(), 2, body["files"]?.jsonArray?.size)
        assertEquals(JsonPrimitive(true), body["truncated"])
        assertEquals(3, File(scratch, "nested").list()!!.size)
    }

    @Test fun nativeOverwriteReplacesOnlyTheRequestedBinaryFile() = runBlocking {
        val scratch = temporary.newFolder(); val target = File(scratch, "target.bin").apply { writeText("OLD") }
        val neighbor = File(scratch, "neighbor.bin").apply { writeText("KEEP") }
        val body = result(fileTool(access(scratch), "write_binary_file"), buildJsonObject {
            put("path", "target.bin"); put("base64_content", "TkVX"); put("overwrite", true)
        })
        assertEquals(body.toString(), JsonPrimitive(true), body["success"])
        assertEquals("NEW", target.readText()); assertEquals("KEEP", neighbor.readText())
    }

    @Test fun nativeIncludeHashComputesTheRealFileDigest() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "data.txt").writeText("real bytes")
        val body = result(fileTool(access(scratch), "file_info"), buildJsonObject { put("path", "data.txt"); put("include_hash", true) })
        val digest = MessageDigest.getInstance("SHA-256").digest("real bytes".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(body.toString(), digest, body["sha256"]?.jsonPrimitive?.content)
    }

    @Test fun nativeAppendKeepsExistingTextAndReportsActualBytes() = runBlocking {
        val scratch = temporary.newFolder(); val file = File(scratch, "note.txt").apply { writeText("OLD") }
        val body = result(writeTextFileTool(access(scratch)), buildJsonObject { put("path", "note.txt"); put("content", "+NEW"); put("append", true) })
        assertEquals(body.toString(), JsonPrimitive(true), body["success"])
        assertEquals(JsonPrimitive(true), body["appended"])
        assertEquals("OLD+NEW", file.readText())
    }

    @Test fun documentNativeNumbersKeepPdfRangeAndTruncatedResult() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "report.pdf").writeText("snapshot bytes")
        var called = false
        val backend = ReadDocumentBackend { file, extension, from, to ->
            assertEquals("snapshot bytes", file.readText()); assertEquals("pdf", extension)
            assertEquals(2, from); assertEquals(3, to); called = true
            ReadDocumentExtraction("abcdef", pages = 4)
        }
        val body = result(readDocumentTool(context, access(scratch), backend), buildJsonObject {
            put("path", "report.pdf"); put("max_chars", 3); put("page_from", 2); put("page_to", 3)
        })
        assertTrue(body.toString(), called); assertEquals(JsonPrimitive("abc"), body["text"])
        assertEquals(JsonPrimitive(6), body["total_chars"]); assertEquals(JsonPrimitive(true), body["truncated"])
        assertEquals(setOf("report.pdf"), scratch.list()!!.toSet())
    }

    @Test fun invalidCompressionNeverCreatesArchiveOrLeavesScratchStage() = runBlocking {
        for (bad in listOf<JsonElement>(JsonPrimitive("oops"), JsonNull, JsonPrimitive("2147483648"), JsonPrimitive(1.5))) {
            val scratch = temporary.newFolder(); File(scratch, "source.txt").writeText("SOURCE")
            val body = result(zipFilesTool(context, access(scratch)), buildJsonObject {
                put("sources", buildJsonArray { add("source.txt") }); put("destination", "out.zip"); put("compression_level", bad)
            })
            assertRussianError(body)
            assertEquals(setOf("source.txt"), scratch.list()!!.toSet()); assertEquals("SOURCE", File(scratch, "source.txt").readText())
        }
    }

    @Test fun nativeZipOverwriteUsesTheExplicitPermissionAndCompressionLevel() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "source.txt").writeText("SOURCE"); File(scratch, "out.zip").writeText("OLD")
        val body = result(zipFilesTool(context, access(scratch)), buildJsonObject {
            put("sources", buildJsonArray { add("source.txt") }); put("destination", "out.zip"); put("overwrite", true); put("compression_level", 0)
        })
        assertEquals(body.toString(), JsonPrimitive(true), body["success"])
        ZipInputStream(File(scratch, "out.zip").inputStream()).use { zip ->
            assertEquals("source.txt", zip.nextEntry.name); assertEquals("SOURCE", zip.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test fun invalidUnzipBooleanCannotSilentlyBecomeFalseAndWriteFiles() = runBlocking {
        val scratch = temporary.newFolder(); archive(File(scratch, "input.zip")); File(scratch, "output").mkdir()
        val body = result(unzipFileTool(context, access(scratch)), buildJsonObject {
            put("source", "input.zip"); put("destination_dir", "output"); put("overwrite", "maybe")
        })
        assertRussianError(body); assertTrue(File(scratch, "output").list()!!.isEmpty())
        assertEquals(setOf("input.zip", "output"), scratch.list()!!.toSet())
    }

    @Test fun russianAttachmentPromptRetainsLatestMessageAndOneBasedReferences() {
        val sources = LocalImageSources(temporary.newFolder(), chatImages = listOf("file:///first.png", "file:///second.png"))
        val prompt = sources.attachmentPrompt()
        assertTrue(prompt, prompt.contains(Regex("[А-Яа-яЁё]")))
        assertTrue(prompt, prompt.contains("attachment:1")); assertTrue(prompt, prompt.contains("attachment:2"))
        assertFalse(prompt, prompt.contains("attachment:0")); assertFalse(prompt, prompt.contains("file:///first.png"))
        assertEquals("", LocalImageSources(temporary.newFolder()).attachmentPrompt())
    }

    @Test fun invalidTypedValuesKeepRussianErrorsAndCannotOverwriteExistingFile() = runBlocking {
        val scratch = temporary.newFolder(); val target = File(scratch, "target.bin").apply { writeText("OLD") }
        val tool = fileTool(access(scratch), "write_binary_file")
        for (bad in listOf<JsonElement>(JsonPrimitive("maybe"), JsonNull, JsonPrimitive(1), buildJsonObject {})) {
            assertRussianError(result(tool, buildJsonObject { put("path", "target.bin"); put("base64_content", "TkVX"); put("overwrite", bad) }))
            assertEquals("OLD", target.readText()); assertEquals(setOf("target.bin"), scratch.list()!!.toSet())
        }
    }

    @Test fun nativeDefaultsAndPathTraversalGuardsRemainEffective() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "data.txt").writeText("abc")
        val access = access(scratch)
        val body = result(fileTool(access, "read_file"), buildJsonObject { put("path", "data.txt") })
        assertEquals(JsonPrimitive("abc"), body["content"]); assertEquals(JsonPrimitive(false), body["truncated"])
        val protected = result(fileTool(access, "write_binary_file"), buildJsonObject {
            put("path", "../outside.bin"); put("base64_content", "TkVX"); put("overwrite", true)
        })
        assertRussianError(protected)
        assertEquals(setOf("data.txt"), scratch.list()!!.toSet())
        catalog(access).forEach { tool -> assertEquals(tool.name, tool.name != "read_document", tool.needsApproval(buildJsonObject {})) }
    }

    @Test fun archiveAlternativesNameTheRegisteredWorkspaceShell() {
        val access = access()
        val tools = listOf(zipFilesTool(context, access), unzipFileTool(context, access), listZipContentsTool(context, access))
        tools.forEach { tool ->
            assertTrue("${tool.name}: the model needs the registered alternative", tool.description.contains("workspace_shell"))
        }
    }

}
