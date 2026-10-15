package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchFileArgumentValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun malformedExplicitPathsCannotFallBackToDeletingAllMatchingFiles() = runBlocking {
        val scratch = temporary.newFolder("scratch")
        val first = File(scratch, "first.txt").apply { writeText("first file") }
        val second = File(scratch, "second.txt").apply { writeText("second file") }
        val access = LocalFileAccess(scratch, temporary.newFolder("upload"))
        val tool = fileManagerTools(access).single { it.name == "batch_delete" }

        val parts = tool.execute(Json.parseToJsonElement(
            """{"paths":"first.txt","root":"/scratch","pattern":"*.txt"}"""))
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        assertTrue("A malformed explicit list must not broaden the deletion to root + pattern", first.isFile && second.isFile)
        assertEquals("first file", first.readText())
        assertEquals("second file", second.readText())
        assertNotNull(output["error"])
    }

    @Test fun malformedExplicitPathsCannotFallBackToCopyOrMoveWildcardMatches() = runBlocking {
        for (operation in listOf("copy", "move")) for (invalid in listOf("\"first.txt\"", "null", "{}")) {
            val scratch = temporary.newFolder()
            val source = File(scratch, "source").apply { mkdir() }
            val first = File(source, "first.txt").apply { writeText("first file") }
            val second = File(source, "second.txt").apply { writeText("second file") }
            val destination = File(scratch, "destination").apply { mkdir() }
            val access = LocalFileAccess(scratch, temporary.newFolder())
            val tool = fileManagerTools(access).single { it.name == "batch_$operation" }

            val parts = tool.execute(Json.parseToJsonElement(
                """{"paths":$invalid,"root":"/scratch/source","pattern":"*.txt","dst_dir":"/scratch/destination"}"""))
            val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

            assertTrue("$operation must validate paths before touching any source", first.isFile && second.isFile)
            assertEquals("first file", first.readText())
            assertEquals("second file", second.readText())
            assertTrue("$operation must not create unintended wildcard targets", destination.listFiles().orEmpty().isEmpty())
            assertNotNull(output["error"])
        }
    }

    @Test fun validExplicitListsAndRootPatternsStillCopyAndMoveOnlySelectedFiles() = runBlocking {
        for (operation in listOf("copy", "move")) for (explicit in listOf(true, false)) {
            val scratch = temporary.newFolder()
            val source = File(scratch, "source").apply { mkdir() }
            val first = File(source, "first.txt").apply { writeText("selected file") }
            val untouched = File(source, "second.log").apply { writeText("unselected file") }
            val destination = File(scratch, "destination").apply { mkdir() }
            val access = LocalFileAccess(scratch, temporary.newFolder())
            val tool = fileManagerTools(access).single { it.name == "batch_$operation" }
            val selection = if (explicit) """"paths":["/scratch/source/first.txt"]"""
                else """"root":"/scratch/source","pattern":"*.txt""""

            val parts = tool.execute(Json.parseToJsonElement(
                """{$selection,"dst_dir":"/scratch/destination"}"""))
            val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

            assertEquals(output.toString(), 1, output.getValue("success").jsonPrimitive.int)
            assertTrue(output.getValue("failed").jsonArray.isEmpty())
            assertEquals("selected file", File(destination, "first.txt").readText())
            assertEquals(operation == "copy", first.exists())
            assertEquals("unselected file", untouched.readText())
            assertEquals(listOf("first.txt"), destination.listFiles().orEmpty().map { it.name })
        }
    }
}
