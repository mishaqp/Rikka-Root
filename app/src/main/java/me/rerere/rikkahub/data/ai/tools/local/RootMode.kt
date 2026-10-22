package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.root.RootStatus

/**
 * «Всё через root»: phone tools whose job a root shell does with one command are not sent to
 * the model, root_exec is added with a short command guide instead. This only changes which
 * tools are advertised; root_exec keeps its own approval and protected-command checks.
 */
object RootMode {
    /** Options with a reliable one-line shell equivalent, listed in [GUIDE]. */
    val REPLACED: Set<LocalToolOption> = setOf(
        LocalToolOption.TimeInfo,
        LocalToolOption.AlarmClock,
        LocalToolOption.Files,
        LocalToolOption.ExternalStorage,
        LocalToolOption.MediaScanner,
        LocalToolOption.SystemIntents,
        LocalToolOption.AppLauncher,
        LocalToolOption.Battery,
        LocalToolOption.AudioInfo,
        LocalToolOption.TelephonyInfo,
        LocalToolOption.WifiInfo,
        LocalToolOption.StorageInfo,
        LocalToolOption.Notification,
        LocalToolOption.Brightness,
        LocalToolOption.Volume,
        LocalToolOption.Vibrate,
        LocalToolOption.ScreenAutomation,
        LocalToolOption.Contacts,
        LocalToolOption.CallLog,
        LocalToolOption.SmsInbox,
    )

    /** A definite answer that this device has no usable root; then the normal tools stay. */
    private val ROOT_MISSING = setOf(RootStatus.UNAVAILABLE, RootStatus.NOT_ROOT, RootStatus.DENIED_OR_FAILED)

    fun isActive(rootMode: Boolean, rootStatus: RootStatus): Boolean = rootMode && rootStatus !in ROOT_MISSING

    fun effectiveOptions(options: List<LocalToolOption>, rootMode: Boolean, rootStatus: RootStatus): List<LocalToolOption> =
        if (!isActive(rootMode, rootStatus)) options
        else (options.filterNot { it in REPLACED } + LocalToolOption.Root).distinct()

    /** Adds the guide to root_exec's system prompt; every other tool is returned unchanged. */
    fun withGuide(tool: Tool): Tool = if (tool.name != "root_exec") tool else tool.copy(
        systemPrompt = { model, messages ->
            val own = tool.systemPrompt(model, messages)
            if (own.isBlank()) GUIDE else own + "\n" + GUIDE
        },
    )

