package me.rerere.rikkahub.ui.pages.setting

internal enum class PermissionAccessKind { Runtime, Special, Automatic, Root }

internal data class PermissionAccessEntry(
    val id: String,
    val title: String,
    val purpose: String,
    val permissions: List<String> = emptyList(),
    val kind: PermissionAccessKind = PermissionAccessKind.Runtime,
    val minSdk: Int = 26,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    fun availableOn(sdk: Int): Boolean = sdk in minSdk..maxSdk
}

/** Only access already used by the app; this catalog does not grant or probe root. */
internal object PermissionsAccessCatalog {
    private fun android(vararg names: String) = names.map { "android.permission.$it" }

    val entries = listOf(
        PermissionAccessEntry("notifications", "Уведомления", "Ответы ассистента, напоминания и состояние фоновых задач.",
            android("POST_NOTIFICATIONS"), minSdk = 33),
        PermissionAccessEntry("legacy_notifications", "Уведомления на Android 8–12", "Уведомления об ответах и задачах включаются в настройках Android.",
            kind = PermissionAccessKind.Special, maxSdk = 32),
        PermissionAccessEntry("sms", "SMS", "Инструменты чтения и отправки SMS; отправка по-прежнему требует одобрения.",
            android("READ_SMS", "SEND_SMS")),
        PermissionAccessEntry("contacts", "Контакты", "Поиск и чтение контактов телефона.", android("READ_CONTACTS")),
        PermissionAccessEntry("calls", "Журнал звонков", "Чтение истории звонков.", android("READ_CALL_LOG")),
        PermissionAccessEntry("calendar", "Календарь", "Чтение и создание событий календаря.", android("READ_CALENDAR", "WRITE_CALENDAR")),
        PermissionAccessEntry("camera", "Камера", "Фотографии и сканирование через инструменты камеры.", android("CAMERA")),
        PermissionAccessEntry("microphone", "Микрофон", "Распознавание речи и запись звука.", android("RECORD_AUDIO")),
        PermissionAccessEntry("location", "Геолокация", "Местоположение, сведения о Wi-Fi и ближайших сетях.",
            android("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")),
        PermissionAccessEntry("phone", "Состояние телефона", "Сведения о мобильной сети и SIM-карте.", android("READ_PHONE_STATE")),
        PermissionAccessEntry("bluetooth", "Bluetooth", "Сведения о подключённых Bluetooth-устройствах.", android("BLUETOOTH_CONNECT"), minSdk = 31),
        PermissionAccessEntry("activity", "Физическая активность", "Шагомер и сведения о движении.", android("ACTIVITY_RECOGNITION"), minSdk = 29),
        PermissionAccessEntry("nearby_wifi", "Устройства Wi-Fi поблизости", "Доступ к локальному веб-серверу и сведениям о Wi-Fi.",
            android("NEARBY_WIFI_DEVICES"), minSdk = 33),
        PermissionAccessEntry("local_network", "Локальная сеть", "SSH и подключения к устройствам в домашней сети.",
            android("ACCESS_LOCAL_NETWORK"), minSdk = 37),
        PermissionAccessEntry("termux", "Команды Termux", "Запуск команд в установленном Termux; его настройка allow-external-apps нужна отдельно.",
            listOf("com.termux.permission.RUN_COMMAND")),
        PermissionAccessEntry("legacy_storage", "Запись в память на старых Android", "Файлы на Android 8 и 9; новые версии используют доступ к папкам или ко всем файлам.",
            android("WRITE_EXTERNAL_STORAGE"), maxSdk = 28),
        PermissionAccessEntry("all_files", "Доступ ко всем файлам", "Прямой доступ файловых инструментов к общей памяти; защищённые папки остаются закрыты.",
            android("MANAGE_EXTERNAL_STORAGE"), PermissionAccessKind.Special, minSdk = 30),
        PermissionAccessEntry("exact_alarms", "Точные будильники", "Своевременный запуск Cron и сценариев по расписанию.",
            android("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM"), PermissionAccessKind.Special, minSdk = 31),
        PermissionAccessEntry("write_settings", "Изменение системных настроек", "Инструменты яркости и системного звука.",
            android("WRITE_SETTINGS"), PermissionAccessKind.Special),
        PermissionAccessEntry("usage", "Статистика использования", "Экранное время и использование приложений.",
            android("PACKAGE_USAGE_STATS"), PermissionAccessKind.Special),
        PermissionAccessEntry("dnd", "Режим «Не беспокоить»", "Управление звуком и режимом уведомлений.",
            android("ACCESS_NOTIFICATION_POLICY"), PermissionAccessKind.Special),
        PermissionAccessEntry("battery", "Работа без оптимизации батареи", "Фоновые задачи, расписания и долгие ответы при выключенном экране.",
            android("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), PermissionAccessKind.Special),
        PermissionAccessEntry("promoted_notifications", "Расширенные уведомления", "Подробное состояние выполняемых задач в уведомлениях.",
            android("POST_PROMOTED_NOTIFICATIONS"), PermissionAccessKind.Special, minSdk = 36),
        PermissionAccessEntry("root", "Root", "Root-команды и управление устройством; проверка только по вашему нажатию.",
            kind = PermissionAccessKind.Root),
        PermissionAccessEntry("network", "Интернет и состояние сетей", "Модели, поиск, браузер, MCP, SSH и сетевые инструменты. Android выдаёт этот доступ при установке.",
            android("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("alarm_clock", "Будильники и таймеры", "Создание будильников и таймеров в приложении часов. Android выдаёт этот доступ при установке.",
            listOf("com.android.alarm.permission.SET_ALARM"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("vibration", "Вибрация", "Отклик интерфейса и инструмент вибрации. Выдаётся при установке.",
            android("VIBRATE"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("wallpaper", "Обои", "Инструмент установки обоев. Выдаётся при установке.", android("SET_WALLPAPER"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("nfc", "NFC", "Чтение меток в открытом приложении. Выдаётся при установке.", android("NFC"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("biometric", "Биометрия", "Подтверждение доступа отпечатком или распознаванием лица. Выдаётся при установке.",
            android("USE_BIOMETRIC"), PermissionAccessKind.Automatic, minSdk = 28),
        PermissionAccessEntry("fingerprint", "Отпечаток пальца на Android 8", "Подтверждение доступа отпечатком. Выдаётся при установке.",
            android("USE_FINGERPRINT"), PermissionAccessKind.Automatic, maxSdk = 27),
        PermissionAccessEntry("background", "Восстановление после перезагрузки", "Восстановление расписаний после перезагрузки телефона. Выдаётся при установке.",
            android("RECEIVE_BOOT_COMPLETED"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("foreground_service", "Фоновые службы", "Уведомления о долгих ответах и воспроизведение звука. Выдаётся при установке.",
            android("FOREGROUND_SERVICE"), PermissionAccessKind.Automatic, minSdk = 28),
        PermissionAccessEntry("wake_lock", "Работа при выключенном экране", "Фоновые задачи могут завершать работу при выключенном экране. Выдаётся при установке.",
            android("WAKE_LOCK"), PermissionAccessKind.Automatic),
        PermissionAccessEntry("foreground_types", "Долгие задачи на новых Android", "Воспроизведение звука, синхронизация и локальный веб-сервер. Выдаётся при установке.",
            android("FOREGROUND_SERVICE_MEDIA_PLAYBACK", "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_SPECIAL_USE"),
            PermissionAccessKind.Automatic, minSdk = 34),
    )

    fun runtimeRequest(sdk: Int, declared: Set<String>, supported: Set<String>, granted: Set<String>): List<String> {
        val request = entries.asSequence().filter { it.kind == PermissionAccessKind.Runtime && it.availableOn(sdk) }
            .flatMap { it.permissions }.filter { it in declared && it in supported && it !in granted }.distinct().toList()
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        // Android 12+ ignores a precise-location request without approximate location,
        // including when the user already granted approximate access earlier.
        return if (sdk >= 31 && fine in request && coarse in declared && coarse in supported && coarse !in request)
            request.toMutableList().apply { add(indexOf(fine), coarse) } else request
    }
}

/** Commands are built from the fixed catalog, with a validated package and Android profile. */
internal fun permissionRootGrantCommands(entry: PermissionAccessEntry, packageName: String, userId: Int, sdk: Int): List<String> {
    if (userId < 0 || !Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName) ||
        !entry.availableOn(sdk) || entry !in PermissionsAccessCatalog.entries) return emptyList()
    if (entry.kind == PermissionAccessKind.Runtime) return entry.permissions.map { "pm grant --user $userId $packageName $it" }
    return when (entry.id) {
        "all_files" -> listOf("appops set --user $userId $packageName MANAGE_EXTERNAL_STORAGE allow")
        "exact_alarms" -> listOf("appops set --user $userId $packageName SCHEDULE_EXACT_ALARM allow")
        "write_settings" -> listOf("appops set --user $userId $packageName WRITE_SETTINGS allow")
        "usage" -> listOf("appops set --user $userId $packageName GET_USAGE_STATS allow")
        "battery" -> listOf("dumpsys deviceidle whitelist +$packageName")
        "dnd" -> listOf("cmd notification allow_dnd $packageName $userId")
        else -> emptyList()
    }
}

internal fun safFolderDisplayPath(documentId: String?, fallback: String): String {
    val volume = documentId?.substringBefore(':').orEmpty()
    val relative = documentId?.substringAfter(':', "").orEmpty().trim('/')
    val root = when {
        volume == "primary" -> "/sdcard"
        Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "/storage/$volume"
        else -> return fallback.ifBlank { "Папка" }
    }
    return root + if (relative.isEmpty()) "" else "/$relative"
}
