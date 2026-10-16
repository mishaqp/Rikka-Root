package me.rerere.rikkahub.root

import org.junit.Assert.*
import org.junit.Test

class RootCommandGuardTest {
    @Test fun `blocks obvious Android system destruction`() {
        listOf("rm -rf /", "rm -rf /system", "rm -rf /vendor/app", "rm -rf /data", "rm -rf /data/system", "rm -rf '/system'", "rm -rf /data/", "mkfs.ext4 /dev/block/mmcblk0", "dd if=/tmp/x of=/dev/block/by-name/boot", "echo x > /dev/block/by-name/boot", "dd if=/tmp/x of=\"/dev/block/by-name/boot\"", "echo x > '/dev/block/by-name/boot'", "reboot", "sh -c 'reboot'", "echo x | base64 -d | sh").forEach {
            val reason = RootCommandGuard.check(it)
            assertNotNull("must block: $it", reason)
            assertTrue("Reason must explain the block in Russian: $reason", Regex("[А-Яа-яЁё]{3}").containsMatchIn(reason!!))
        }
    }

    @Test fun `blocks unquoted and combined shell command flags`() {
        listOf("sh -c reboot", "bash -lc reboot", "bash -lc 'reboot'", "bash -xec \"reboot\"",
            "sh -e -c reboot", "busybox sh -lc reboot", "toybox sh -c reboot").forEach {
            assertNotNull("must block shell payload: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `blocks power commands behind common executable wrappers`() {
        listOf("busybox reboot", "toybox reboot", "env reboot", "env PATH=/system/bin reboot",
            "nohup reboot", "timeout 5 reboot", "exec reboot", "su 0 reboot",
            "env PATH=/system/bin timeout 5 busybox reboot", "/system/bin/toybox reboot").forEach {
            assertNotNull("must block wrapped command: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `blocks command positions inside shell control structures`() {
        listOf("(reboot)", "{ reboot; }", "if true; then reboot; fi", "for x in 1; do reboot; done",
            "echo ok; reboot", "true && reboot", "false || reboot", "echo ok\nreboot").forEach {
            assertNotNull("must block shell command position: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `blocks Android power service and power control properties`() {
        listOf("svc power reboot", "svc power shutdown", "setprop sys.powerctl reboot",
            "setprop sys.powerctl shutdown", "env svc power reboot", "sh -lc 'setprop sys.powerctl reboot'").forEach {
            assertNotNull("must block Android power command: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `allows power command names used as ordinary arguments`() {
        listOf("echo reboot", "printf '%s' reboot", "echo '(reboot)'", "printf reboot", "echo svc power reboot", "getprop sys.powerctl",
            "echo setprop sys.powerctl reboot", "env PATH=/system/bin echo reboot", "busybox echo reboot").forEach {
            assertNull("must allow diagnostic: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `allows ordinary diagnostics and targeted work`() {
        listOf("id -u", "getprop ro.build.version.release", "pm list packages", "dumpsys battery", "echo reboot", "rm -f /data/local/tmp/root-test.txt", "rm -rf /system-backup; true", "rm -rf /data/local/tmp/root-test; true").forEach {
            assertNull("must allow: $it", RootCommandGuard.check(it))
        }
    }
}
