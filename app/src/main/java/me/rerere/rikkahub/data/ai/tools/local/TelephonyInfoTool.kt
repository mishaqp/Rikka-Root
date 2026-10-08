// Adapted from ExTV/rikkahub-agent, local/TelephonyInfoTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private fun networkTypeName(type: Int): String = when (type) {
    1 -> "GPRS"
    2 -> "EDGE"
    3 -> "UMTS"
    4 -> "CDMA"
    5 -> "EVDO_0"
    6 -> "EVDO_A"
    7 -> "1xRTT"
    8 -> "HSDPA"
    9 -> "HSUPA"
    10 -> "HSPA"
    12 -> "EVDO_B"
    13 -> "LTE"
    14 -> "EHRPD"
    15 -> "HSPAP"
    16 -> "GSM"
    17 -> "TD_SCDMA"
    18 -> "IWLAN"
    19 -> "LTE_CA"
    20 -> "NR"
    else -> "unknown"
}

fun telephonyInfoTool(context: Context): Tool = Tool(
    name = "get_telephony_info",
    description = "Read SIM availability, carrier, network country and radio type. Does not read phone numbers, calls or messages.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    execute = { deviceToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return@deviceToolResult deviceToolError("Разрешите доступ к состоянию телефона при включении «Мобильная сеть» в настройках ассистента.", Manifest.permission.READ_PHONE_STATE)
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?: return@deviceToolResult deviceToolError("Служба мобильной сети отсутствует на этом устройстве.")
        buildJsonObject {
            put("has_sim", tm.simState == TelephonyManager.SIM_STATE_READY)
            put("sim_operator", tm.simOperator.orEmpty())
            put("sim_country", tm.simCountryIso.orEmpty())
            put("network_operator", tm.networkOperator.orEmpty())
            put("network_country", tm.networkCountryIso.orEmpty())
            put("network_type", networkTypeName(tm.dataNetworkType))
            put("phone_type", when (tm.phoneType) { 0 -> "none"; 1 -> "gsm"; 2 -> "cdma"; 3 -> "sip"; else -> "unknown" })
        }
    } },
)
