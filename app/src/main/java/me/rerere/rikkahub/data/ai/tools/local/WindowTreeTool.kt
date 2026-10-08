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

internal fun nodeToJson(
    node: RootScreenNode,
    windowId: Int,
    traversalIndex: Int,
): JsonObject {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return buildJsonObject {
        put("node_id", "${windowId}:${traversalIndex}")
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
        service.wakeIfNeeded()
        val verbose = input.jsonObject["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxNodesRaw = input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_NODES
        val maxNodes = maxNodesRaw.coerceIn(1, MAX_NODES_HARD_CEILING)
        val pkgFilter = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull

        val payload = service.withService { svc ->
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
