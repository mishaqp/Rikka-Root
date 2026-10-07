package me.rerere.rikkahub.root

import org.junit.Assert.*
import org.junit.Test

class RootCommandGuardTest {
    @Test fun `blocks obvious Android system destruction`() {
        listOf("rm -rf /", "rm -rf /system", "rm -rf /vendor/app", "rm -rf /data", "rm -rf /data/system", "rm -rf '/system'", "rm -rf /data/", "mkfs.ext4 /dev/block/mmcblk0", "dd if=/tmp/x of=/dev/block/by-name/boot", "echo x > /dev/block/by-name/boot", "dd if=/tmp/x of=\"/dev/block/by-name/boot\"", "echo x > '/dev/block/by-name/boot'", "reboot", "sh -c 'reboot'", "echo x | base64 -d | sh").forEach {
            assertNotNull("must block: $it", RootCommandGuard.check(it))
        }
    }

    @Test fun `allows ordinary diagnostics and targeted work`() {
        listOf("id -u", "getprop ro.build.version.release", "pm list packages", "dumpsys battery", "echo reboot", "rm -f /data/local/tmp/root-test.txt").forEach {
            assertNull("must allow: $it", RootCommandGuard.check(it))
        }
    }
}
