package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class PhoneAuditServicesRegressionTest {
    private val context = mockk<Context>(relaxed = true)
    private suspend fun result(tool: Tool, input: JsonObject = buildJsonObject {}): JsonObject =
        Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject

    @Test fun unavailableBatteryHasAnActionableRussianError() = runBlocking {
        every { context.registerReceiver(null, any()) } returns null
        val payload = result(batteryTool(context))
        val error = payload.getValue("error").jsonPrimitive.content
        assertTrue(error, error.any { it in 'А'..'я' })
    }

    @Test fun unavailableAudioHasAnActionableRussianError() = runBlocking {
        every { context.getSystemService(AudioManager::class.java) } returns null
        val payload = result(audioInfoTool(context))
        val error = payload.getValue("error").jsonPrimitive.content
        assertTrue(error, error.any { it in 'А'..'я' })
    }

    @Test fun approximateWifiReturnsSignalAndIpWithoutNetworkIdentifiers() = runBlocking {
        every { context.applicationContext } returns context
        every { context.checkPermission(any(), any(), any()) } answers {
            if (firstArg<String>() == Manifest.permission.ACCESS_COARSE_LOCATION) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        }
        val connectivity = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        val links = mockk<LinkProperties>()
        val link = mockk<LinkAddress>()
        every { link.address } returns InetAddress.getByName("192.0.2.10")
        every { links.linkAddresses } returns listOf(link)
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns caps
        every { connectivity.getLinkProperties(network) } returns links
        val wifi = mockk<WifiManager>()
        val info = mockk<WifiInfo>()
        every { context.getSystemService(WifiManager::class.java) } returns wifi
        every { wifi.connectionInfo } returns info
        every { info.ssid } returns "\"Private network\""
        every { info.bssid } returns "11:22:33:44:55:66"
        every { info.rssi } returns -55
        every { info.linkSpeed } returns 144
        every { info.frequency } returns 5200
        val payload = result(wifiInfoTool(context))
        assertFalse(payload.toString(), payload.containsKey("error"))
        assertEquals(true, payload.getValue("connected").jsonPrimitive.boolean)
        assertEquals(-55, payload.getValue("rssi").jsonPrimitive.int)
        assertEquals("192.0.2.10", payload.getValue("ip").jsonPrimitive.content)
        assertEquals(true, payload.getValue("ssid_redacted").jsonPrimitive.boolean)
        assertFalse(payload.toString(), payload.containsKey("ssid") || payload.containsKey("bssid"))
    }

    @Test fun approximateLocationDoesNotBlockWifiAtTheRuntimePermissionGate() {
        assertTrue(missingRuntimePermissions(LocalToolOption.WifiInfo, 33) {
            it == Manifest.permission.ACCESS_COARSE_LOCATION
        }.isEmpty())
    }

    @Test fun wifiLocationRevocationIsDetectedBeforeAndAfterAnApproximateGrant() {
        var granted = emptySet<String>()
        assertTrue(missingRuntimePermissions(LocalToolOption.WifiInfo, 33) { it in granted }.isNotEmpty())
        granted = setOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(missingRuntimePermissions(LocalToolOption.WifiInfo, 33) { it in granted }.isEmpty())
        granted = emptySet()
        assertTrue(missingRuntimePermissions(LocalToolOption.WifiInfo, 33) { it in granted }.isNotEmpty())
    }

    @Test fun enablingSensorsDoesNotDemandActivityRecognition() {
        assertTrue(localToolRuntimePermissions(LocalToolOption.Sensors, 33).isEmpty())
        assertTrue(missingRuntimePermissions(LocalToolOption.Sensors, 33) { false }.isEmpty())
    }

    @Test fun onlyStepCounterRequiresActivityRecognitionAtReadTime() = runBlocking {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        val manager = mockk<SensorManager>()
        every { context.getSystemService(SensorManager::class.java) } returns manager
        every { manager.getDefaultSensor(any()) } returns null
        val light = result(readSensorTool(context), buildJsonObject { put("type", "light") })
        assertFalse(light.toString(), light.toString().contains(Manifest.permission.ACTIVITY_RECOGNITION))
        assertTrue(light.toString(), light.getValue("error").jsonPrimitive.content.contains("отсутствует"))
        val steps = result(readSensorTool(context), buildJsonObject { put("type", "step_counter") })
        assertTrue(steps.toString(), steps.toString().contains(Manifest.permission.ACTIVITY_RECOGNITION))
    }

    @Test fun stepCounterPermissionErrorPointsToAndroidAppPermissions() = runBlocking {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        val payload = result(readSensorTool(context), buildJsonObject { put("type", "step_counter") })
        val error = payload.getValue("error").jsonPrimitive.content
        for (label in listOf("Android", "Настройки", "Приложения", "Rikka-Root", "Разрешения", "Физическая активность")) {
            assertTrue("The permission error must give the real Android path ($label): $error", error.contains(label))
        }
        assertFalse(error, error.contains("при включении"))
        assertEquals(Manifest.permission.ACTIVITY_RECOGNITION, payload.getValue("required_permission").jsonPrimitive.content)
    }
}
