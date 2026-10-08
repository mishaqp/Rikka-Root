// Adapted from RikkaHub Agent (AGPL-3.0): root WAKEUP replaces the wake-lock backend.
package me.rerere.rikkahub.data.ai.tools.local

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Reusable helpers so launch_app and any other tool that needs the screen on can opt in
 * without duplicating the root WAKEUP command.
 */
internal object ScreenWaker {
    fun isInteractive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun isKeyguardLocked(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    fun isKeyguardSecure(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardSecure == true
}

fun wakeScreenTool(context: Context, service: RootScreenService): Tool = Tool(
    name = "wake_screen",
    description = "Turn the screen on using root input keyevent WAKEUP; no wake lock is held. Call BEFORE launch_app / screen-automation when the screen is off — otherwise activities launch behind the lock screen and read_window_tree sees nothing. Doesn't bypass secure keyguards. Returns {success, was_off, woke, keyguard_locked, keyguard_secure}.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hold_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Compatibility parameter (default 3000, max 30000); root WAKEUP is immediate and holds no wake lock")
                })
            }
        )
    },
    execute = { input ->
        @Suppress("UNUSED_VARIABLE") val holdMs = input.jsonObject["hold_ms"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(500, 30_000)?.toLong() ?: 3_000L
        val payload = service.withService(treeRequired = false) {
            val wasOff = !ScreenWaker.isInteractive(context)
            val woke = if (wasOff) service.wakeIfNeeded() else false
            val keyLocked = ScreenWaker.isKeyguardLocked(context)
            val keySecure = ScreenWaker.isKeyguardSecure(context)
            buildJsonObject {
                    put("success", !wasOff || woke)
                    put("was_off", wasOff)
                    put("woke", woke)
                    put("keyguard_locked", keyLocked)
                    put("keyguard_secure", keySecure)
                    if (keyLocked && keySecure) {
                        put("warn", "Экран включён, но защищён PIN-кодом. Пользователь должен сам разблокировать устройство.")
                    }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
