package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class LocalToolsRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(files: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }

    @Test fun enablingArchivesRegistersExactlyTheThreeNativeZipTools() {
        val tools = scopedFileTools(context(temporary.newFolder()), listOf(LocalToolOption.Archive))

        assertEquals(listOf("zip_files", "unzip_file", "list_zip_contents"), tools.map { it.name })
    }

    @Test fun disabledArchivesAreAbsentEvenWhenFileToolsAreEnabled() {
        val tools = scopedFileTools(context(temporary.newFolder()), listOf(LocalToolOption.Files))

        assertTrue(tools.any { it.name == "list_files" })
        assertTrue(tools.none { it.name in setOf("zip_files", "unzip_file", "list_zip_contents") })
    }

    @Test fun archiveToolsUseTheConversationWorkspaceAndItsCurrentDirectory() = runBlocking {
        val selectedId = Uuid.random()
        val assistantWorkspaceId = Uuid.random()
        val selected = temporary.newFolder("selected-workspace")
        val assistantWorkspace = temporary.newFolder("assistant-workspace")
        File(selected, "project").mkdir()
        File(assistantWorkspace, "project").mkdir()
        File(selected, "project/source.txt").writeText("Из выбранного рабочего пространства")
        File(assistantWorkspace, "project/source.txt").writeText("Из другого рабочего пространства")
        val assistant = Assistant(
            workspaceId = assistantWorkspaceId,
            localTools = listOf(LocalToolOption.Archive),
        )
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = ConversationConfig(workspaceId = selectedId),
            workspaceCwd = "/workspace/project",
        )
        val effective = Settings(assistants = listOf(assistant), assistantId = assistant.id)
            .getAssistantOf(conversation)
        val roots = mapOf(selectedId to selected, assistantWorkspaceId to assistantWorkspace)
        val tools = scopedFileTools(
            context(temporary.newFolder("files")),
            effective.localTools,
            workspaceCwd = conversation.workspaceCwd,
            resolveWorkspacePath = { path ->
                File(roots.getValue(requireNotNull(effective.workspaceId)), path.removePrefix("/workspace").trimStart('/'))
            },
        ).associateBy { it.name }

        val zipped = tools.getValue("zip_files").result("""{"sources":["source.txt"],"destination":"archive.zip"}""")
        assertTrue(zipped.toString(), zipped.getValue("success").jsonPrimitive.boolean)
        assertTrue(File(selected, "project/archive.zip").isFile)
        assertFalse(File(assistantWorkspace, "project/archive.zip").exists())

        val listing = tools.getValue("list_zip_contents").result("""{"source":"/workspace/project/archive.zip"}""")
        assertEquals(listOf("source.txt"), listing.getValue("entries").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        })

        val unzipped = tools.getValue("unzip_file").result("""{"source":"archive.zip","destination_dir":"/workspace/restored"}""")
        assertTrue(unzipped.toString(), unzipped.getValue("success").jsonPrimitive.boolean)
        assertEquals("Из выбранного рабочего пространства", File(selected, "restored/source.txt").readText())
        assertFalse(File(assistantWorkspace, "restored").exists())
    }

    private suspend fun Tool.result(input: String): JsonObject {
        val parts = execute(Json.parseToJsonElement(input))
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
}
