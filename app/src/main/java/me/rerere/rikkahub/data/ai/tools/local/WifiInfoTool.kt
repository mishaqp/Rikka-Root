// Adapted from ExTV/rikkahub-agent, local/WifiInfoTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.net.Inet4Address

fun wifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info",
    description = "Read active Wi-Fi connection, SSID/BSSID, IP, RSSI, frequency and link speed. Android may redact identifiers; ssid_redacted does not mean disconnected.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    execute = { deviceToolResult {
        if (missingLocalToolPermissions(context, LocalToolOption.WifiInfo).isNotEmpty()) {
            return@deviceToolResult deviceToolError("Для SSID/BSSID разрешите точную геолокацию при включении «Wi-Fi» в настройках ассистента.", android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return@deviceToolResult deviceToolError("Служба подключения к сети недоступна.")
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        // Never use networkId/SSID as a connectivity test: Android redacts them even on Wi-Fi.
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return@deviceToolResult buildJsonObject { put("connected", false) }
        }
        buildJsonObject {
            put("connected", true)
            @Suppress("DEPRECATION")
            val info = appContext.getSystemService(WifiManager::class.java)?.connectionInfo
            val ssid = info?.ssid.orEmpty().removeSurrounding("\"")
            val redacted = ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x"
            put("ssid_redacted", redacted)
            if (!redacted) put("ssid", ssid)
            else put("note", "Android скрыл имя сети. Проверьте точную геолокацию и системный переключатель местоположения.")
            info?.let {
                val bssid = it.bssid.orEmpty()
                if (bssid.isNotBlank() && bssid != "02:00:00:00:00:00") put("bssid", bssid)
                put("link_speed_mbps", it.linkSpeed)
                put("rssi", it.rssi)
                put("frequency_mhz", it.frequency)
            }
            network?.let { cm.getLinkProperties(it) }?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress?.let { put("ip", it) }
        }
    } },
)
