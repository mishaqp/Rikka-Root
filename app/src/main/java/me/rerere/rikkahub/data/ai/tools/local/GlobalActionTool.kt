// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val ACTION_MAP: Map<String, Int> = mapOf(
    "back" to RootScreenService.GLOBAL_ACTION_BACK,
    "home" to RootScreenService.GLOBAL_ACTION_HOME,
    "recents" to RootScreenService.GLOBAL_ACTION_RECENTS,
    "notifications" to RootScreenService.GLOBAL_ACTION_NOTIFICATIONS,
    "quick_settings" to RootScreenService.GLOBAL_ACTION_QUICK_SETTINGS,
    "lock_screen" to RootScreenService.GLOBAL_ACTION_LOCK_SCREEN,
    "power_dialog" to RootScreenService.GLOBAL_ACTION_POWER_DIALOG,
)

fun globalActionTool(
    service: RootScreenService,
): Tool = Tool(
    name = "global_action",
    description = "Выполнить системное действие Android через root: back, home, recents, notifications, quick_settings, lock_screen или power_dialog. Для нажатия элемента используйте click_node. success означает только принятие действия системой; проверьте after, активное приложение, shade_open и screen_changed перед выводом об успехе.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { ACTION_MAP.keys.forEach { add(it) } }); put("description", "Системное действие из перечисления; success подтверждает принятие, результат проверяйте по after.") })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        screenArgumentError(input, "action" to "string")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        val code = action?.let { ACTION_MAP[it] }
        if (action == null || code == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите action из списка: ${ACTION_MAP.keys.toList()}.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true) { svc ->
            withActionEnvelope(svc) { _ ->
                val ok = svc.performGlobalAction(code)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "rejected_by_os")
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
