/* Adapted from ExTV/RikkaHub Agent SystemIntentTools.kt and AppLauncherTool.kt.
 * Original project authors retain copyright; licensed under AGPL-3.0 (repository LICENSE).
 */
package me.rerere.rikkahub.data.ai.tools.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.net.URI

internal fun safeNativeUrl(value: String): String {
    require(value.isNotBlank() && value.length <= 8192 && value.none { it.isISOControl() }) { "Некорректный URL. Укажите полную ссылку с допустимой схемой." }
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Некорректный URL. Укажите полную ссылку с допустимой схемой.") }
    val scheme = uri.scheme?.lowercase()
    require(scheme in setOf("http", "https", "mailto", "tel", "geo", "market")) { "Используйте ссылку со схемой http, https, mailto, tel, geo или market." }
    require(uri.rawUserInfo == null && !uri.rawSchemeSpecificPart.isNullOrBlank()) { "Укажите URL без логина и пароля и с непустым адресом." }
    if (scheme in setOf("http", "https", "market")) require(!uri.host.isNullOrBlank()) { "В URL отсутствует адрес сервера. Укажите полную ссылку." }
    return value
}
internal fun nativePackageName(value: String): String = value.also {
    require(it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Укажите корректное имя пакета, например org.example.app." }
}
internal fun nativeActivityName(pkg: String, value: String): String {
    nativePackageName(pkg)
    val name = if (value.startsWith('.')) pkg + value else value
    require(name.length <= 512 && name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) { "Укажите полное имя Activity или имя с ведущей точкой, например .MainActivity." }
    return name
}
internal fun validateCalendarTimes(start: Long?, end: Long?) {
    require((start == null || start >= 0) && (end == null || end >= 0) && (start == null || end == null || end >= start)) { "Укажите неотрицательное время события: конец должен быть не раньше начала." }
}
private fun nativeText(args: JsonObject, key: String, required: Boolean = false, max: Int = 16000): String? {
    if (key !in args) { require(!required) { "Нужен параметр $key." }; return null }
    val value = args.textArgument(key) ?: throw IllegalArgumentException("$key должен быть строкой.")
    require(value.length <= max && (!required || value.isNotBlank()) && '\u0000' !in value) { "Проверьте параметр $key: допустимая длина — до $max символов; обязательное значение не должно быть пустым." }
    return value
}
private fun nativeLong(args: JsonObject, key: String): Long? {
    if (key !in args) return null
    return (args[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("$key должен быть целым числом.")
}
private fun nativeSchema(name: String, fields: Map<String, String>, required: List<String> = emptyList()) = InputSchema.Obj(
    properties = buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject {
        put("type", type)
        put("description", when (key) {
            "title" -> "Непустое название черновика события, до 1024 символов. Пользователь сам сохраняет событие."
            "description" -> "Необязательное описание события, до 16000 символов."
            "location" -> "Место события, до 2048 символов; геолокация телефона не читается."
            "start_time_unix_ms" -> "Неотрицательное время начала события: миллисекунды Unix. Не позже переданного конца."
            "end_time_unix_ms" -> "Неотрицательное время конца: миллисекунды Unix, не раньше переданного начала."
            "all_day" -> "Событие на весь день, по умолчанию false."
            "first_name" -> "Имя нового контакта, до 512 символов."
            "last_name" -> "Фамилия нового контакта, до 512 символов."
            "phone_number" -> if (name == "send_sms_intent") "Непустой номер до 64 символов: цифры, пробелы, скобки, + и -. Только открывает черновик SMS."
                else "Телефон контакта, до 2048 символов."
            "email" -> "Адрес электронной почты контакта, до 2048 символов."
            "organization" -> "Организация контакта, до 2048 символов."
            "to" -> "Непустой адрес получателя письма, до 512 символов, без управляющих символов."
            "subject" -> "Тема письма, до 2048 символов."
            "body" -> "Текст черновика сообщения, до 16000 символов; отправку выполняет пользователь."
            "query" -> "Непустой адрес или название места для поиска на карте, до 2048 символов."
            "package_name" -> "Имя видимого Android-пакета, например org.example.app, до 255 символов."
            "activity_name" -> "Полное имя экспортированной Activity либо имя с ведущей точкой, например .MainActivity; до 512 символов."
            "url" -> "Полная ссылка до 8192 символов: http, https, mailto, tel, geo или market; без встроенного логина/пароля. Открывает приложение, но не читает страницу."
            "filter" -> "Поиск без учёта регистра по имени/пакету либо имени Activity, до 512 символов; по умолчанию без фильтра."
            "user_only" -> "Только пользовательские приложения, по умолчанию true."
            "limit" -> if (name == "list_installed_apps") "Максимум приложений: 1–1000, по умолчанию 200; проверяйте matched и truncated."
                else "Максимум Activity: 1–500, по умолчанию 100; проверяйте matched и truncated."
            "include_no_launcher" -> "Включать приложения без экрана запуска, по умолчанию false. Непустой filter включает их автоматически."
            "include_permissions" -> "Включить запрошенные разрешения каждого пакета, по умолчанию false; до 256 имён на пакет."
            "exported_only" -> "Только экспортированные Activity, по умолчанию false. Запускать можно лишь экспортированные и включённые."
            else -> "Параметр системного действия Android."
        })
        val maxLength = when (key) {
            "title" -> 1024
            "description", "body" -> 16000
            "location", "email", "organization", "subject", "query" -> 2048
            "first_name", "last_name", "to", "activity_name", "filter" -> 512
            "phone_number" -> if (name == "send_sms_intent") 64 else 2048
            "package_name" -> 255
            "url" -> 8192
            else -> null
        }
        maxLength?.let { put("maxLength", it) }
        if (key in required && type == "string") put("minLength", 1)
        when (key) {
            "start_time_unix_ms", "end_time_unix_ms" -> put("minimum", 0)
            "limit" -> { put("minimum", 1); put("maximum", if (name == "list_installed_apps") 1000 else 500); put("default", if (name == "list_installed_apps") 200 else 100) }
            "user_only" -> put("default", true)
            "include_no_launcher", "include_permissions", "exported_only", "all_day" -> put("default", false)
        }
    }) } }, required = required,
)
private fun nativeTool(context: Context, name: String, description: String, option: LocalToolOption,
    fields: Map<String, String>, required: List<String> = emptyList(), execute: suspend (JsonObject) -> JsonObject,
) = personalJsonTool(context, name, description, option, nativeSchema(name, fields, required), validate = { it }, read = { args ->
    try { execute(args) } catch (error: IllegalArgumentException) { deviceToolError(deviceToolFailureReason(error)) }
})

