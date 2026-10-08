package me.rerere.rikkahub.workflow.execution

import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec

/** Source schemas stay compatible, while special accesses are explicitly absent in Root. */
object WorkflowAvailability {
    fun triggerReason(trigger: TriggerSpec): String? = when (trigger) {
        is TriggerSpec.GeofenceEnter, is TriggerSpec.GeofenceExit ->
            "Геозоны отключены: нужен специальный доступ к местоположению в фоне, который не подключён в этой сборке."
        is TriggerSpec.NotificationReceived ->
            "Триггер уведомлений отключён: доступ к чтению чужих уведомлений не подключён в этой сборке."
        is TriggerSpec.AppLaunched, is TriggerSpec.AppClosed ->
            "Триггер запуска/закрытия приложений отключён: служба специальных возможностей не подключена. Автоматизация экрана через root этот поток событий не предоставляет."
        else -> null
    }

    /** Source runtime permission gate exposed for actual Android permission regression tests. */
    fun runtimeReason(context: android.content.Context, trigger: TriggerSpec): String? {
        triggerReason(trigger)?.let { return it }
        return when (trigger) {
            is TriggerSpec.BluetoothDeviceConnected, is TriggerSpec.BluetoothDeviceDisconnected -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    "Нет доступа к Bluetooth. Включите функцию «Сценарии» в настройках ассистента и разрешите доступ к устройствам поблизости."
                else null
            }
            else -> null
        }
    }

    fun conditionReason(conditions: List<ConditionSpec>): String? = when {
        conditions.any { it is ConditionSpec.ForegroundAppIs || it is ConditionSpec.ForegroundAppIn } ->
            "Условия активного приложения отключены: служба специальных возможностей не подключена."
        conditions.any { it is ConditionSpec.TimeAfterSunset || it is ConditionSpec.TimeBeforeSunrise } ->
            "Условия заката и рассвета отключены: фоновое чтение местоположения не подключено."
        else -> null
    }
}
