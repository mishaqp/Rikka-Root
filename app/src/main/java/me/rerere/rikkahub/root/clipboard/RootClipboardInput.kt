package me.rerere.rikkahub.root.clipboard

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.root.RootProcessResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** A clipboard transaction is one RootShellManager operation, including in serialized headless mode. */
internal fun interface RootClipboardInput {
    /** Null on success, otherwise a payload-free diagnostic code. */
    suspend fun replaceText(
        text: String,
        authorizeCommand: suspend (String) -> Boolean,
        launchRoot: suspend (String, Int) -> RootProcessResult,
    ): String?
}

internal class AndroidRootClipboardInput(private val context: Context) : RootClipboardInput {
    override suspend fun replaceText(
        text: String,
        authorizeCommand: suspend (String) -> Boolean,
        launchRoot: suspend (String, Int) -> RootProcessResult,
    ): String? = transactions.withLock {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > RootClipboardBridge.MAX_TEXT_BYTES) return@withLock "root_clipboard_text_too_large"
        coroutineScope {
            val endpoint = "rikka-root-paste-${UUID.randomUUID()}"
            val server = try { LocalServerSocket(endpoint) } catch (_: Exception) {
                return@coroutineScope "root_clipboard_unavailable"
            }
            var socket: LocalSocket? = null
            val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process / " +
                "${RootClipboardBridge::class.java.name} ${quote(endpoint)} ${Process.myUid()} ${Process.myUid() / 100_000}"
            // Parent cancellation closes the socket first. The helper can then restore the clipboard
            // before RootShellManager reaps it; the same bounded operation never opens a second su.
            val root = async(NonCancellable) {
                try { launchRoot(command, 45_000) }
                catch (_: Exception) { RootProcessResult(error = "root_clipboard_unavailable") }
                finally { runCatching { server.close() } }
            }
            val connection = async(Dispatchers.IO) {
                try { runInterruptible { server.accept() } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            }
            var failure: String? = null
            var editsStarted = false
            try {
                socket = select {
                    connection.onAwait { it }
                    root.onAwait { result ->
                        failure = result.error ?: "root_clipboard_unavailable"
                        null
                    }
                }
                val peer = socket ?: return@coroutineScope (failure ?: "root_clipboard_unavailable")
                // Endpoint randomness is supplementary. Authenticate the actual Unix peer UID.
                if (peer.peerCredentials.uid != 0) return@coroutineScope "root_clipboard_unavailable"
                peer.soTimeout = 10_000
                val input = DataInputStream(peer.inputStream)
                val output = DataOutputStream(peer.outputStream)
                if (io { input.readUTF() } != RootClipboardBridge.PROTOCOL) return@coroutineScope "root_clipboard_unavailable"
                io {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
                bytes.fill(0)
                while (true) {
                    when (io { input.readUTF() }) {
                        "authorize" -> {
                            val requested = io { input.readUTF() }
                            val allowed = requested in INPUT_COMMANDS && authorizeCommand(requested)
                            if (allowed) editsStarted = true
                            io { output.writeBoolean(allowed); output.flush() }
                        }
                        "result" -> {
                            val result = io { input.readUTF() }
                            val completed = withContext(NonCancellable) { root.await() }
                            return@coroutineScope completed.error ?: if (completed.exitCode != 0) "root_input_failed"
                                else result.takeUnless { it == "ok" }
                        }
                        else -> return@coroutineScope "root_clipboard_unavailable"
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (editsStarted) "root_clipboard_transaction_failed" else "root_clipboard_unavailable"
            } finally {
                bytes.fill(0)
                runCatching { socket?.close() }
                runCatching { server.close() }
                connection.cancel()
                withContext(NonCancellable) {
                    // A helper EOF runs its finally before exit. The root transport remains bounded.
                    runCatching { root.await() }
                }
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { runInterruptible { block() } }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val transactions = Mutex()
        private val INPUT_COMMANDS = setOf("input keycombination 113 29", "input keyevent 67", "input keyevent 279")
    }
}
