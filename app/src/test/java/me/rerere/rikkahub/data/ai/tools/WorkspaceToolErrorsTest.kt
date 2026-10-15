package me.rerere.rikkahub.data.ai.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkspaceToolErrorsTest {
    private val repository = mockk<WorkspaceRepository> {
        coEvery { getById("w") } returns null
    }

    @Test fun missingArgumentsExplainWhatToProvideInRussian() = runBlocking {
        val tools = createWorkspaceTools("w", repository)
        listOf("workspace_shell", "workspace_background_start", "workspace_background_stop",
            "workspace_background_output", "workspace_read_file", "workspace_write_file", "workspace_edit_file")
            .forEach { name ->
                val text = (tools.first { it.name == name }.execute(Json.parseToJsonElement("{}")).single() as UIMessagePart.Text).text
                val error = Json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content
                assertTrue("$name: $error", Regex("Укажите|Проверьте").containsMatchIn(error))
                assertFalse(error, error.contains("Exception"))
            }
    }

    @Test fun shellFailureIsRussianAndDoesNotExposePlatformMessage() = runBlocking {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } throws IOException("token=private /Секрет/input")
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_shell" }
        val text = (tool.execute(Json.parseToJsonElement("""{"command":"pwd"}""")).single() as UIMessagePart.Text).text
        assertTrue(text, text.contains("Проверьте"))
        assertFalse(text, text.contains("private"))
        assertFalse(text, text.contains("Секрет"))
    }

    @Test fun cancellationStillPropagatesInsteadOfReturningFailure() = runBlocking {
        val cancelled = CancellationException("stop")
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } throws cancelled
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_shell" }
        try {
            tool.execute(Json.parseToJsonElement("""{"command":"pwd"}"""))
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun writeFailureKeepsSpecificAuthoredRecoveryInsteadOfGenericDiskError() = runBlocking {
        val reason = "Файл уже существует. Укажите другое имя или overwrite=true."
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns
            WorkspaceCommandResult(1, "", reason)
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_write_file" }
        val text = (tool.execute(Json.parseToJsonElement("""{"path":"/workspace/test.txt","text":"new","overwrite":false}""")).single() as UIMessagePart.Text).text
        assertEquals(reason, Json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun shellTimeoutAndTruncationKeepOutputAndExplainIncompleteExecution() = runBlocking {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns
            WorkspaceCommandResult(124, "already done", "partial diagnostic", timedOut = true, truncated = true)
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_shell" }
        val text = (tool.execute(Json.parseToJsonElement("""{"command":"long command"}""")).single() as UIMessagePart.Text).text
        val output = Json.parseToJsonElement(text).jsonObject
        assertEquals("already done", output.getValue("stdout").jsonPrimitive.content)
        assertEquals("partial diagnostic", output.getValue("stderr").jsonPrimitive.content)
        assertTrue(output.getValue("timedOut").jsonPrimitive.content.toBooleanStrict())
        assertTrue(output.getValue("truncated").jsonPrimitive.content.toBooleanStrict())
        assertEquals("Время команды истекло. Проверьте, что успело выполниться, перед новым вызовом; команда автоматически не повторяется.",
            output.getValue("message").jsonPrimitive.content)
        assertTrue(output.getValue("warning").jsonPrimitive.content.contains("не полностью"))
    }

    @Test fun nonzeroShellExitExplainsFailureAndPreservesCommandOutput() = runBlocking {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns
            WorkspaceCommandResult(23, "partial stdout", "English command diagnostic")
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_shell" }
        val text = (tool.execute(Json.parseToJsonElement("""{"command":"false"}""")).single() as UIMessagePart.Text).text
        val output = Json.parseToJsonElement(text).jsonObject
        assertEquals("23", output.getValue("exitCode").jsonPrimitive.content)
        assertEquals("partial stdout", output.getValue("stdout").jsonPrimitive.content)
        assertEquals("English command diagnostic", output.getValue("stderr").jsonPrimitive.content)
        assertEquals("false", output.getValue("timedOut").jsonPrimitive.content)
        assertEquals("Команда завершилась с ошибкой (код 23). Проверьте вывод и результат перед новым запуском.",
            output["message"]?.jsonPrimitive?.content)
    }

    @Test fun successfulShellExitDoesNotAddFailureMessage() = runBlocking {
        coEvery { repository.executeCommand(any(), any(), any(), any(), any()) } returns WorkspaceCommandResult(0, "done", "")
        val tool = createWorkspaceTools("w", repository).first { it.name == "workspace_shell" }
        val text = (tool.execute(Json.parseToJsonElement("""{"command":"true"}""")).single() as UIMessagePart.Text).text
        assertFalse(Json.parseToJsonElement(text).jsonObject.containsKey("message"))
    }

}
