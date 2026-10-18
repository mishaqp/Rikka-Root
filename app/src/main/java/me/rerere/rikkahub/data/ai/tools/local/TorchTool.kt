// Adapted from ExTV/rikkahub-agent, local/TorchTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun torchTool(context: Context): Tool = Tool(
    name = "set_torch",
    description = "Включить или выключить вспышку телефона по on. Используйте для освещения; для снимка экрана вызовите take_screenshot. Нужны разрешение камеры и устройство со вспышкой. Возвращает success и фактический запрошенный on; при недоступной камере — ошибку с подсказкой.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("on", buildJsonObject { put("type", "boolean"); put("description", "Включить вспышку: true; выключить: false.") })
    }, required = listOf("on")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val primitive = (input as? JsonObject)?.get("on") as? JsonPrimitive
        val on = primitive?.takeUnless { it.isString }?.booleanOrNull
            ?: return@deviceToolResult deviceToolError("Параметр on должен быть true или false.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return@deviceToolResult deviceToolError("Разрешите доступ к камере при включении «Фонарик» в настройках ассистента.", Manifest.permission.CAMERA)
        }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return@deviceToolResult deviceToolError("Служба камеры недоступна. Закройте приложение камеры и проверьте разрешение камеры в настройках функции.")
        val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
            .filter { (_, info) -> info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        val camera = cameras.firstOrNull { (_, info) -> info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: cameras.firstOrNull()
            ?: return@deviceToolResult deviceToolError("На устройстве нет вспышки для фонарика. Используйте устройство со вспышкой.")
        manager.setTorchMode(camera.first, on)
        buildJsonObject { put("success", true); put("on", on) }
    } },
)
