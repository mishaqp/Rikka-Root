package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileManagerToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun output(parts: List<UIMessagePart>) = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    @Test fun readingCyrillicTextAndBatchErrorsPreservesIndependentSuccessfulItems() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        access.write(access.resolve("hello.txt", true), "Привет, мир".toByteArray())
        val tools = fileManagerTools(access)
        val read = tools.single { it.name == "read_file" }
        assertEquals("Привет, мир", output(read.execute(Json.parseToJsonElement("{\"path\":\"hello.txt\"}")))["content"]?.jsonPrimitive?.content)
        val deleted = tools.single { it.name == "batch_delete" }.execute(Json.parseToJsonElement("{\"paths\":[\"hello.txt\",\"/system/build.prop\"]}"))
        assertEquals(1, output(deleted)["success"]?.jsonPrimitive?.int)
        assertEquals(1, output(deleted)["failed"]?.jsonArray?.size)
    }

    @Test fun listingReportsTruncationAndFindUsesGlobNameMatching() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        listOf("one.txt", "two.txt", "three.log").forEach { access.write(access.resolve(it, true), "x".toByteArray()) }
        val tools = fileManagerTools(access)
        val listed = output(tools.single { it.name == "list_files" }.execute(Json.parseToJsonElement("{\"path\":\"/scratch\",\"limit\":1}")))
        assertTrue(listed["truncated"]!!.jsonPrimitive.boolean)
        val found = output(tools.single { it.name == "find_files" }.execute(Json.parseToJsonElement("{\"root\":\"/scratch\",\"query\":\"*.txt\"}")))
        assertEquals(2, found["files"]!!.jsonArray.size)
    }

    @Test fun textWritesRefuseSilentOverwrite() = runBlocking {
        val access = LocalFileAccess(temporary.newFolder("scratch"), temporary.newFolder("upload"))
        val tool = writeTextFileTool(access)
        assertTrue(output(tool.execute(Json.parseToJsonElement("{\"path\":\"note.txt\",\"content\":\"first\"}")))["success"]!!.jsonPrimitive.boolean)
        assertNotNull(output(tool.execute(Json.parseToJsonElement("{\"path\":\"note.txt\",\"content\":\"second\"}")))["error"])
        assertEquals("first", access.openInput(access.resolve("note.txt")).use { it.reader().readText() })
    }
}
