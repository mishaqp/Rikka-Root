package me.rerere.rikkahub.root.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RootClipboardTransactionTest {
    private class Clipboard(var value: ClipData?) : RootClipboardBridge.ClipboardAccess {
        override fun get(): ClipData? = value
        override fun set(value: ClipData?) { this.value = value }
    }
    private fun original(): ClipData = ClipData.newHtmlText("original", "secret old text", "<b>secret old text</b>").apply {
        addItem(ClipData.Item(Intent("org.test.ACTION").putExtra("key", "old value")))
    }
    private fun transaction(clipboard: Clipboard, text: String = "Привет 👩🏽‍💻", authorize: (String) -> Boolean = { true },
                            execute: (String) -> Boolean = { true }): String? =
        RootClipboardBridge.replaceText(clipboard, text, RootClipboardBridge.Commands { command ->
            when {
                !authorize(command) -> "screen_permission_revoked"
                !execute(command) -> "root_input_failed"
                else -> null
            }
        })

    @Test fun `Unicode arrives intact at paste and complete old clipboard is restored`() {
        val prior = original()
        val clipboard = Clipboard(prior)
        val commands = mutableListOf<String>()
        val text = "Привет 👩🏽‍💻\nвторая строка"
        assertNull(transaction(clipboard, text, execute = { command ->
            commands.add(command)
            if (command == "input keyevent 279") assertEquals(text, clipboard.value!!.getItemAt(0).text.toString())
            true
        }))
        assertEquals(listOf("input keycombination 113 29", "input keyevent 67", "input keyevent 279"), commands)
        assertSame(prior, clipboard.value)
        assertEquals(2, clipboard.value!!.itemCount)
        assertEquals("<b>secret old text</b>", clipboard.value!!.getItemAt(0).htmlText)
        assertEquals("old value", clipboard.value!!.getItemAt(1).intent!!.getStringExtra("key"))
    }
    @Test fun `legacy service unable to clear refuses empty clipboard before edit`() {
        val clipboard = object : RootClipboardBridge.ClipboardAccess {
            override fun get(): ClipData? = null
            override fun set(value: ClipData?) { fail("legacy empty clipboard must never be changed") }
            override fun canClear(): Boolean = false
        }
        val commands = mutableListOf<String>()
        assertEquals("root_clipboard_clear_unsupported", RootClipboardBridge.replaceText(clipboard, "Привет", RootClipboardBridge.Commands {
            commands.add(it); null
        }))
        assertTrue(commands.isEmpty())
    }
    @Test fun `empty clipboard remains empty after paste`() {
        val clipboard = Clipboard(null)
        assertNull(transaction(clipboard))
        assertNull(clipboard.value)
    }
    @Test fun `failed paste restores original clipboard`() {
        val prior = original()
        val clipboard = Clipboard(prior)
        assertEquals("root_input_failed", transaction(clipboard, execute = { it != "input keyevent 279" }))
        assertSame(prior, clipboard.value)
    }
    @Test fun `revoked permission stops before select and restores clipboard`() {
        val prior = original()
        val clipboard = Clipboard(prior)
        val executed = mutableListOf<String>()
        assertEquals("screen_permission_revoked", transaction(clipboard, authorize = { false }, execute = { executed.add(it); true }))
        assertTrue(executed.isEmpty())
        assertSame(prior, clipboard.value)
    }
    @Test fun `cancellation restores clipboard and propagates cancellation`() {
        val prior = original()
        val clipboard = Clipboard(prior)
        try {
            transaction(clipboard, execute = { throw InterruptedIOException("cancelled") })
            fail("cancellation must propagate")
        } catch (_: InterruptedIOException) { }
        assertSame(prior, clipboard.value)
    }
    @Test fun `new user clipboard during input is never overwritten by restore`() {
        val clipboard = Clipboard(original())
        val userClip = ClipData.newPlainText("user copied", "new text")
        assertNull(transaction(clipboard, execute = { command ->
            if (command == "input keyevent 279") clipboard.value = userClip
            true
        }))
        assertSame(userClip, clipboard.value)
    }
    @Test fun `content URI clipboard fails before overwrite or edit to avoid revoking its grant`() {
        val prior = ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(Uri.parse("content://photos/private")))
        val clipboard = Clipboard(prior)
        val commands = mutableListOf<String>()
        assertEquals("root_clipboard_uri_restore_unsupported", transaction(clipboard, execute = { commands.add(it); true }))
        assertSame(prior, clipboard.value)
        assertTrue(commands.isEmpty())
    }
    @Test fun `restoration failure is reported without exposing old clipboard`() {
        val prior = original()
        var value: ClipData? = prior
        val clipboard = object : RootClipboardBridge.ClipboardAccess {
            override fun get(): ClipData? = value
            override fun set(clip: ClipData?) {
                if (clip === prior) throw SecurityException("old secret must never escape")
                value = clip
            }
        }
        assertEquals("root_clipboard_restore_failed", RootClipboardBridge.replaceText(clipboard, "Привет", RootClipboardBridge.Commands { null }))
    }
    @Test fun `user copy before paste stops and leaves new clipboard`() {
        val clipboard = Clipboard(original())
        val userClip = ClipData.newPlainText("user", "new value")
        val commands = mutableListOf<String>()
        assertEquals("root_clipboard_changed", transaction(clipboard, execute = { command ->
            commands.add(command)
            if (command == "input keyevent 67") clipboard.value = userClip
            true
        }))
        assertSame(userClip, clipboard.value)
        assertFalse(commands.contains("input keyevent 279"))
    }
    @Test fun `clipboard failure before capture never edits field`() {
        val commands = mutableListOf<String>()
        val clipboard = object : RootClipboardBridge.ClipboardAccess {
            override fun get(): ClipData? = throw SecurityException("denied")
            override fun set(value: ClipData?) { fail("clipboard should not be changed") }
        }
        assertEquals("root_clipboard_unavailable", RootClipboardBridge.replaceText(clipboard, "Привет", RootClipboardBridge.Commands {
            commands.add(it); null
        }))
        assertTrue(commands.isEmpty())
    }
}
