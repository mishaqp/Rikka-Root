// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val DEFAULT_MAX_NODES = 500
private const val MAX_NODES_HARD_CEILING = 2000

// Preserve order numbers, amounts and other semantic digits. Only the explicit
// seconds countdown suffix is volatile, e.g. «Отправить код (59с)».
private val NODE_COUNTDOWN = Regex("(\\()\\d+(\\s*[сs]\\))")
private fun stableNodeLabel(text: String): String = text.replace(NODE_COUNTDOWN) {
    "${it.groupValues[1]}#${it.groupValues[2]}"
}

/**
 * What makes a node "the same element" for a later action. Bounds move when the keyboard
 * opens, an edited field's own text changes, and timers ("Отправить код (59с)") tick, so
 * none of those may invalidate the handle; a different element at the same index still does.
 */
private fun nodeIdentity(node: RootScreenNode, withText: Boolean): List<String> = listOf(
    node.packageName.orEmpty(), node.className.orEmpty(),
    if (withText && !node.isEditable) stableNodeLabel(node.text.orEmpty()) else "",
    stableNodeLabel(node.contentDescription.orEmpty()), node.viewIdResourceName.orEmpty(),
    node.isClickable.toString(), node.isEditable.toString(), node.isScrollable.toString(),
)

/** Include the actual click/edit targets, since a child handle can promote to an ancestor. */
internal fun nodeFingerprint(node: RootScreenNode): String = fnv1a64(buildList {
    add("node")
    addAll(nodeIdentity(node, withText = true))
    fun addActionTarget(kind: String, accepts: (RootScreenNode) -> Boolean) {
        var target: RootScreenNode? = node
        var depth = 0
        while (target != null && !accepts(target)) {
            target = target.parent
            depth++
        }
        add(kind)
        if (target == null) {
            add("none")
        } else {
            add(depth.toString())
            // A child handle can trigger this ancestor's action. Its non-editable
            // label is part of that action's identity, including semantic numbers.
            addAll(nodeIdentity(target, withText = true))
        }
    }
    addActionTarget("click") { it.isClickable }
    addActionTarget("edit") { it.isEditable }
}).toULong().toString(16)

internal fun nodeToJson(
    node: RootScreenNode,
    windowId: Int,
    traversalIndex: Int,
): JsonObject {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return buildJsonObject {
        put("node_id", "${windowId}:${traversalIndex}:${nodeFingerprint(node)}")
        put("bounds", buildJsonArray {
            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
        })
        put("class", node.className?.toString() ?: "")
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let {
            put("content_description", it)
        }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { put("view_id", it) }
        put("clickable", node.isClickable)
        put("scrollable", node.isScrollable)
        put("editable", node.isEditable)
        put("enabled", node.isEnabled)
    }
}

internal fun defaultFilter(n: RootScreenNode, depth: Int): Boolean {
    if (!n.isVisibleToUser) return false
    if (n.isClickable || n.isScrollable || n.isEditable) return true
    val text = n.text?.toString().orEmpty()
    val cd = n.contentDescription?.toString().orEmpty()
    return text.isNotEmpty() || cd.isNotEmpty()
}

fun readWindowTreeTool(
    service: RootScreenService,
): Tool = Tool(
    name = "read_window_tree",
    description = "Snapshot of the active window's UIAutomator node tree via root. Default filters to visible nodes that are clickable / scrollable / editable / have text or content_description. verbose=true skips the filter (use sparingly). max_nodes caps result (default 500, max 2000). package_name optionally restricts + errors if the foreground app doesn't match. Every node carries a node_id you can pass directly to click_node / set_text (preferred over by/value or coordinates). The result includes screen_state identifying the current surface (package, shade_open, ime_visible, display size).",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("verbose", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return all nodes (default false applies the filter)")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned nodes (default 500, max 2000)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "If set, return wrong_foreground_app error if the foreground app does not match")
                })
            }
        )
    },
    execute = { input ->
        screenArgumentError(input, "verbose" to "boolean", "max_nodes" to "integer", "package_name" to "string")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val verbose = input.jsonObject["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxNodesRaw = input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull
            ?: if (input.jsonObject.containsKey("max_nodes")) 0 else DEFAULT_MAX_NODES
        if (maxNodesRaw !in 1..MAX_NODES_HARD_CEILING) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "Укажите max_nodes от 1 до $MAX_NODES_HARD_CEILING.")
            }.toString()))
        }
        val maxNodes = maxNodesRaw
        val pkgFilter = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull

        val payload = service.withService(wakeBeforeSnapshot = true) { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject {
                    put("error", svc.lastTreeError ?: "no_active_window")
                    put("recovery", "Root UIAutomator не вернул дерево. Откройте и разблокируйте нужное приложение; для нестандартного интерфейса используйте take_screenshot и координаты. Accessibility не подключён.")
                    put("nodes", buildJsonArray { })
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("nodes", buildJsonArray { })
                }
            }
            val nodes = mutableListOf<JsonObject>()
            val (emitted, seen, truncated) = svc.traverseTree(
                root = root,
                filter = if (verbose) ({ _, _ -> true }) else (::defaultFilter),
                cap = maxNodes,
                emit = { n, _, idx ->
                    nodes.add(nodeToJson(n, root.windowId, idx))
                }
            )
            buildJsonObject {
                put("nodes", buildJsonArray { nodes.forEach { add(it) } })
                put("truncated", truncated)
                put("total_seen", seen)
                put("package", pkg)
                root.window?.title?.toString()?.let { put("window_title", it) } ?: put("window_title", "")
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
