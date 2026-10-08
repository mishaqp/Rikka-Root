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
    require(value.isNotBlank() && value.length <= 8192 && value.none { it.isISOControl() }) { "Некорректный URL." }
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Некорректный URL.") }
    val scheme = uri.scheme?.lowercase()
    require(scheme in setOf("http", "https", "mailto", "tel", "geo", "market")) { "Допустимы только http, https, mailto, tel, geo и market." }
    require(uri.rawUserInfo == null && !uri.rawSchemeSpecificPart.isNullOrBlank()) { "URL не должен содержать логин или пароль." }
    if (scheme in setOf("http", "https", "market")) require(!uri.host.isNullOrBlank()) { "В URL отсутствует адрес сервера." }
    return value
}
internal fun nativePackageName(value: String): String = value.also {
    require(it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Некорректное имя пакета." }
}
internal fun nativeActivityName(pkg: String, value: String): String {
    nativePackageName(pkg)
    val name = if (value.startsWith('.')) pkg + value else value
    require(name.length <= 512 && name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) { "Некорректное имя Activity." }
    return name
}
internal fun validateCalendarTimes(start: Long?, end: Long?) {
    require((start == null || start >= 0) && (end == null || end >= 0) && (start == null || end == null || end >= start)) { "Некорректное время события: конец должен быть не раньше начала." }
}
private fun nativeText(args: JsonObject, key: String, required: Boolean = false, max: Int = 16000): String? {
    if (key !in args) { require(!required) { "Нужен параметр $key." }; return null }
    val value = args.textArgument(key) ?: throw IllegalArgumentException("$key должен быть строкой.")
    require(value.length <= max && (!required || value.isNotBlank()) && '\u0000' !in value) { "Некорректный параметр $key." }
    return value
}
private fun nativeLong(args: JsonObject, key: String): Long? {
    if (key !in args) return null
    return (args[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("$key должен быть целым числом.")
}
private fun nativeSchema(fields: Map<String, String>, required: List<String> = emptyList()) = InputSchema.Obj(
    properties = buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } }, required = required,
)
private fun nativeTool(context: Context, name: String, description: String, option: LocalToolOption,
    fields: Map<String, String>, required: List<String> = emptyList(), execute: suspend (JsonObject) -> JsonObject,
) = personalJsonTool(context, name, description, option, nativeSchema(fields, required), validate = { it }, read = { args ->
    try { execute(args) } catch (error: IllegalArgumentException) { deviceToolError(error.message ?: "Некорректные параметры.") }
})

private suspend fun dispatchNative(context: Context, intent: Intent, action: String): JsonObject = withContext(Dispatchers.Main.immediate) {
    if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext deviceToolError("Откройте приложение, чтобы выполнить системное действие.")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Starting directly also supports handlers hidden by package visibility. Android enforces exported/permission checks.
    try {
        context.startActivity(intent)
        buildJsonObject { put("success", true); put("intent_fired", true); put("action", action); put("ok", true); put("confirmed_foreground", false); put("note", "Действие передано Android. Завершите его в открывшемся приложении.") }
    } catch (_: android.content.ActivityNotFoundException) { deviceToolError("Нет приложения для этого действия.") }
    catch (_: SecurityException) { deviceToolError("Android запретил запуск этого экрана. Выберите доступное приложение или экспортированную Activity.") }
}

internal fun systemIntentTools(context: Context): List<Tool> = listOf(
    nativeTool(context, "create_calendar_event", "Открыть черновик события в календаре. Пользователь сам сохраняет его.", LocalToolOption.SystemIntents,
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
    nativeTool(context, "create_contact", "Открыть черновик контакта. Пользователь сам сохраняет его.", LocalToolOption.SystemIntents,
        mapOf("first_name" to "string", "last_name" to "string", "phone_number" to "string", "email" to "string", "organization" to "string")) { args ->
        val intent=Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        val name=listOfNotNull(nativeText(args,"first_name",max=512),nativeText(args,"last_name",max=512)).joinToString(" ")
        intent.putExtra(ContactsContract.Intents.Insert.NAME,name)
        mapOf("phone_number" to ContactsContract.Intents.Insert.PHONE,"email" to ContactsContract.Intents.Insert.EMAIL,"organization" to ContactsContract.Intents.Insert.COMPANY).forEach { (field,extra) -> nativeText(args,field,max=2048)?.let { intent.putExtra(extra,it) } }
        dispatchNative(context,intent,"create_contact")
    },
    nativeTool(context,"send_email_intent","Открыть черновик письма. Пользователь сам нажимает «Отправить».",LocalToolOption.SystemIntents,
        mapOf("to" to "string","subject" to "string","body" to "string"),listOf("to")) { args ->
        val to=nativeText(args,"to",true,512)!!; require(to.none { it.isISOControl() }) { "Некорректный адрес почты." }
        val intent=Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL,arrayOf(to))
        nativeText(args,"subject",max=2048)?.let { intent.putExtra(Intent.EXTRA_SUBJECT,it) }; nativeText(args,"body")?.let { intent.putExtra(Intent.EXTRA_TEXT,it) }
        dispatchNative(context,intent,"send_email_intent")
    },
    nativeTool(context,"send_sms_intent","Открыть черновик SMS. Пользователь сам нажимает «Отправить».",LocalToolOption.SystemIntents,
        mapOf("phone_number" to "string","body" to "string"),listOf("phone_number")) { args ->
        val phone=nativeText(args,"phone_number",true,64)!!; require(phone.matches(Regex("[+0-9 ()-]{1,64}"))) { "Некорректный номер телефона." }
        val intent=Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",phone,null)); nativeText(args,"body")?.let { intent.putExtra("sms_body",it) }
        dispatchNative(context,intent,"send_sms_intent")
    },
    nativeTool(context,"open_wifi_settings","Открыть настройки Wi-Fi.",LocalToolOption.SystemIntents,emptyMap()) { dispatchNative(context,Intent(Settings.ACTION_WIFI_SETTINGS),"open_wifi_settings") },
    nativeTool(context,"show_location_on_map","Открыть место или адрес в приложении карт.",LocalToolOption.SystemIntents,mapOf("query" to "string"),listOf("query")) { args ->
        dispatchNative(context,Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(nativeText(args,"query",true,2048)))),"show_location_on_map")
    },
)

