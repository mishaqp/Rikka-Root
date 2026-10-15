package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference

class SshProbeFallbackLogicTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `successful default route is used for SSH when another network probe failed`() = runBlocking {
        val context = mockk<Context>()
        val session = mockk<Session>(relaxed = true)
        val channel = mockk<ChannelExec>(relaxed = true)
        every { session.openChannel("exec") } returns channel
        every { channel.isClosed } returns true
        every { channel.exitStatus } returns 0

        withDefaultRouteProbe(context, session) {
            val result = execOneShot(context, "build.example", 22, "builder", SshAuth(password = "unused"),
                "printf ok", 1000, AtomicReference())
            assertEquals("A successful unbound default-route probe is reachable: $result",
                "true", result["success"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `successful default route is used for SFTP when another network probe failed`() = runBlocking {
        val context = mockk<Context>()
        val session = mockk<Session>(relaxed = true)
        val channel = mockk<ChannelSftp>(relaxed = true)
        every { session.openChannel("sftp") } returns channel
        val repository = mockk<SshHostRepository>()
        coEvery { repository.getByName("build") } returns SshHostEntity(name = "build", host = "build.example",
            user = "builder", createdAtMs = 0).apply { password = "unused" }
        val file = folder.newFile("upload.txt").apply { writeText("payload") }
        val access = mockk<LocalFileAccess>()
        coEvery { access.resolve(file.absolutePath, false) } returns LocalFileSource.Local(file)

        withDefaultRouteProbe(context, session) {
            val parts = sshUploadTool(context, repository, access).execute(buildJsonObject {
                put("name", "build"); put("local_path", file.absolutePath); put("remote_path", "/tmp/upload.txt")
            })
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("A successful unbound default-route probe is reachable: $result",
                "true", result["success"]?.jsonPrimitive?.content)
            assertEquals("7", result["bytes"]?.jsonPrimitive?.content)
        }
    }

    private suspend fun withDefaultRouteProbe(context: Context, session: Session, block: suspend () -> Unit) {
        val jsch = mockk<JSch>()
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns ProbeOutcome(
                winningNetwork = null, winningLabel = "default", failures = listOf("wifi" to "Connection refused"),
                resolvedIp = "192.0.2.10", totalMs = 10,
            )
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } returns session
            block()
        } finally {
            unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        }
    }
}
