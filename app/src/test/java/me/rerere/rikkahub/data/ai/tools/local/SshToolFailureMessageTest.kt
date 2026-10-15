package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

class SshToolFailureMessageTest {
    @Test fun `nonzero SSH exit explains the code without changing command streams`() {
        val session = mockk<Session>()
        val channel = mockk<ChannelExec>(relaxed = true)
        every { session.openChannel("exec") } returns channel
        every { channel.outputStream = any() } answers {
            firstArg<OutputStream>().write("original output\n".toByteArray())
        }
        every { channel.setErrStream(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write("missing_binary: command not found\n".toByteArray())
        }
        every { channel.isClosed } returns true
        every { channel.exitStatus } returns 127

        val result = runOnSession(session, "missing_binary", 100)
        assertEquals("false", result["success"]?.jsonPrimitive?.content)
        assertEquals("127", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("original output\n", result["stdout"]?.jsonPrimitive?.content)
        assertEquals("missing_binary: command not found\n", result["stderr"]?.jsonPrimitive?.content)
        assertEquals("Команда завершилась с ошибкой (код 127). Проверьте вывод и результат перед новым запуском.",
            result["message"]?.jsonPrimitive?.content)
    }

    @Test fun `connection failure has Russian reason and recovery while retaining diagnostics`() {
        val result = wrapConnectError("build.example", JSchException("Connection refused"))
        assertEquals("connect_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("build.example", result["host"]?.jsonPrimitive?.content)
        assertTrue(result["reason"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("Connection refused", result["raw"]?.jsonPrimitive?.content)
    }

    @Test fun `overall timeout explains how to retry`() = runBlocking {
        val result = runCancellableSshOp(10) { delay(1000); error("unreachable") }
        assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("timeout_seconds"))
    }

    @Test fun `failed command preserves partial streams and gives Russian action`() {
        val session = mockk<Session>()
        val channel = mockk<ChannelExec>(relaxed = true)
        every { session.openChannel("exec") } returns channel
        every { channel.outputStream = any() } answers {
            firstArg<OutputStream>().write("built 3 targets".toByteArray())
        }
        every { channel.setErrStream(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write("remote warning".toByteArray())
        }
        every { channel.isClosed } throws IOException("Broken pipe")

        val result = runOnSession(session, "make", 100)
        assertTrue(result["error"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("built 3 targets", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("remote warning", result["partial_stderr"]?.jsonPrimitive?.content)
        assertEquals("Broken pipe", result["raw"]?.jsonPrimitive?.content)
    }

    @Test fun `operation exception is localized without swallowing cancellation`() = runBlocking {
        val result = runCancellableSshOp(1000) { throw IOException("Network is unreachable") }
        assertTrue(result["error"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("Network is unreachable", result["raw"]?.jsonPrimitive?.content)
        try {
            runCancellableSshOp(1000) { throw CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `outer timeout keeps output already read by saved host command`() = runBlocking {
        val context = mockk<Context>()
        val repo = mockk<SshHostRepository>()
        coEvery { repo.getByName("build") } returns SshHostEntity(name = "build", host = "build.example", user = "builder", createdAtMs = 0).apply {
            password = "unused"
        }
        val jsch = mockk<JSch>()
        val session = mockk<Session>(relaxed = true)
        val channel = mockk<ChannelExec>(relaxed = true)
        every { session.openChannel("exec") } returns channel
        every { channel.outputStream = any() } answers {
            firstArg<OutputStream>().write("compiled 12 files".toByteArray())
        }
        every { channel.setErrStream(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write("warning before disconnect".toByteArray())
        }
        every { channel.isClosed } answers { Thread.sleep(20); false }
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns
                ProbeOutcome(null, "default", emptyList(), "192.0.2.1", 0)
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } answers {
                // Leave enough time spent in the handshake that the outer timeout wins
                // before runOnSession's separate command deadline.
                Thread.sleep(100)
                session
            }
            val parts = sshExecSavedTool(context, repo).execute(Json.parseToJsonElement("""{"name":"build","command":"make","timeout_seconds":1}"""))
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
            assertEquals("compiled 12 files", result["partial_stdout"]?.jsonPrimitive?.content)
            assertEquals("warning before disconnect", result["partial_stderr"]?.jsonPrimitive?.content)
            assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("timeout_seconds"))
        } finally {
            unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        }
    }
}