@Suppress("DEPRECATION")
internal fun appLauncherTools(context: Context): List<Tool> = listOf(
    nativeTool(context,"launch_app","Открыть установленное приложение по имени пакета.",LocalToolOption.AppLauncher,mapOf("package_name" to "string"),listOf("package_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!)
        val intent=context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent==null) deviceToolError("Пакет не виден Android, не установлен или не имеет экрана запуска.") else dispatchNative(context,intent,"launch_app")
    },
    nativeTool(context,"open_url","Открыть URL в системном приложении. Чтение содержимого страницы не выполняется.",LocalToolOption.AppLauncher,mapOf("url" to "string","package_name" to "string"),listOf("url")) { args ->
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse(safeNativeUrl(nativeText(args,"url",true,8192)!!)))
        nativeText(args,"package_name",max=255)?.let { intent.setPackage(nativePackageName(it)) }
        dispatchNative(context,intent,"open_url")
    },
    nativeTool(context,"list_installed_apps","Список видимых Android приложений. Скрытые системой пакеты не перечисляются.",LocalToolOption.AppLauncher,
        mapOf("filter" to "string","user_only" to "boolean","limit" to "integer","include_no_launcher" to "boolean","include_permissions" to "boolean")) { args ->
        val pm=context.packageManager; val filter=nativeText(args,"filter",max=512).orEmpty().lowercase()
        val userOnly=personalBoolean(args,"user_only",true); val limit=personalInt(args,"limit",200,1..1000)
        val includeNoLauncher=personalBoolean(args,"include_no_launcher",false)||filter.isNotBlank(); val permissions=personalBoolean(args,"include_permissions",false)
        val launchers=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map { it.activityInfo.packageName }.toSet()
        val packages=pm.getInstalledPackages(if(permissions) PackageManager.GET_PERMISSIONS else 0)
            .filter { (!userOnly || it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM)==0) && (includeNoLauncher || it.packageName in launchers) }
            .map { it to (it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName) }.filter { filter.isEmpty() || filter in it.first.packageName.lowercase() || filter in it.second.lowercase() }.sortedBy { it.second.lowercase() }
        buildJsonObject { put("apps",buildJsonArray { packages.take(limit).forEach { (info,label) -> add(buildJsonObject { put("label",label); put("package",info.packageName); put("version",info.versionName.orEmpty()); put("has_launcher",info.packageName in launchers); if(permissions) put("permissions",buildJsonArray { info.requestedPermissions?.take(256)?.forEach { add(it) } }) }) } }); put("returned",minOf(packages.size,limit)); put("matched",packages.size); put("visibility","android_package_visibility"); put("note","Android может скрывать пакеты; это не полный список всех установленных приложений.") }
    },
    nativeTool(context,"list_app_activities","Список Activity видимого Android пакета.",LocalToolOption.AppLauncher,mapOf("package_name" to "string","filter" to "string","exported_only" to "boolean","limit" to "integer"),listOf("package_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!); val filter=nativeText(args,"filter",max=512).orEmpty().lowercase(); val exportedOnly=personalBoolean(args,"exported_only",false); val limit=personalInt(args,"limit",100,1..500)
        val info=try { context.packageManager.getPackageInfo(pkg,PackageManager.GET_ACTIVITIES) } catch (_: PackageManager.NameNotFoundException) { return@nativeTool deviceToolError("Пакет не установлен или скрыт Android.") }
        val activities=info.activities.orEmpty().filter { (!exportedOnly || it.exported) && (filter.isEmpty() || filter in it.name.lowercase()) }
        buildJsonObject { put("package",pkg); put("matched",activities.size); put("activities",buildJsonArray { activities.take(limit).forEach { add(buildJsonObject { put("name",it.name); put("exported",it.exported); put("enabled",it.enabled) }) } }) }
    },
    nativeTool(context,"launch_activity","Открыть экспортированную Activity установленного приложения.",LocalToolOption.AppLauncher,mapOf("package_name" to "string","activity_name" to "string"),listOf("package_name","activity_name")) { args ->
        val pkg=nativePackageName(nativeText(args,"package_name",true,255)!!); val activity=nativeActivityName(pkg,nativeText(args,"activity_name",true,512)!!); val component=ComponentName(pkg,activity)
        val info=try { context.packageManager.getActivityInfo(component,0) } catch (_: PackageManager.NameNotFoundException) { return@nativeTool deviceToolError("Activity не найдена или скрыта Android.") }
        if (!info.exported || !info.enabled || info.applicationInfo?.enabled==false) deviceToolError("Activity не экспортирована или отключена.")
        else dispatchNative(context,Intent(Intent.ACTION_MAIN).setComponent(component),"launch_activity")
    },
)
