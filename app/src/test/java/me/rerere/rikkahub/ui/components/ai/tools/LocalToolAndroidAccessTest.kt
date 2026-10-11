package me.rerere.rikkahub.ui.components.ai.tools

import android.Manifest
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolAndroidAccessTest {
    @Test
    fun calendarNeedsReadAndWriteAccess() {
        val access = localToolAndroidRuntimeAccess(LocalToolOption.Calendar, 36) { it == Manifest.permission.READ_CALENDAR }
        assertEquals(2, access.size)
        assertEquals(1, access.count { it.required && !it.granted })
    }

    @Test
    fun approximateLocationAllowsTheRequiredAccess() {
        val access = localToolAndroidRuntimeAccess(LocalToolOption.Location, 36) { it == Manifest.permission.ACCESS_COARSE_LOCATION }
        assertEquals(2, access.size)
        assertTrue(access.filter { it.required }.all { it.granted })
        assertFalse(access.last().required)
    }

    @Test
    fun wifiStillRequiresPreciseLocation() {
        val access = localToolAndroidRuntimeAccess(LocalToolOption.WifiInfo, 36) { it == Manifest.permission.ACCESS_COARSE_LOCATION }
        assertEquals(2, access.size)
        assertEquals(1, access.count { it.required && !it.granted })
    }

    @Test
    fun localNetworkAccessStartsAtAndroid37() {
        assertTrue(localToolAndroidRuntimeAccess(LocalToolOption.Ssh, 36) { false }.isEmpty())
        assertEquals(1, localToolAndroidRuntimeAccess(LocalToolOption.Ssh, 37) { false }.size)
    }
    @Test
    fun workflowBluetoothOnlyUsesItsDedicatedButton() {
        val access = listOf(LocalToolAndroidAccess("Будильники", true, false), LocalToolAndroidAccess("Bluetooth", false, false))
        assertFalse(androidAccessRequestNeeded(LocalToolOption.Workflows, access))
    }

    @Test
    fun exactAlarmAccessRemainsRequestableWhenOptional() {
        val access = listOf(LocalToolAndroidAccess("Будильники", false, false), LocalToolAndroidAccess("Bluetooth", true, false))
        assertTrue(androidAccessRequestNeeded(LocalToolOption.Workflows, access))
        assertTrue(androidAccessRequestNeeded(LocalToolOption.CronJobs, access.take(1)))
    }

    @Test
    fun locationDoesNotRequestOptionalPrecisionAfterApproximateGrant() {
        val access = listOf(LocalToolAndroidAccess("Примерное", true, true), LocalToolAndroidAccess("Точное", false, false))
        assertFalse(androidAccessRequestNeeded(LocalToolOption.Location, access))
    }

    @Test
    fun grantButtonCannotInstallTermuxOrAddMissingHardware() {
        val unavailable = listOf(LocalToolAndroidAccess("Разрешение", false, true), LocalToolAndroidAccess("Установлено или оборудование", false, true))
        assertFalse(androidAccessRequestNeeded(LocalToolOption.Termux, unavailable))
        assertFalse(androidAccessRequestNeeded(LocalToolOption.Torch, unavailable))
        assertFalse(androidAccessRequestNeeded(LocalToolOption.Nfc, unavailable.takeLast(1)))
        val available = unavailable.dropLast(1) + LocalToolAndroidAccess("Установлено или оборудование", true, true)
        assertTrue(androidAccessRequestNeeded(LocalToolOption.Termux, available))
        assertTrue(androidAccessRequestNeeded(LocalToolOption.Torch, available))
    }

    @Test
    fun optionalDndAccessCanStillBeRequestedForVolume() {
        assertTrue(androidAccessRequestNeeded(LocalToolOption.Volume, listOf(LocalToolAndroidAccess("Не беспокоить", false, false))))
    }

}
