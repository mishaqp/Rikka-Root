// Adapted from ExTV/rikkahub-agent, local/SensorTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private val SENSOR_TYPES = mapOf(
    "accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE,
    "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY,
    "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD, "pressure" to Sensor.TYPE_PRESSURE,
    "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE, "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
    "step_counter" to Sensor.TYPE_STEP_COUNTER, "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
    "gravity" to Sensor.TYPE_GRAVITY, "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
)
private val SENSOR_UNITS = mapOf(
    "accelerometer" to "m/s^2", "gravity" to "m/s^2", "linear_acceleration" to "m/s^2",
    "gyroscope" to "rad/s", "magnetic_field" to "uT", "light" to "lx", "proximity" to "cm",
    "pressure" to "hPa", "temperature" to "°C", "humidity" to "%", "step_counter" to "steps",
)

fun listSensorsTool(context: Context): Tool = Tool(
    name = "list_sensors",
    description = "List available sensors, their types, vendors, ranges and resolutions. Does not start sampling.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    execute = { deviceToolResult {
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return@deviceToolResult deviceToolError("Служба датчиков недоступна.")
        buildJsonObject {
            put("sensors", buildJsonArray {
                manager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
                    addJsonObject {
                        put("name", sensor.name)
                        put("type", SENSOR_TYPES.entries.firstOrNull { it.value == sensor.type }?.key ?: sensor.stringType)
                        put("vendor", sensor.vendor)
                        put("max_range", sensor.maximumRange)
                        put("resolution", sensor.resolution)
                    }
                }
            })
        }
    } },
)

fun readSensorTool(context: Context): Tool = Tool(
    name = "read_sensor",
    description = "Read an averaged sensor sample. type is a sensor name such as accelerometer, light or step_counter. duration_ms defaults to 200, maximum 5000; absent readings return an error.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("type", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { SENSOR_TYPES.keys.forEach { add(it) } })
        })
        put("duration_ms", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 5000) })
    }, required = listOf("type")) },
    execute = { input -> deviceToolResult {
        val params = input as? JsonObject ?: return@deviceToolResult deviceToolError("Ожидался объект параметров.")
        val type = params.textArgument("type")
        val typeInt = SENSOR_TYPES[type] ?: return@deviceToolResult deviceToolError("Неизвестный тип датчика.")
        val duration = if ("duration_ms" in params) (params["duration_ms"] as? JsonPrimitive)?.intOrNull else 200
        if (duration == null || duration !in 1..5000) return@deviceToolResult deviceToolError("duration_ms должен быть целым числом от 1 до 5000.")
        if (typeInt == Sensor.TYPE_STEP_COUNTER && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return@deviceToolResult deviceToolError("Для шагомера разрешите физическую активность при включении «Датчики» в настройках ассистента.", Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(typeInt)
        if (manager == null || sensor == null) return@deviceToolResult deviceToolError("Этот датчик отсутствует на устройстве.")
        var listener: SensorEventListener? = null
        val values = collectSensorValues(duration, register = { receive ->
            val subscription = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) { receive(event.values) }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = subscription
            manager.registerListener(subscription, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper()))
        }, unregister = { listener?.let { manager.unregisterListener(it) } })
            ?: return@deviceToolResult deviceToolError("Датчик не передал показания. Попробуйте увеличить duration_ms до 5000.")
        buildJsonObject {
            put("type", type)
            put("values", buildJsonArray { values.forEach { add(it) } })
            SENSOR_UNITS[type]?.let { put("unit", it) }
            put("timestamp_ms", System.currentTimeMillis())
        }
    } },
)
