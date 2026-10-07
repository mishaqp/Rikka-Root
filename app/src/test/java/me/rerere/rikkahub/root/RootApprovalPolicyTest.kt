package me.rerere.rikkahub.root

import org.junit.Assert.*
import org.junit.Test

class RootApprovalPolicyTest {
    @Test fun combinedBooleanOutputOptionsRecognizeOnlyLiteralDestinations() {
        listOf(
            "curl -so /dev/block/by-name/boot https://example.test/image",
            "curl -sLo/dev/block/by-name/boot https://example.test/image",
            "wget -qO /dev/block/by-name/boot https://example.test/image",
            "wget -cqO/dev/block/by-name/boot https://example.test/image",
            "cp -ft /dev/block/by-name image",
            "mv -ft/dev/block/by-name image",
            "install -vt /dev/block/by-name image",
        ).forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
        listOf(
            "curl -Xo /dev/block/by-name/boot https://example.test/image",
            "curl -bo /dev/block/by-name/boot https://example.test/image",
            "wget -UO /dev/block/by-name/boot https://example.test/image",
            "curl -so \"${'$'}TARGET\" https://example.test/image",
            "wget -qO\"${'$'}TARGET\" https://example.test/image",
            "cp -ft /sdcard /dev/block/by-name/boot",
            "cp -ft \"${'$'}TARGET\" /dev/block/by-name/boot",
        ).forEach {
            assertNull("must stay automatic: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun copyDestinationsExcludeOuterRedirectionsAndDescriptors() {
        assertNotNull(RootApprovalPolicy.reason("cp image /dev/block/by-name/boot >/sdcard/log"))
        assertNotNull(RootApprovalPolicy.reason("cp image /dev/block/by-name/boot 2>&1"))
        assertNull(RootApprovalPolicy.reason("cp /dev/block/by-name/boot /sdcard/boot.img 2>&1"))
        assertNull(RootApprovalPolicy.reason("cp image /dev/block/by-name/boot '2'>/sdcard/log"))
    }

    @Test fun literalCopyMoveInstallBlockDestinationsRequireApproval() {
        listOf(
            "cp image /dev/block/by-name/boot",
            "mv -f image '/dev/block/by-name/boot'",
            "install -m 600 image /dev/mmcblk0",
            "cp \"${'$'}SOURCE\" /dev/block/by-name/boot",
            "cp -t /dev/block/by-name image",
            "mv -t/dev/block/by-name image",
            "install --target-directory /dev/block/by-name image",
            "cp --target-directory='/dev/block/by-name' image",
            "mv --target-directory=/dev/block/by-name image",
            "install -t /dev/block/by-name image",
        ).forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun copyingBlockSourcesToOrdinaryOrOpaqueDestinationsStaysAutomatic() {
        listOf(
            "cp /dev/block/by-name/boot /sdcard/boot.img",
            "dd if=/dev/block/by-name/boot of=/sdcard/boot.img",
            "cp -t /sdcard /dev/block/by-name/boot",
            "mv --target-directory=/data/local/tmp /dev/block/by-name/boot",
            "install --target-directory /sdcard /dev/block/by-name/boot",
            "cp /dev/block/by-name/boot \"${'$'}TARGET\"",
            "cp -t \"${'$'}TARGET\" /dev/block/by-name/boot",
            "mv --target-directory=\"${'$'}TARGET\" /dev/block/by-name/boot",
            "install --target-directory \"${'$'}TARGET\" /dev/block/by-name/boot",
            "cp image \"${'$'}TARGET\"",
            "sh -c 'cp image /dev/block/by-name/boot'",
        ).forEach {
            assertNull("must stay automatic: $it", RootApprovalPolicy.reason(it))
        }
    }

    @Test fun forcedOverwriteRedirectRequiresApprovalButQuotedOperatorIsInert() {
        assertNotNull(RootApprovalPolicy.reason("printf x >| /dev/block/by-name/boot"))
        assertNotNull(RootApprovalPolicy.reason("printf x >|'/dev/block/by-name/boot'"))
        assertNull(RootApprovalPolicy.reason("printf '%s' '>|' '/dev/block/by-name/boot'"))
        assertNull(RootApprovalPolicy.reason("printf x >| \"${'$'}TARGET\""))
    }

    @Test fun literalDownloaderOutputPathsRequireApproval() {
        listOf(
            "curl -o /dev/block/by-name/boot https://example.test/image",
            "curl -o/dev/block/by-name/boot https://example.test/image",
            "curl --output /dev/block/by-name/boot https://example.test/image",
            "curl --output=/dev/block/by-name/boot https://example.test/image",
            "wget -O /dev/block/by-name/boot https://example.test/image",
            "wget -O/dev/block/by-name/boot https://example.test/image",
            "wget --output-document=/dev/block/by-name/boot https://example.test/image",
            "wget --output-document /dev/block/by-name/boot https://example.test/image",
            "wget -o /dev/block/by-name/boot https://example.test/image",
            "wget --output-file=/dev/block/by-name/boot https://example.test/image",
        ).forEach {
            assertNotNull("must require approval: $it", RootApprovalPolicy.reason(it))
        }
        listOf(
            "curl -o /sdcard/image https://example.test/image",
            "curl -o \"${'$'}TARGET\" https://example.test/image",
            "wget --output-document=\"${'$'}TARGET\" https://example.test/image",
            "curl -O https://example.test/dev/block/by-name/boot",
            "echo 'curl -o /dev/block/by-name/boot'",
        ).forEach {
            assertNull("must stay automatic: $it", RootApprovalPolicy.reason(it))
        }
    }

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
