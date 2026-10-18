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

// The observed node preserves semantic digits; only its existing explicit seconds
// countdown suffix is volatile, e.g. «Отправить код (59с)».
private val NODE_COUNTDOWN = Regex("(\\()\\d+(\\s*[сs]\\))")
// Expanded formats belong only to a promoted action ancestor, never the observed node itself.
private val ANCESTOR_AGE = Regex(
    """(?<![\p{L}\p{N}])\d+\s+(?:сек(?:унд[уы]?)?|мин(?:ут[уы]?)?|час(?:а|ов)?|д(?:ень|ня|ней))\.?\s+назад(?![\p{L}\p{N}_])""",
    RegexOption.IGNORE_CASE,
)
private val ANCESTOR_PROGRESS = Regex("""^\s*\d{1,3}(?:[.,]\d+)?\s*%\s*$""")
private val ANCESTOR_CLOCK = Regex("""^\s*(?:\d{1,2}:)?\d{1,2}:[0-5]\d\s*$""")
private val ANCESTOR_COUNTER = Regex(
    """^(\s*(?:сообщения|уведомления|messages|notifications)\s*\()\d+(\)\s*)$""",
    RegexOption.IGNORE_CASE,
)
private fun stableNodeLabel(text: String, ancestor: Boolean = false): String {
    val stable = text.replace(NODE_COUNTDOWN) { "${it.groupValues[1]}#${it.groupValues[2]}" }
    if (!ancestor) return stable
    return when {
        ANCESTOR_PROGRESS.matches(stable) -> "#%"
        ANCESTOR_CLOCK.matches(stable) -> "#:#"
        else -> stable.replace(ANCESTOR_AGE, "# назад").replace(ANCESTOR_COUNTER) {
            "${it.groupValues[1]}#${it.groupValues[2]}"
        }
    }
}

/**
 * What makes a node "the same element" for a later action. Bounds move when the keyboard
 * opens, an edited field's own text changes, and timers ("Отправить код (59с)") tick, so
 * none of those may invalidate the handle; a different element at the same index still does.
 */
private fun nodeIdentity(node: RootScreenNode, withText: Boolean, ancestor: Boolean = false): List<String> = listOf(
    node.packageName.orEmpty(), node.className.orEmpty(),
    if (withText && !node.isEditable) stableNodeLabel(node.text.orEmpty(), ancestor) else "",
    stableNodeLabel(node.contentDescription.orEmpty(), ancestor), node.viewIdResourceName.orEmpty(),
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
            addAll(nodeIdentity(target, withText = true, ancestor = depth > 0))
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
    description = "Прочитать дерево UIAutomator активного окна через root. По умолчанию показаны видимые интерактивные узлы и узлы с текстом/описанием; verbose=true включает остальные. max_nodes: 1–2000, по умолчанию 500; package_name защищает от чтения другого приложения. Возвращает nodes с node_id для click_node/set_text, truncated, total_seen и screen_state. Сначала используйте дерево и find_node; take_screenshot нужен для визуальных деталей или интерфейса без доступных узлов.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("verbose", buildJsonObject {
                    put("type", "boolean"); put("description", "Включить узлы вне стандартного фильтра видимых/интерактивных: по умолчанию false."); put("default", false) })
                put("max_nodes", buildJsonObject {
                    put("type", "integer"); put("description", "Максимум узлов дерева: 1–2000, по умолчанию 500; проверяйте truncated."); put("minimum", 1); put("maximum", 2000); put("default", 500) })
                put("package_name", buildJsonObject {
                    put("type", "string"); put("description", "Необязательное имя ожидаемого приложения; при другом активном пакете чтение отклоняется.") })
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
