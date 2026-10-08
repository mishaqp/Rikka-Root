// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val ALLOWED_DIRECTIONS = setOf("up", "down", "left", "right")
private const val SCROLL_GESTURE_MS = 300L

private fun findScrollableAt(
    root: RootScreenNode,
    x: Int,
    y: Int,
): RootScreenNode? {
    val rect = Rect()
    fun matches(n: RootScreenNode): Boolean {
        n.getBoundsInScreen(rect)
        return n.isScrollable && rect.contains(x, y)
    }
    fun walk(n: RootScreenNode): RootScreenNode? {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c)?.let { return it }
        }
        return if (matches(n)) n else null
    }
    return walk(root)
}

private fun findFirstScrollable(root: RootScreenNode): RootScreenNode? {
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
        val c = root.getChild(i) ?: continue
        findFirstScrollable(c)?.let { return it }
    }
    return null
}

fun scrollTool(
    service: RootScreenService,
): Tool = Tool(
    name = "scroll",
    description = """
        Scroll the active window (up/down/left/right). With x/y, scrolls the container at that
        point; otherwise the first scrollable container. Uses real horizontal scroll actions for
        left/right and falls back to a direction-true swipe. success=true means the scroll was
        accepted; after.screen_changed is the ground truth, and a note is added when the scroll
        was accepted but nothing moved (usually end of list).
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("up"); add("down"); add("left"); add("right")
                    })
                })
                put("x", buildJsonObject { put("type", "number"); put("description", "Optional anchor px") })
                put("y", buildJsonObject { put("type", "number"); put("description", "Optional anchor px") })
            },
            required = listOf("direction")
        )
    },
    execute = { input ->
        val wakeOk = service.wakeIfNeeded()
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        if (direction == null || direction !in ALLOWED_DIRECTIONS) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "direction must be one of [up, down, left, right]")
                    }.toString()
                )
            )
        }
        val anchorX = input.jsonObject["x"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
        val anchorY = input.jsonObject["y"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }

        val payload = service.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", svc.lastTreeError ?: "no_active_window")
                    put("recovery", "Root UIAutomator не вернул дерево. Откройте и разблокируйте нужное приложение; для нестандартного интерфейса используйте take_screenshot и координаты. Accessibility не подключён.") }

                val target = if (anchorX != null && anchorY != null) {
                    findScrollableAt(root, anchorX.toInt(), anchorY.toInt())
                } else {
                    findFirstScrollable(root)
                }

                var usedFallback = false
                val ok = if (target != null) {
                    val action = when (direction) {
                        "down" -> RootScreenNode.ACTION_SCROLL_FORWARD
                        "up" -> RootScreenNode.ACTION_SCROLL_BACKWARD
                        "right" -> RootScreenNode.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                        else /* left */ -> RootScreenNode.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    }
                    val supported = target.actionList.any { it.id == action }
                    if (supported) {
                        target.performAction(action)
                    } else {
                        // Container does not support this axis; fall back to a direction-true swipe.
                        usedFallback = true
                        dispatchFallbackSwipe(svc, root, direction)
                    }
                } else {
                    usedFallback = true
                    dispatchFallbackSwipe(svc, root, direction)
                }
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "no_scroll_action_accepted")
                    if (!wakeOk) put("wake_failed", true)
                }
            }
        }
        val finalPayload = run {
            val after = payload["after"] as? JsonObject
            val changed = (after?.get("screen_changed") as? JsonPrimitive)?.booleanOrNull
            val succeeded = (payload["success"] as? JsonPrimitive)?.booleanOrNull == true
            if (succeeded && changed == false) {
                JsonObject(
                    payload + ("note" to JsonPrimitive(
                        "scroll accepted but the screen did not change; likely already at the end"
                    ))
                )
            } else payload
        }
        listOf(UIMessagePart.Text(finalPayload.toString()))
    }
)

/**
 * Swipe-based fallback when no scrollable node was found (or the node doesn't support the
 * requested axis). Shared by both the node and no-node paths so left/right and up/down
 * behave identically whichever way scroll() reaches them.
 */
private suspend fun dispatchFallbackSwipe(
    svc: RootScreenService,
    root: RootScreenNode,
    direction: String,
): Boolean {
    val rect = Rect()
    root.getBoundsInScreen(rect)
    val cx = (rect.left + rect.right) / 2f
    val cy = (rect.top + rect.bottom) / 2f
    val w = (rect.width() / 3f).coerceAtLeast(100f)
    val h = (rect.height() / 3f).coerceAtLeast(100f)
    val (sx, sy, ex, ey) = when (direction) {
        "down" -> floatArrayOf(cx, cy + h, cx, cy - h)
        "up" -> floatArrayOf(cx, cy - h, cx, cy + h)
        "right" -> floatArrayOf(cx + w, cy, cx - w, cy)
        else /* left */ -> floatArrayOf(cx - w, cy, cx + w, cy)
    }.let { arr -> Quadruple(arr[0], arr[1], arr[2], arr[3]) }
    return svc.swipe(sx.toDouble(), sy.toDouble(), ex.toDouble(), ey.toDouble(), SCROLL_GESTURE_MS)
}

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
