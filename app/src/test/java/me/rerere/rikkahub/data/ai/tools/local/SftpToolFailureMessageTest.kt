package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SftpToolFailureMessageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `upload and download explain credential read failure and retain paths`() = runBlocking {
        val context = mockk<Context>()
        val repository = mockk<SshHostRepository>()
        coEvery { repository.getByName("build") } throws IOException("Read failed")
        val localFile = folder.newFile("artifact.txt").apply { writeText("artifact") }
        val access = mockk<LocalFileAccess>()
        coEvery { access.resolve(localFile.absolutePath, any()) } returns LocalFileSource.Local(localFile)
        for (tool in listOf(sshUploadTool(context, repository, access), sshDownloadTool(context, repository, access))) {
            val parts = tool.execute(buildJsonObject {
                put("name", "build")
                put("local_path", localFile.absolutePath)
                put("remote_path", "/srv/artifact.txt")
            })
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertTrue(result["error"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
            assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
            assertEquals("Read failed", result["raw"]?.jsonPrimitive?.content)
            assertEquals("build", result["name"]?.jsonPrimitive?.content)
            assertEquals(localFile.absolutePath, result["local_path"]?.jsonPrimitive?.content)
            assertEquals("/srv/artifact.txt", result["remote_path"]?.jsonPrimitive?.content)
        }
        assertEquals("artifact", localFile.readText())
    }
}
