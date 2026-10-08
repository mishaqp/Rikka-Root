package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.data.preferences.isWorkspaceToolName

/** Registry of side-effect capabilities actually implemented by this app. */
object ToolPermissionPolicy {
    fun canResumeAutomatic(tool: UIMessagePart.Tool): Boolean =
        !tool.isExecuted && tool.approvalState == ToolApprovalState.Auto &&
            tool.toolName != "ask_user"

    val registry: Map<String, String> = linkedMapOf(
        "schedule_job" to "создание заданий по расписанию",
        "list_jobs" to "список собственных заданий",
        "get_job_history" to "история собственных заданий",
        "delete_job" to "удаление заданий",
        "pause_job" to "приостановка заданий",
        "resume_job" to "возобновление заданий",
        "trigger_job_now" to "ручной запуск задания",
        "subagent_dispatch" to "запуск субагента",
        "subagent_list" to "список собственных субагентов",
        "subagent_get" to "чтение результатов собственного субагента",
        "subagent_cancel" to "остановка собственного субагента",
        "generate_bug_report" to "создание безопасного отчёта об ошибках",
        "check_token_usage" to "расход токенов текущего чата",
        "list_files" to "список файлов",
        "read_file" to "чтение файлов",
        "write_binary_file" to "запись двоичных файлов",
        "delete_file" to "удаление файлов",
        "move_file" to "перемещение файлов",
        "copy_file" to "копирование файлов",
        "create_directory" to "создание папок",
        "file_info" to "сведения о файлах",
        "find_files" to "поиск файлов",
        "show_image" to "чтение изображения",
        "open_file" to "открытие файла в приложении",
        "batch_copy" to "пакетное копирование",
        "batch_move" to "пакетное перемещение",
        "batch_delete" to "пакетное удаление",
        "list_storage_volumes" to "список накопителей",
        "list_granted_directories" to "список папок SAF",
        "grant_directory_access" to "выбор папки SAF",
        "zip_files" to "создание ZIP",
        "unzip_file" to "распаковка ZIP",
        "list_zip_contents" to "содержимое ZIP",
        "play_media" to "воспроизведение медиа",
        "stop_media" to "остановка медиа",
        "pause_media" to "пауза медиа",
        "resume_media" to "возобновление медиа",
        "seek_media" to "перемотка медиа",
        "get_media_status" to "состояние медиаплеера",
        "scan_media" to "сканирование медиафайлов",
        "download_file" to "загрузка файлов",
        "write_text_file" to "запись текста в файл",
        "create_calendar_event" to "черновик события календаря",
        "create_contact" to "черновик контакта",
        "send_email_intent" to "черновик письма",
        "send_sms_intent" to "черновик SMS",
        "open_wifi_settings" to "открытие настроек Wi-Fi",
        "show_location_on_map" to "открытие карты",
        "launch_app" to "запуск приложения",
        "list_installed_apps" to "список видимых приложений",
        "open_url" to "открытие ссылки",
        "list_app_activities" to "список экранов приложения",
        "launch_activity" to "запуск экспортированной Activity",
        "root_exec" to "root-команды",
        "workspace_shell" to "команды workspace",
        "workspace_background_start" to "запуск фоновых процессов workspace",
        "workspace_background_stop" to "остановка фоновых процессов workspace",
        "workspace_write_file" to "запись файлов workspace",
        "workspace_edit_file" to "редактирование файлов workspace",
        "clipboard_tool" to "запись в буфер обмена",
        "calendar_create" to "создание событий календаря",
        "memory_tool" to "создание, изменение и удаление памяти",
        "text_to_speech" to "озвучивание текста",
        "show_toast" to "всплывающие сообщения",
        "post_notification" to "публикация уведомлений",
        "share" to "открытие системного меню отправки",
        "set_torch" to "управление фонариком",
        "vibrate" to "вибрация устройства",
        "get_brightness" to "чтение яркости экрана",
        "set_brightness" to "изменение яркости экрана",
        "get_volume" to "чтение громкости",
        "set_volume" to "изменение громкости",
        "set_wallpaper" to "смена обоев",
        "nfc_status" to "состояние NFC",
        "nfc_read_tag" to "чтение NFC-меток",
        "nfc_write_tag" to "запись NFC-меток",
        "get_location" to "местоположение",
        "list_contacts" to "чтение контактов",
        "search_contacts" to "поиск контактов",
        "list_call_log" to "журнал звонков",
        "list_sms_inbox" to "чтение входящих SMS",
        "search_sms" to "поиск SMS",
        "send_sms" to "отправка SMS",
        "take_photo" to "съёмка фото",
        "record_audio" to "запись микрофона",
        "speech_to_text" to "распознавание речи",
        "verify_fingerprint" to "биометрическая проверка",
        "keystore_generate_key" to "создание ключей",
        "keystore_sign" to "подпись данных ключом",
        "keystore_verify" to "проверка подписи",
        "keystore_encrypt" to "шифрование данных",
        "keystore_decrypt" to "расшифровка данных",
        "keystore_delete_key" to "удаление ключей",
        "keystore_list_keys" to "список ключей инструментов",
    )

    fun mandatoryConfirmation(name: String, input: JsonElement): Boolean {
        if (name != "root_exec") return false
        val command = ((input as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return command.isNullOrBlank() || RootApprovalPolicy.reason(command) != null
    }

    fun sideEffect(name: String, input: JsonElement): Boolean = when (name) {
        "clipboard_tool" -> ((input as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull != "read"
        else -> ToolApprovalDefaults.requiresApproval(name)
    }

    fun canGrantAlways(name: String, input: JsonElement): Boolean =
        name != "ask_user" && !mandatoryConfirmation(name, input)

    fun apply(tool: Tool): Tool = tool.copy(needsApproval = { input ->
        when {
            tool.name == "ask_user" -> true // Interactive answers must never be auto-filled.
            mandatoryConfirmation(tool.name, input) -> true
            // Resolve workspace's live overrides through isToolAutoApproved, not a frozen tool definition.
            isWorkspaceToolName(tool.name) -> true
            else -> sideEffect(tool.name, input) || tool.needsApproval(input)
        }
    })

    fun warningText(): String =
        "Без запроса будут выполняться доступные ассистенту инструменты: ${registry.values.joinToString(", ")}. " +
            "Также будут автоодобряться подключённые MCP-инструменты, для которых настроено подтверждение. Вопросы пользователю остаются интерактивными.\n\n" +
            "Автоодобрение и «Всегда разрешать» не обходят подтверждение массового удаления защищённых каталогов, форматирования, записи в разделы, " +
            "изменения загрузчика, сброса устройства, отключения SELinux/проверки загрузки и remount рабочих разделов. " +
            "Подтверждения после веб-поиска возвращаются только при включённом переключателе «Спрашивать после веб-контента». " +
            "Запретный список не разбирает shell-обёртки (sh -c, eval) и содержимое скриптов.\n\n" +
            "Это опасная функция. Включайте, только если доверяете модели, конфигурации ассистента и своим запросам."
}