private suspend fun dispatchNative(context: Context, intent: Intent, action: String): JsonObject = withContext(Dispatchers.Main.immediate) {
    if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext deviceToolError("Откройте приложение, чтобы выполнить системное действие.")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Starting directly also supports handlers hidden by package visibility. Android enforces exported/permission checks.
    try {
        context.startActivity(intent)
        buildJsonObject { put("success", true); put("intent_fired", true); put("action", action); put("ok", true); put("confirmed_foreground", false); put("note", "Действие передано Android. Завершите его в открывшемся приложении.") }
    } catch (_: android.content.ActivityNotFoundException) { deviceToolError("Нет приложения для этого действия. Установите или включите подходящее приложение Android.") }
    catch (_: SecurityException) { deviceToolError("Android запретил запуск этого экрана. Выберите доступное приложение или экспортированную Activity.") }
}

internal fun systemIntentTools(context: Context): List<Tool> = listOf(
    nativeTool(context, "create_calendar_event", "Открыть черновик события в календаре по title и необязательным времени/месту. Пользователь сам сохраняет его: результат подтверждает лишь отправку Intent. Для автоматического создания события используйте включённый инструмент календаря, если он доступен. Нужен открытый Rikka-Root.", LocalToolOption.SystemIntents,
        mapOf("title" to "string", "description" to "string", "location" to "string", "start_time_unix_ms" to "integer", "end_time_unix_ms" to "integer", "all_day" to "boolean"), listOf("title")) { args ->
        val title = nativeText(args, "title", true, 1024)!!
        val start = nativeLong(args, "start_time_unix_ms"); val end = nativeLong(args, "end_time_unix_ms"); validateCalendarTimes(start, end)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
        nativeText(args,"description")?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION,it) }
        nativeText(args,"location",max=2048)?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION,it) }
        start?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,it) }; end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME,it) }
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, personalBoolean(args,"all_day",false))
        dispatchNative(context,intent,"create_calendar_event")
    },
    nativeTool(context, "create_contact", "Открыть черновик контакта с именем, телефоном, почтой или организацией. Пользователь сам сохраняет его; инструмент не записывает контакт напрямую. Для поиска существующего контакта используйте инструменты контактов. Нужен открытый Rikka-Root; результат — принятие системного запроса, а не сохранение.", LocalToolOption.SystemIntents,
        mapOf("first_name" to "string", "last_name" to "string", "phone_number" to "string", "email" to "string", "organization" to "string")) { args ->
        val intent=Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        val name=listOfNotNull(nativeText(args,"first_name",max=512),nativeText(args,"last_name",max=512)).joinToString(" ")
        intent.putExtra(ContactsContract.Intents.Insert.NAME,name)
        mapOf("phone_number" to ContactsContract.Intents.Insert.PHONE,"email" to ContactsContract.Intents.Insert.EMAIL,"organization" to ContactsContract.Intents.Insert.COMPANY).forEach { (field,extra) -> nativeText(args,field,max=2048)?.let { intent.putExtra(extra,it) } }
        dispatchNative(context,intent,"create_contact")
    },
    nativeTool(context,"send_email_intent","Открыть черновик письма с получателем, темой и текстом. Пользователь сам нажимает «Отправить»; фактическую отправку инструмент не подтверждает. Для просмотра почты этот инструмент не подходит. Нужен открытый Rikka-Root и почтовое приложение.",LocalToolOption.SystemIntents,
        mapOf("to" to "string","subject" to "string","body" to "string"),listOf("to")) { args ->
        val to=nativeText(args,"to",true,512)!!; require(to.none { it.isISOControl() }) { "Укажите адрес почты без управляющих символов." }
        val intent=Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL,arrayOf(to))
        nativeText(args,"subject",max=2048)?.let { intent.putExtra(Intent.EXTRA_SUBJECT,it) }; nativeText(args,"body")?.let { intent.putExtra(Intent.EXTRA_TEXT,it) }
        dispatchNative(context,intent,"send_email_intent")
    },
    nativeTool(context,"send_sms_intent","Открыть черновик SMS с номером и текстом; пользователь сам нажимает «Отправить». В отличие от send_sms, прямой отправки и подтверждения доставки нет. Нужны открытый Rikka-Root и приложение SMS; результат подтверждает только системный запрос.",LocalToolOption.SystemIntents,
        mapOf("phone_number" to "string","body" to "string"),listOf("phone_number")) { args ->
        val phone=nativeText(args,"phone_number",true,64)!!; require(phone.matches(Regex("[+0-9 ()-]{1,64}"))) { "Укажите номер телефона из цифр, пробелов, скобок, знаков + и - длиной до 64 символов." }
        val intent=Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",phone,null)); nativeText(args,"body")?.let { intent.putExtra("sms_body",it) }
        dispatchNative(context,intent,"send_sms_intent")
    },
    nativeTool(context,"open_wifi_settings","Открыть системные настройки Wi-Fi для ручного выбора сети пользователем. Не включает сеть и не читает пароль; для сведений о подключении используйте get_wifi_info, если он доступен. Нужен открытый Rikka-Root; результат — отправка запроса Android.",LocalToolOption.SystemIntents,emptyMap()) { dispatchNative(context,Intent(Settings.ACTION_WIFI_SETTINGS),"open_wifi_settings") },
    nativeTool(context,"show_location_on_map","Открыть адрес или место из query в приложении карт. Не читает геолокацию телефона: для текущих координат нужен инструмент местоположения. Требуются открытый Rikka-Root и подходящее приложение; пользователь завершает действие в нём.",LocalToolOption.SystemIntents,mapOf("query" to "string"),listOf("query")) { args ->
        dispatchNative(context,Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(nativeText(args,"query",true,2048)))),"show_location_on_map")
    },
)

