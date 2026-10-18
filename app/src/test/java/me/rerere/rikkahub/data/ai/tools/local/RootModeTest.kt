package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.pages.assistant.detail.mergeToolUiChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModeTest {
    private val chosen = listOf(LocalToolOption.TimeInfo, LocalToolOption.AlarmClock, LocalToolOption.Files,
        LocalToolOption.Browser, LocalToolOption.AskUser, LocalToolOption.Clipboard, LocalToolOption.ScreenAutomation)

    @Test fun `off keeps the assistant's own tools`() {
        assertEquals(chosen, RootMode.effectiveOptions(chosen, rootMode = false, rootStatus = RootStatus.READY))
    }

    @Test fun `on replaces phone tools with root and keeps everything a shell cannot do`() {
        val effective = RootMode.effectiveOptions(chosen, rootMode = true, rootStatus = RootStatus.READY)
        assertEquals(listOf(LocalToolOption.Browser, LocalToolOption.AskUser, LocalToolOption.Clipboard, LocalToolOption.Root), effective)
        // Root is advertised once even when the assistant already had it.
        assertEquals(1, RootMode.effectiveOptions(chosen + LocalToolOption.Root, true, RootStatus.READY).count { it == LocalToolOption.Root })
    }

    @Test fun `not yet probed root is optimistic, a definite failure restores normal tools`() {
        assertTrue(RootMode.isActive(true, RootStatus.UNCHECKED))
        assertTrue(RootMode.isActive(true, RootStatus.TIMED_OUT))
        for (missing in listOf(RootStatus.UNAVAILABLE, RootStatus.NOT_ROOT, RootStatus.DENIED_OR_FAILED)) {
            assertFalse(RootMode.isActive(true, missing))
            assertEquals(chosen, RootMode.effectiveOptions(chosen, true, missing))
        }
    }

    @Test fun `guide is added only to root_exec and keeps its own prompt`() {
        val root = Tool("root_exec", "d", systemPrompt = { _, _ -> "own" }, execute = { emptyList() })
        val other = Tool("browser_open", "d", execute = { emptyList() })
        val prompt = RootMode.withGuide(root).systemPrompt(Model(), emptyList())
        assertTrue(prompt.startsWith("own\n"))
        assertTrue(prompt.contains("SET_ALARM"))
        assertEquals("", RootMode.withGuide(other).systemPrompt(Model(), emptyList()))
    }

    @Test fun `guide commands are neither blocked nor treated as protected`() {
        val commands = listOf(
            "date '+%Y-%m-%d %H:%M:%S %Z'",
            "am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7 --ei android.intent.extra.alarm.MINUTES 30 --es android.intent.extra.alarm.MESSAGE 'Подпись' --ez android.intent.extra.alarm.SKIP_UI true",
            "am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 90 --ez android.intent.extra.alarm.SKIP_UI true",
            "am start -a android.intent.action.SHOW_ALARMS",
            "dumpsys alarm | grep -A6 deskclock", "pm list packages deskclock",
            "content query --uri content://com.android.contacts/data/phones --projection display_name:data1 --sort '_id DESC'",
            "pm list packages -3", "monkey -p com.example -c android.intent.category.LAUNCHER 1", "am force-stop com.example",
            "am start -a android.intent.action.VIEW -d 'https://example.com'",
            "mkdir -p /sdcard/Documents/Notes && printf '%s\\n' 'строка 1' > '/sdcard/Documents/Notes/Имя.md'",
            "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'текст'",
            "cmd notification post -S bigtext -t 'Заголовок' rikka 'Текст'",
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/a.png'",
            "dumpsys battery", "df -h /data /sdcard", "getprop ro.product.model",
            "cmd wifi status", "svc wifi disable", "svc data enable", "cmd bluetooth_manager enable",
            "cmd media_session volume --stream 3 --set 8", "media volume --stream 3 --set 8",
            "settings put system screen_brightness_mode 0", "settings put system screen_brightness 128",
            "input tap 100 200", "input swipe 100 800 100 200 300", "input text 'abc'", "input keyevent KEYCODE_HOME",
            "uiautomator dump /sdcard/window.xml >/dev/null && cat /sdcard/window.xml", "screencap -p /sdcard/Pictures/screen.png",
            "content query --uri content://com.android.contacts/data/phones --projection display_name:data1",
            "content query --uri content://sms/inbox --projection address:date:body --sort 'date DESC' | head -n 40",
            "content query --uri content://call_log/calls --projection number:name:date:type --sort 'date DESC' | head -n 40",
        )
        for (command in commands) {
            assertNull(command, RootCommandGuard.check(command))
            assertNull(command, HardlineCommandGuard.checkCommand(command))
            assertNull(command, RootApprovalPolicy.reason(command))
        }
    }

    @Test fun `tools screen edits carry the switch and keep other fresh fields`() {
        val before = Assistant(rootMode = false)
        val fresh = before.copy(name = "Новое имя")
        val merged = mergeToolUiChanges(fresh, before, before.copy(rootMode = true))
        assertTrue(merged.rootMode)
        assertEquals("Новое имя", merged.name)
        assertFalse(mergeToolUiChanges(merged, merged, merged.copy(rootMode = false)).rootMode)
    }
}
