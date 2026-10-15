package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SshCancellationLifecycleTest {
    @Test(timeout = 4000)
    fun `session published after cancellation is closed without starting a remote command`() = runBlocking {
        val context = mockk<Context>()
        val jsch = mockk<JSch>()
        val session = mockk<Session>(relaxed = true)
        val channel = mockk<ChannelExec>(relaxed = true)
        val commandStarted = AtomicBoolean(false)
        val disconnected = AtomicBoolean(false)
        val handshakeStarted = CountDownLatch(1)
        val handshakeReleased = CountDownLatch(1)
        every { session.openChannel("exec") } answers { commandStarted.set(true); channel }
        every { session.disconnect() } answers { disconnected.set(true) }
        every { channel.isClosed } returns true
        every { channel.exitStatus } returns 0
        val releaseHandshake = thread(isDaemon = true, name = "ssh-late-handshake") {
            if (handshakeStarted.await(1, TimeUnit.SECONDS)) Thread.sleep(350)
            handshakeReleased.countDown()
        }
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns
                ProbeOutcome(null, "default", emptyList(), "192.0.2.10", 0)
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } answers {
                handshakeStarted.countDown()
                while (handshakeReleased.count != 0L) {
                    try { handshakeReleased.await() } catch (_: InterruptedException) { /* Delayed handshake ignores interruption. */ }
                }
                session
            }
            val result = runCancellableSshOp(100) { sessionRef ->
                execOneShot(context, "build.example", 22, "builder", SshAuth(password = "unused"),
                    "printf should-not-run", 1000, sessionRef)
            }
            assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
            assertFalse("A late handshake must not execute a command after cancellation", commandStarted.get())
            assertEquals("The late session must still be closed", true, disconnected.get())
        } finally {
            handshakeReleased.countDown()
            releaseHandshake.join(1000)
            unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        }
    }

    @Test(timeout = 4000)
    fun `timeout disconnects SSH before waiting for IO that ignores interruption`() = runBlocking {
        val ioStarted = CountDownLatch(1)
        val ioReleased = CountDownLatch(1)
        val rescueNeeded = AtomicBoolean(false)
        val session = mockk<Session>()
        every { session.disconnect() } answers { ioReleased.countDown() }
        // Native socket reads can ignore Thread.interrupt(). The daemon watchdog only
        // makes the pre-fix failure bounded; session.disconnect is the intended release.
        val rescue = thread(isDaemon = true, name = "ssh-test-rescue") {
            if (ioStarted.await(1, TimeUnit.SECONDS) && !ioReleased.await(700, TimeUnit.MILLISECONDS)) {
                rescueNeeded.set(true)
                ioReleased.countDown()
            }
        }
        try {
            val result = runCancellableSshOp(150) { sessionRef ->
                sessionRef.set(session)
                runInterruptible(Dispatchers.IO) {
                    ioStarted.countDown()
                    while (ioReleased.count != 0L) {
                        try { ioReleased.await() } catch (_: InterruptedException) { /* Native IO ignores interruption. */ }
                    }
                    buildJsonObject { put("success", true) }
                }
            }
            assertEquals("The blocking operation must actually start", 0L, ioStarted.count)
            assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
            assertFalse("Timeout must disconnect the live session without waiting for an external rescue", rescueNeeded.get())
        } finally {
            ioReleased.countDown()
            rescue.join(1000)
        }
    }
}