    val GUIDE: String = """
Режим «Всё через root»: отдельные инструменты телефона отключены. Время, будильники, приложения, файлы, экран, звук, вибрацию, сеть, уведомления, контакты, SMS и звонки делай через root_exec (Android shell под su). Шпаргалка:
- Время: date '+%Y-%m-%d %H:%M:%S %Z'
- Будильник: am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7 --ei android.intent.extra.alarm.MINUTES 30 --es android.intent.extra.alarm.MESSAGE 'Подпись' --ez android.intent.extra.alarm.SKIP_UI true
- Таймер (секунды, до 86400): am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 90 --es android.intent.extra.alarm.MESSAGE 'Подпись' --ez android.intent.extra.alarm.SKIP_UI true
- Показать будильники: am start -a android.intent.action.SHOW_ALARMS. Проверить, что будильник или таймер поставлен: dumpsys alarm | grep -A6 deskclock (подпись там не видна, сверяй время)
- Приложения: pm list packages -3 (установленные); найти пакет по части имени: pm list packages ЧАСТЬ; имя по пакету: dumpsys package ПАКЕТ | grep -m1 versionName; запустить: monkey -p ПАКЕТ -c android.intent.category.LAUNCHER 1; закрыть: am force-stop ПАКЕТ
- Открыть ссылку или файл: am start -a android.intent.action.VIEW -d 'https://example.com'
- Заметка: mkdir -p /sdcard/Documents/Notes && printf '%s\n' 'строка 1' 'строка 2' > '/sdcard/Documents/Notes/Имя.md' (>> — дописать); отправить текст в приложение: am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'текст'
- Уведомление: cmd notification post -S bigtext -t 'Заголовок' rikka 'Текст'
- Файлы: ls -la, cat, head, cp, mv, mkdir -p, find, du -sh, df -h; общая память — /sdcard. Удаляй (rm) только то, что пользователь явно просил.
- После создания файла в /sdcard, чтобы он появился в галерее: am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/путь'
- Батарея: dumpsys battery. Память: df -h /data /sdcard. Модель и Android: getprop ro.product.model; getprop ro.build.version.release
- Wi-Fi: cmd wifi status; svc wifi enable|disable. Мобильные данные: svc data enable|disable. Bluetooth: cmd bluetooth_manager enable|disable
- Громкость (поток 3 — музыка, 2 — звонок, 4 — будильник, 5 — уведомления): cmd media_session volume --stream 3 --get; --set 8. На старых Android: media volume --stream 3 --set 8
- Не беспокоить: cmd notification set_dnd on | priority | alarms | off (settings put global zen_mode не работает — система откатывает). Узнать: settings get global zen_mode (0 — выкл, 1 — только важные, 2 — полная тишина, 3 — только будильники)
- Режим полёта: cmd connectivity airplane-mode enable|disable (узнать: settings get global airplane_mode_on)
- Поворот экрана: автоповорот settings put system accelerometer_rotation 1|0; при выключенном — settings put system user_rotation 0|1|2|3
- Вибрация (мс): cmd vibrator_manager synced oneshot 500; на старых Android: cmd vibrator vibrate 500
- Музыка в любом плеере: cmd media_session dispatch play-pause | next | previous | pause; что играет: dumpsys media_session | grep -m4 -E 'package=|metadata: size=[1-9]|state=PlaybackState' (трек — в metadata: description=Название, Исполнитель). Открыть файл в плеере: am start -a android.intent.action.VIEW -d 'file:///sdcard/Music/файл.mp3' -t audio/*
- Календарь (только чтение; создавать событие — инструментом календаря, если он есть): content query --uri content://com.android.calendar/instances/when/$(date +%s)000/$(( $(date +%s) + 2592000 ))000 --projection title:begin:end --sort 'begin ASC' | head -n 20 (окно 30 дней; instances показывает и повторяющиеся события, например дни рождения; begin/end — миллисекунды)
- Где телефон (последние координаты): dumpsys location | grep -m3 -i 'last location'. Если везде null — геолокация выключена (settings get secure location_mode = 0), координат нет; так и скажи.
- Перевести время из секунд в дату: date -D %s -d 1804982400 '+%Y-%m-%d %H:%M' (toybox; date -d @… здесь даёт неверный результат). Миллисекунды сначала раздели на 1000: $(( MS / 1000 ))
- Экран включён или нет: dumpsys power | grep -m1 mWakefulness. Открыть настройки приложения: am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:ПАКЕТ' (URI обязательно в кавычках). Что сейчас на экране: dumpsys window | grep -m1 mCurrentFocus
- Яркость: settings put system screen_brightness_mode 0; settings put system screen_brightness 128 (0–255)
- Экран: input tap X Y; input swipe X1 Y1 X2 Y2 300; input text 'abc' (пробел — %s); input keyevent KEYCODE_HOME | KEYCODE_BACK | KEYCODE_ENTER | KEYCODE_WAKEUP. Разметка с координатами: uiautomator dump /sdcard/window.xml >/dev/null && cat /sdcard/window.xml. Скриншот: screencap -p /sdcard/Pictures/screen.png
- Контакты: content query --uri content://com.android.contacts/data/phones --projection display_name:data1 (последние добавленные: --sort '_id DESC')
- SMS: content query --uri content://sms/inbox --projection address:date:body --sort 'date DESC' | head -n 40
- Журнал звонков: content query --uri content://call_log/calls --projection number:name:date:type --sort 'date DESC' | head -n 40
PID и стартовая строка журнала не подтверждают завершение. После ошибки проверь результат каждого действия по stdout/stderr, exit_code и состоянию устройства. Не говори «всё выполнено», пока запрошенный результат не подтверждён; о частично выполненных или непроверенных действиях сообщай прямо.
Правила: независимые проверки объединяй в одну команду через ; или && (одна команда вместо пяти — ответ в разы быстрее). Команда не должна ждать ввода (никаких vi, less, top без -n 1). Значения с пробелами бери в одинарные кавычки. Если команда не сработала, прочитай stderr и попробуй другой способ, а не ту же команду. Перед изменением системных настроек и удалением убедись, что пользователь этого хочет.
""".trim()
}
