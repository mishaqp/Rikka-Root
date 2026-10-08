package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class LocalToolPermissionsTest {
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
}