@Suppress("DEPRECATION")
internal fun appLauncherTools(context: Context): List<Tool> = listOf(
    nativeTool(context,"launch_app","Открыть видимое установленное приложение по package_name. Сначала используйте list_installed_apps; для конкретной Activity — list_app_activities и launch_activity. Нужен открытый Rikka-Root. success/intent_fired означают передачу запроса Android, confirmed_foreground=false требует проверки экрана.",LocalToolOption.AppLauncher,mapOf("package_name" to "string"),listOf("package_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!)
        val intent=context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent==null) deviceToolError("Пакет не виден Android, не установлен или не имеет экрана запуска. Проверьте package_name или выберите приложение из list_installed_apps.") else dispatchNative(context,intent,"launch_app")
    },
    nativeTool(context,"open_url","Открыть URL в системном приложении: http, https, mailto, tel, geo или market, без встроенных учётных данных. Не читает страницу; для чтения используйте browser_get_text, для HTTP API — http_request. Нужен открытый Rikka-Root; результат подтверждает запрос, не загрузку/отправку данных.",LocalToolOption.AppLauncher,mapOf("url" to "string","package_name" to "string"),listOf("url")) { args ->
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse(safeNativeUrl(nativeText(args,"url",true,8192)!!)))
        nativeText(args,"package_name",max=255)?.let { intent.setPackage(nativePackageName(it)) }
        dispatchNative(context,intent,"open_url")
    },
    nativeTool(context,"list_installed_apps","Перечислить видимые Android приложения по filter и лимиту 1–1000 (по умолчанию 200). По умолчанию только пользовательские приложения с экраном запуска; непустой filter включает и пакеты без него. Возвращает apps, returned, matched и truncated; разрешения ограничены 256 именами на пакет. Android скрывает часть пакетов: это не полный список телефона. Для запуска используйте launch_app.",LocalToolOption.AppLauncher,
        mapOf("filter" to "string","user_only" to "boolean","limit" to "integer","include_no_launcher" to "boolean","include_permissions" to "boolean")) { args ->
        val pm=context.packageManager; val filter=nativeText(args,"filter",max=512).orEmpty().lowercase()
        val userOnly=personalBoolean(args,"user_only",true); val limit=personalInt(args,"limit",200,1..1000)
        val includeNoLauncher=personalBoolean(args,"include_no_launcher",false)||filter.isNotBlank(); val permissions=personalBoolean(args,"include_permissions",false)
        val launchers=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map { it.activityInfo.packageName }.toSet()
        val packages=pm.getInstalledPackages(if(permissions) PackageManager.GET_PERMISSIONS else 0)
            .filter { (!userOnly || it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM)==0) && (includeNoLauncher || it.packageName in launchers) }
            .map { it to (it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName) }.filter { filter.isEmpty() || filter in it.first.packageName.lowercase() || filter in it.second.lowercase() }.sortedBy { it.second.lowercase() }
        buildJsonObject { put("apps",buildJsonArray { packages.take(limit).forEach { (info,label) -> add(buildJsonObject { put("label",label); put("package",info.packageName); put("version",info.versionName.orEmpty()); put("has_launcher",info.packageName in launchers); if(permissions) put("permissions",buildJsonArray { info.requestedPermissions?.take(256)?.forEach { add(it) } }) }) } }); put("returned",minOf(packages.size,limit)); put("matched",packages.size); put("truncated",packages.size > limit); put("visibility","android_package_visibility"); put("note","Android может скрывать пакеты; это не полный список всех установленных приложений.") }
    },
    nativeTool(context,"list_app_activities","Перечислить видимые Activity пакета package_name по filter и exported_only (по умолчанию false). limit: 1–500, по умолчанию 100. Возвращает activities, returned, matched и truncated; скрытые системой компоненты могут отсутствовать. Для открытия экспортированной включённой Activity используйте launch_activity.",LocalToolOption.AppLauncher,mapOf("package_name" to "string","filter" to "string","exported_only" to "boolean","limit" to "integer"),listOf("package_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!); val filter=nativeText(args,"filter",max=512).orEmpty().lowercase(); val exportedOnly=personalBoolean(args,"exported_only",false); val limit=personalInt(args,"limit",100,1..500)
        val info=try { context.packageManager.getPackageInfo(pkg,PackageManager.GET_ACTIVITIES) } catch (_: PackageManager.NameNotFoundException) { return@nativeTool deviceToolError("Пакет не установлен или скрыт Android. Проверьте package_name или выберите видимый пакет из list_installed_apps.") }
        val activities=info.activities.orEmpty().filter { (!exportedOnly || it.exported) && (filter.isEmpty() || filter in it.name.lowercase()) }
        buildJsonObject { put("package",pkg); put("matched",activities.size); put("returned",minOf(activities.size,limit)); put("truncated",activities.size > limit); put("activities",buildJsonArray { activities.take(limit).forEach { add(buildJsonObject { put("name",it.name); put("exported",it.exported); put("enabled",it.enabled) }) } }) }
    },
    nativeTool(context,"launch_activity","Открыть экспортированную включённую Activity видимого пакета. Получите package_name и activity_name через list_installed_apps/list_app_activities; для обычного запуска используйте launch_app. Нужен открытый Rikka-Root. Результат подтверждает передачу запроса Android, не фактическое окно.",LocalToolOption.AppLauncher,mapOf("package_name" to "string","activity_name" to "string"),listOf("package_name","activity_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!); val activity=nativeActivityName(pkg,nativeText(args,"activity_name",true,512)!!); val component=ComponentName(pkg,activity)
        val info=try { context.packageManager.getActivityInfo(component,0) } catch (_: PackageManager.NameNotFoundException) { return@nativeTool deviceToolError("Activity не найдена или скрыта Android. Проверьте package_name и activity_name через list_app_activities.") }
        if (!info.exported || !info.enabled || info.applicationInfo?.enabled==false) deviceToolError("Activity не экспортирована или отключена. Выберите включённую экспортированную Activity через list_app_activities.")
        else dispatchNative(context,Intent(Intent.ACTION_MAIN).setComponent(component),"launch_activity")
    },
)
