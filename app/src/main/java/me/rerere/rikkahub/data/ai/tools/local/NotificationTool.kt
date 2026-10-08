// Adapted from ExTV/rikkahub-agent, local/NotificationTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val CHANNEL_ID = "rikkahub_ai_tool"
private const val NOTIFICATION_TAG = "rikkahub_ai_tool"

private suspend fun nextNotificationId(context: Context): Int? = withContext(Dispatchers.IO) {
    // Only an integer counter is stored; no content, identifiers, credentials or secrets.
    val preferences = context.applicationContext.getSharedPreferences("tool_notification_ids", Context.MODE_PRIVATE)
    NotificationIdAllocator(
        readNext = { preferences.getInt("next_id", NotificationIdAllocator.FIRST_ID) },
        storeNext = { preferences.edit().putInt("next_id", it).commit() },
    ).allocate()
}

fun notificationTool(context: Context, conversationId: String? = null): Tool = Tool(
    name = "post_notification",
    description = "Post an Android notification after approval. title is required, body and id are optional. Tapping it opens the originating conversation.",
    needsApproval = { true },
    parameters = { InputSchema.Obj(buildJsonObject {
        put("title", buildJsonObject { put("type", "string") })
        put("body", buildJsonObject { put("type", "string") })
        put("id", buildJsonObject { put("type", "integer"); put("minimum", 0) })
    }, required = listOf("title")) },
    execute = { input -> deviceToolResult {
        val params = input as? JsonObject ?: return@deviceToolResult deviceToolError("Ожидался объект параметров.")
        val title = params.textArgument("title")?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return@deviceToolResult deviceToolError("Нужен непустой title длиной до 512 символов.")
        val body = params.textArgument("body")
        if ((body?.length ?: 0) > 16_000) return@deviceToolResult deviceToolError("body должен быть длиной до 16000 символов.")
        val explicitId = if ("id" in params) (params["id"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } else null
        if ("id" in params && explicitId == null) return@deviceToolResult deviceToolError("id должен быть неотрицательным целым числом.")
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@deviceToolResult deviceToolError("Разрешите уведомления при включении функции в настройках ассистента.", Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return@deviceToolResult deviceToolError("Уведомления приложения отключены в настройках Android.")
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.assistant_page_local_tools_notification_channel)).build())
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return@deviceToolResult deviceToolError("Канал «Уведомления ассистента» отключён в настройках Android.")
        }
        val id = explicitId ?: nextNotificationId(context)
            ?: return@deviceToolResult deviceToolError("Не удалось сохранить новый ID уведомления. Уведомление не опубликовано.")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title).setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true)
        body?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        if (!conversationId.isNullOrBlank()) {
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId)
                // PendingIntent identity includes data, and survives process restarts/hash collisions.
                data = Uri.Builder().scheme("rikkaroot").authority("tool-notification").appendPath(conversationId).build()
            }
            builder.setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        // Tool IDs must not replace ordinary chat/foreground service notifications (null tag).
        manager.notify(NOTIFICATION_TAG, id, builder.build())
        buildJsonObject { put("success", true); put("id", id) }
    } },
)
