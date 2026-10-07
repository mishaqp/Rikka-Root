package me.rerere.rikkahub.root

import org.junit.Assert.*
import org.junit.Test

class RootApprovalPolicyTest {
    @Test fun heredocPayloadIsOpaqueButOuterRedirectionAndFollowingCommandsAreVisible() {
        assertNull(RootApprovalPolicy.reason("sh <<'EOF'\nrm -rf /system\nEOF"))
        assertNotNull(RootApprovalPolicy.reason("cat <<EOF >/dev/block/by-name/boot\nhidden input\nEOF"))
        assertNotNull(RootApprovalPolicy.reason("sh <<EOF\nhidden script\nEOF\nrm -rf /data"))
    }

    @Test fun visibleBootloaderLockAndVerifiedBootDisableRequireApproval() {
        listOf("fastboot flashing lock", "fastboot flashing lock_critical", "fastboot oem lock",
            "avbctl disable-verity", "avbctl disable-verification").forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
        assertNull(RootApprovalPolicy.reason("avbctl get-verity"))
    }

    @Test fun knownLiteralWrappersAndSimpleGroupsKeepVisibleOperationsVisible() {
        listOf("command toybox rm -rf /data", "exec busybox mkfs.ext4 image",
            "env CHECK=1 command /system/bin/setenforce 0", "(rm -rf /data)",
            "(echo 'rm -rf /data'); mount -o remount,rw /system").forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
        assertNull(RootApprovalPolicy.reason("env CHECK=1 sh -c 'rm -rf /data'"))
        assertNull(RootApprovalPolicy.reason("(echo 'rm -rf /data')"))
    }

    @Test fun protectedWholeDeletionRequiresApproval() {
        listOf("rm -rf /", "rm -r -f '/data/'", "rm --recursive --force /system",
            "rm -rf /sdcard/*", "rm -rf /storage/emulated/0", "rm -rf /data/media/0/.*",
            "rm -rf /data/local/tmp/test /system", "/system/bin/rm -rf //data",
            "toybox rm -rf /sdcard", "rm -rf /sdcard/{*,.*}").forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun visibleBlockWritesAndFormatsRequireApproval() {
        listOf("dd if=/tmp/image of=/dev/block/by-name/boot", "dd of='/dev/mmcblk0'",
            "printf bytes >/dev/block/by-name/boot", "echo bytes >> /dev/block/by-name/system",
            "cat image | tee /dev/block/by-name/boot", "tee -a /dev/nvme0n1",
            "\"${'$'}COMMAND\" >/dev/block/by-name/boot",
            "mkfs.ext4 /tmp/image", "busybox mkfs /dev/block/foo").forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun visibleBootloaderResetSelinuxAndRemountRequireApproval() {
        listOf("fastboot flash boot image", "fastboot flashing unlock", "fastboot oem unlock",
            "fastboot --set-active=b", "bootctl set-active-boot-slot 1", "reboot bootloader",
            "recovery --wipe_data", "am broadcast -a android.intent.action.MASTER_CLEAR",
            "cmd recovery wipe", "setenforce 0", "mount -o remount,rw /system",
            "mount -oremount,ro /vendor", "mount -o remount /data",
            "mount -o remount /dev/block/by-name/system /").forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun ordinaryWorkAndOpaquePayloadsStayAutomatic() {
        listOf("id -u", "rm -rf /data/local/tmp/test", "rm -f /sdcard/file",
            "dd if=/dev/block/by-name/boot of=/sdcard/boot.img", "setenforce 1",
            "mount -o remount,rw /mnt/scratch", "echo 'rm -rf /system'",
            "echo '>' '/dev/block/by-name/boot'",
            "sh -c 'rm -rf /system'", "eval 'setenforce 0'", "sh /sdcard/script.sh",
            "rm -rf \"${'$'}TARGET\"", "printf '%s' \"${'$'}(dd of=/dev/block/foo)\"",
            "sh <<'EOF'\nrm -rf /system\nEOF").forEach {
            assertNull("must stay automatic: $it", RootApprovalPolicy.reason(it))
        }
        assertNotNull(RootApprovalPolicy.reason("sh -c 'id'; rm -rf /data"))
    }

    @Test fun rulesHaveDistinctIDsAndMatchingExamples() {
        assertEquals(setOf("protected_delete", "filesystem_format", "block_write", "bootloader", "factory_reset", "selinux_disable", "verified_boot_disable", "protected_remount"),
            RootApprovalPolicy.rules.map { it.id }.toSet())
        assertEquals(RootApprovalPolicy.rules.size, RootApprovalPolicy.rules.map { it.id }.distinct().size)
        RootApprovalPolicy.rules.forEach { rule ->
            assertTrue(rule.description.isNotBlank())
            assertTrue(rule.examples.isNotEmpty())
            rule.examples.forEach { assertNotNull(RootApprovalPolicy.reason(it)) }
        }
    }
}
