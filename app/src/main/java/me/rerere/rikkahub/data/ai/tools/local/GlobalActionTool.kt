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
    description = """
        Perform an Android system action: back / home / recents / notifications / quick_settings /
        lock_screen / power_dialog. success only means the OS accepted the action; verify the
        outcome via the returned "after" object (e.g. after home, confirm shade_open is absent
        and the launcher package is foreground before assuming you are on the home screen).
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { ACTION_MAP.keys.forEach { add(it) } })
                    put("description", "Which system action to perform")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val wakeOk = service.wakeIfNeeded()
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        val code = action?.let { ACTION_MAP[it] }
        if (action == null || code == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "action must be one of ${ACTION_MAP.keys.toList()}")
                    }.toString()
                )
            )
        }
        val payload = service.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val ok = svc.performGlobalAction(code)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "rejected_by_os")
                    if (!wakeOk) put("wake_failed", true)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
