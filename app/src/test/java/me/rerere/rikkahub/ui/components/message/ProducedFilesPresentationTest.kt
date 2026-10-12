package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducedFilesPresentationTest {
    private fun tool(name: String, input: String, vararg output: UIMessagePart) =
        UIMessagePart.Tool("call-$name", name, input, output.toList())

    @Test fun extractsSuccessfulWorkspaceResultsUsingActualOutputPath() {
        val files = presentProducedFiles(listOf(
            tool("workspace_write_file", """{"path":"/workspace/typed.txt"}""",
                UIMessagePart.Text("""{"path":"/workspace/actual.txt","name":"actual.txt","isDirectory":false,"sizeBytes":4,"updatedAt":12}""")),
            tool("workspace_edit_file", """{"path":"/workspace/actual.txt"}""",
                UIMessagePart.Text("""{"path":"/workspace/actual.txt","replacements":1,"sizeBytes":8,"updatedAt":13}""")),
        ))
        assertEquals(1, files.size)
        assertEquals("/workspace/actual.txt", files.single().path)
        assertEquals(ProducedFileAction.EDITED, files.single().action)
        assertEquals(ProducedFileSource.Workspace("/workspace/actual.txt"), files.single().source)
    }

    @Test fun excludesFailedPendingAndUnrelatedPathText() {
        val files = presentProducedFiles(listOf(
            tool("workspace_write_file", """{"path":"/workspace/failed.txt"}""",
                UIMessagePart.Text("""{"error":"denied","path":"/workspace/failed.txt"}""")),
            tool("workspace_write_file", """{"path":"/workspace/false.txt"}""",
                UIMessagePart.Text("""{"success":false,"path":"/workspace/false.txt","name":"false.txt"}""")),
            tool("workspace_edit_file", """{"path":"/workspace/pending.txt"}"""),
            tool("workspace_shell", """{"command":"echo done"}""",
                UIMessagePart.Text("""{"exitCode":0,"stdout":"wrote /workspace/shell.txt"}""")),
            tool("workspace_read_file", """{"path":"/workspace/read.txt"}""",
                UIMessagePart.Text("""{"path":"/workspace/read.txt","text":"data"}""")),
        ))
        assertTrue(files.isEmpty())
    }

    @Test fun validLegacyWorkspaceResultCanUseKnownInputPath() {
        val files = presentProducedFiles(listOf(
            tool("workspace_edit_file", """{"path":"/workspace/legacy.txt"}""",
                UIMessagePart.Text("""{"replacements":1,"sizeBytes":8,"updatedAt":13}""")),
        ))
        assertEquals("/workspace/legacy.txt", files.single().path)
    }

    @Test fun includesActualDocumentResultWithItsContentUriRoute() {
        val uri = "content://me.rerere.rikkahub.fileprovider/report.zip"
        val files = presentProducedFiles(listOf(
            tool("generate_bug_report", "{}",
                UIMessagePart.Text("""{"ok":true,"path":"/cache/report.zip"}"""),
                UIMessagePart.Document(uri, "report.zip", "application/zip")),
            tool("generate_bug_report", "{}",
                UIMessagePart.Text("""{"error":"failed"}"""),
                UIMessagePart.Document("content://failed/report.zip", "failed.zip", "application/zip")),
        ))
        assertEquals(1, files.size)
        assertEquals(ProducedFileSource.Document(uri, "application/zip"), files.single().source)
        assertEquals("report.zip", files.single().displayName)
    }
}
