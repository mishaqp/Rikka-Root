package me.rerere.rikkahub.miniapps

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

/** Moru mini_app_launcher host rewritten for native provider/calendar/notification services. */
class MiniAppNativeHost(
    context: Context,
    private val settings: SettingsStore,
    private val providers: ProviderManager,
    private val scheduler: MiniAppAndroidReminders,
    private val fetch: MiniAppFetch,
) {
    private val context = context.applicationContext

    fun forApp(app: MiniApp, requestPermissions: suspend (List<String>) -> Boolean): MiniAppHost {
        suspend fun notifications() {
            if (Build.VERSION.SDK_INT >= 33 && !requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))) {
                throw MiniAppException("permission_denied", "Доступ к уведомлениям не предоставлен.")
            }
        }
        return MiniAppHost(
            ask = { prompt, system -> withContext(Dispatchers.IO) {
                // Wait for persisted provider/assistant settings on a cold shortcut launch.
                val current = settings.settingsFlow.first { !it.init }
                val model = miniAppAskModel(current) ?: throw MiniAppException("no_model", "Выберите модель чата в настройках ассистента или модель по умолчанию.")
                val provider = model.findProvider(current.providers) ?: throw MiniAppException("no_model", "Провайдер выбранной модели недоступен.")
                val messages = buildList {
                    if (!system.isNullOrEmpty()) add(UIMessage.system(system))
                    add(UIMessage.user(prompt))
                }
                // No chat history, tools or settings are exposed to the mini app.
                providers.getProviderByType(provider).generateText(provider, messages,
                    TextGenerationParams(model = model, tools = emptyList())).message.toText()
            } },
            notify = { title, body ->
                notifications()
                showMiniAppNotification(context, app.id,
                    MiniAppReminders.notificationIds(app.id, "_notify", JsonNull).first(), title, body)
            },
            beforeReminderSet = { notifications() },
            reminders = scheduler.reminders,
            fetch = fetch,
            calendar = { method, args ->
                if (!requestPermissions(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))) {
                    throw MiniAppException("permission_denied", "Доступ к календарю не предоставлен.")
                }
                MiniAppCalendar(context).execute(method, args)
            },
        )
    }
}

internal fun miniAppAskModel(settings: Settings): Model? {
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId } ?: settings.assistants.firstOrNull()
    return settings.findModelById(assistant?.chatModelId, settings.chatModelId)
}
