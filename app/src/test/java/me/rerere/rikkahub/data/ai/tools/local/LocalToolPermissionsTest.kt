package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class LocalToolPermissionsTest {
    @Test fun personalFeaturesRequestOnlyTheirOwnRuntimePermissions() {
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), localToolRuntimePermissions(LocalToolOption.Contacts, 37))
        assertEquals(listOf(Manifest.permission.READ_CALL_LOG), localToolRuntimePermissions(LocalToolOption.CallLog, 37))
        assertEquals(listOf(Manifest.permission.READ_SMS), localToolRuntimePermissions(LocalToolOption.SmsInbox, 37))
        assertEquals(listOf(Manifest.permission.SEND_SMS), localToolRuntimePermissions(LocalToolOption.SmsSend, 37))
        assertEquals(listOf(Manifest.permission.CAMERA), localToolRuntimePermissions(LocalToolOption.CameraPhoto, 26))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), localToolRuntimePermissions(LocalToolOption.MicRecorder, 37))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), localToolRuntimePermissions(LocalToolOption.SpeechToText, 26))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), localToolRuntimePermissions(LocalToolOption.Location, 37))
        assertTrue(localToolRuntimePermissions(LocalToolOption.Fingerprint, 26).isEmpty())
        assertTrue(localToolRuntimePermissions(LocalToolOption.Keystore, 37).isEmpty())
    }

    @Test fun approximateLocationIsUsableAndRevocationIsDetected() {
        assertTrue(missingRuntimePermissions(LocalToolOption.Location, 37) { it == Manifest.permission.ACCESS_COARSE_LOCATION }.isEmpty())
        assertTrue(missingRuntimePermissions(LocalToolOption.Location, 26) { it == Manifest.permission.ACCESS_FINE_LOCATION }.isEmpty())
        assertEquals(2, missingRuntimePermissions(LocalToolOption.Location, 37) { false }.size)
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), missingRuntimePermissions(LocalToolOption.WifiInfo, 37) { it == Manifest.permission.ACCESS_COARSE_LOCATION })
    }

    @Test fun wifiRequestsBothLocationPermissionsButNeverPhoneOrActivity() {
        val permissions = localToolRuntimePermissions(LocalToolOption.WifiInfo, 37)
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), permissions)
        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE), localToolRuntimePermissions(LocalToolOption.TelephonyInfo, 37))
        assertTrue(localToolRuntimePermissions(LocalToolOption.Battery, 37).isEmpty())
        assertTrue(localToolRuntimePermissions(LocalToolOption.StorageInfo, 37).isEmpty())
    }

    @Test fun permissionsNotPresentOnOlderAndroidAreNeverRequested() {
        assertTrue(localToolRuntimePermissions(LocalToolOption.Sensors, 28).isEmpty())
        assertEquals(listOf(Manifest.permission.ACTIVITY_RECOGNITION), localToolRuntimePermissions(LocalToolOption.Sensors, 29))
        assertTrue(localToolRuntimePermissions(LocalToolOption.Notification, 32).isEmpty())
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), localToolRuntimePermissions(LocalToolOption.Notification, 33))
    }

    @Test fun rootGrantIsRestrictedToOwnPackageAndSelectedFeature() {
        val command = localPermissionGrantCommand("me.mishaqp.rikkaroot.debug", LocalToolOption.WifiInfo, 37, 10)!!
        assertTrue(command.contains("pm grant --user 10 me.mishaqp.rikkaroot.debug android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(command.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(command.contains("READ_PHONE_STATE"))
        assertNull(localPermissionGrantCommand("me.app;reboot", LocalToolOption.WifiInfo, 37, 0))
        assertNull(localPermissionGrantCommand("$(reboot)", LocalToolOption.WifiInfo, 37, 0))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.WifiInfo, 37, -1))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.Root, 37, 0))
    }

    @Test fun equipmentOnlyRequestsTheRelevantPermission() {
        assertEquals(listOf(Manifest.permission.CAMERA), localToolRuntimePermissions(LocalToolOption.Torch, 37))
        listOf(LocalToolOption.Vibrate, LocalToolOption.Volume, LocalToolOption.Brightness,
            LocalToolOption.Wallpaper, LocalToolOption.Nfc).forEach {
            assertTrue(localToolRuntimePermissions(it, 37).isEmpty())
        }
        assertEquals("pm grant --user 0 me.app android.permission.CAMERA", localPermissionGrantCommand("me.app", LocalToolOption.Torch, 37, 0))
        assertEquals("appops set --user 10 me.app WRITE_SETTINGS allow", localPermissionGrantCommand("me.app", LocalToolOption.Brightness, 37, 10))
        assertNull(localPermissionGrantCommand("me.app;reboot", LocalToolOption.Brightness, 37, 0))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.Volume, 37, 0))
    }

    @Test fun packageFRequestsOnlyTheEnabledFunctionsRuntimePermission() {
        for (sdk in listOf(26, 28, 33, 36, 37)) {
            assertEquals(listOf("com.termux.permission.RUN_COMMAND"),
                localToolRuntimePermissions(LocalToolOption.Termux, sdk))
            assertEquals(if (sdk >= 37) listOf("android.permission.ACCESS_LOCAL_NETWORK") else emptyList<String>(),
                localToolRuntimePermissions(LocalToolOption.Ssh, sdk))
            assertTrue(localToolRuntimePermissions(LocalToolOption.McpControl, sdk).isEmpty())
            assertTrue(localToolRuntimePermissions(LocalToolOption.ExternalAutomation, sdk).isEmpty())
        }
    }

    @Test fun refusedAndRevokedShellPermissionsStayMissingUntilGranted() {
        val termux = "com.termux.permission.RUN_COMMAND"
        val network = "android.permission.ACCESS_LOCAL_NETWORK"
        assertEquals(listOf(termux), missingRuntimePermissions(LocalToolOption.Termux, 37) { it == network })
        assertEquals(listOf(network), missingRuntimePermissions(LocalToolOption.Ssh, 37) { it == termux })
        assertTrue(missingRuntimePermissions(LocalToolOption.Termux, 37) { it == termux }.isEmpty())
        assertTrue(missingRuntimePermissions(LocalToolOption.Ssh, 37) { it == network }.isEmpty())
        assertTrue(missingRuntimePermissions(LocalToolOption.Ssh, 36) { false }.isEmpty())
    }

    @Test fun shellRootGrantCommandsAreScopedToTheSelectedFeature() {
        assertEquals("pm grant --user 0 me.app com.termux.permission.RUN_COMMAND",
            localPermissionGrantCommand("me.app", LocalToolOption.Termux, 37, 0))
        assertEquals("pm grant --user 10 me.app android.permission.ACCESS_LOCAL_NETWORK",
            localPermissionGrantCommand("me.app", LocalToolOption.Ssh, 37, 10))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.Ssh, 36, 0))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.McpControl, 37, 0))
        assertNull(localPermissionGrantCommand("me.app", LocalToolOption.ExternalAutomation, 37, 0))
    }
}
