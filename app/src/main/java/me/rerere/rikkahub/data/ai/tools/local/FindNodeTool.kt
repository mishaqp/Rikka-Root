// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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

private val ALLOWED_BY = setOf("text", "content_description", "view_id_resource_name")
private const val MATCH_CAP = 50

private data class NodeMatch(val node: RootScreenNode, val traversalIndex: Int)

private data class FingerprintedNodeId(val windowId: Int, val index: Int, val fingerprint: String)

private fun parseFingerprintedNodeId(raw: String): FingerprintedNodeId? {
    val parts = raw.split(':')
    if (parts.size != 3 || !Regex("[0-9a-f]{1,16}").matches(parts[2])) return null
    val position = parseNodeId(parts.take(2).joinToString(":")) ?: return null
    return FingerprintedNodeId(position.first, position.second, parts[2])
}

private fun invalidNodeId(raw: String): JsonObject = buildJsonObject {
    if (parseNodeId(raw) != null) {
        put("error", "stale_node_id")
        put("message", "Экран изменился, прочитайте дерево заново через read_window_tree и используйте новый node_id.")
    } else {
        put("error", "Укажите node_id с отпечатком элемента из нового вызова read_window_tree.")
    }
}

/**
 * Matches nodes via the same traversal (and therefore the same traversalIndex numbering)
 * as read_window_tree, so returned node_ids agree across tools and a node visible in the
 * tree can never be no_match. Exact string match per axis. recycle stays OFF because
 * matched nodes are retained for clicking.
 */
private fun findMatchesUnified(
    svc: RootScreenService,
    root: RootScreenNode,
    by: String,
    value: String,
): Pair<List<NodeMatch>, Boolean> {
    val out = mutableListOf<NodeMatch>()
    val (_, _, truncated) = svc.traverseTree(
        root = root,
        filter = { n, _ ->
            when (by) {
                "text" -> n.text?.toString() == value
                "content_description" -> n.contentDescription?.toString() == value
                "view_id_resource_name" -> n.viewIdResourceName == value
                else -> false
            }
        },
        cap = MATCH_CAP,
        emit = { n, _, idx -> out.add(NodeMatch(n, idx)) },
    )
    return out to truncated
}

/**
 * Resolves a read_window_tree node_id back to a live node. Returns the node or null with
 * a reason string. Staleness detection: window changed, index out of range, or the node
 * is no longer visible, or its observed identity (including class/text/bounds) changed.
 */
private fun resolveByNodeId(
    svc: RootScreenService,
    root: RootScreenNode,
    windowId: Int,
    traversalIndex: Int,
    fingerprint: String,
): Pair<RootScreenNode?, String?> {
    if (root.windowId != windowId) return null to "stale_node_id"
    var found: RootScreenNode? = null
    // filter=true makes emitted == seen, so cap=traversalIndex stops exactly at the node.
    svc.traverseTree(
        root = root,
        filter = { _, _ -> true },
        cap = traversalIndex,
        emit = { n, _, idx -> if (idx == traversalIndex) found = n },
    )
    val node = found ?: return null to "stale_node_id"
    if (!node.isVisibleToUser || nodeFingerprint(node) != fingerprint) return null to "stale_node_id"
    return node to null
}

private fun parseSelector(input: kotlinx.serialization.json.JsonElement): Triple<String?, String?, String?> {
    val by = input.jsonObject["by"]?.jsonPrimitive?.contentOrNull
    val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
    val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
    return Triple(by, value, pkg)
}

fun findNodeTool(
    service: RootScreenService,
): Tool = Tool(
    name = "find_node",
    description = "Найти узлы активного окна по точному value: by=text, content_description или view_id_resource_name. Сначала прочитайте read_window_tree; для действия передайте найденный node_id в click_node или set_text. package_name ограничивает приложение. Возвращает не более 50 matches и screen_state; truncated=true означает дополнительные совпадения, отсутствие truncated — отсутствие усечения. Индексы node_id совпадают с read_window_tree.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") }); put("description", "Ось точного поиска: text, content_description или view_id_resource_name. Передавайте вместе с value, если нет node_id.") })
                put("value", buildJsonObject {
                    put("type", "string"); put("description", "Точное строковое значение выбранной оси поиска; нужно вместе с by.") })
                put("package_name", buildJsonObject {
                    put("type", "string"); put("description", "Необязательное имя ожидаемого активного приложения для защиты от действия в другом окне.") })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        screenArgumentError(input, "by" to "string", "value" to "string", "package_name" to "string")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val (by, value, pkgFilter) = parseSelector(input)
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите by: text, content_description или view_id_resource_name, и искомое значение в value.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true) { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject {
                    put("error", svc.lastTreeError ?: "no_active_window")
                    put("recovery", "Root UIAutomator не вернул дерево. Откройте и разблокируйте нужное приложение; для нестандартного интерфейса используйте take_screenshot и координаты. Accessibility не подключён.")
                    put("matches", buildJsonArray {})
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("matches", buildJsonArray {})
                }
            }
            val (matches, truncated) = findMatchesUnified(svc, root, by, value)
            buildJsonObject {
                put("matches", buildJsonArray {
                    matches.forEach { m -> add(nodeToJson(m.node, root.windowId, m.traversalIndex)) }
                })
                if (truncated) put("truncated", true)
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun clickNodeTool(
    service: RootScreenService,
): Tool = Tool(
    name = "click_node",
    description = "Нажать узел через root. Предпочитайте node_id из read_window_tree/find_node; альтернатива — by и value с необязательным nth (от 0, по умолчанию 0) и package_name. Если узел не нажимаемый, выбирается ближайший нажимаемый предок. При stale_node_id перечитайте дерево: действие не выполняется по устаревшему узлу. Для координат без узла есть tap. Возвращает success и after; проверьте screen_changed.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string"); put("description", "Дескриптор windowId:index:fingerprint из read_window_tree/find_node. Предпочтительный выбор; stale_node_id требует нового чтения дерева.") })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") }); put("description", "Ось точного поиска: text, content_description или view_id_resource_name. Передавайте вместе с value, если нет node_id.") })
                put("value", buildJsonObject { put("type", "string"); put("description", "Точное строковое значение выбранной оси поиска; нужно вместе с by.") })
                put("package_name", buildJsonObject { put("type", "string"); put("description", "Необязательное имя ожидаемого активного приложения для защиты от действия в другом окне.") })
                put("nth", buildJsonObject {
                    put("type", "integer"); put("description", "Номер совпадения с нуля, по умолчанию 0. Применяется к поиску by/value."); put("minimum", 0); put("default", 0) })
            },
        )
    },
    execute = { input ->
        screenArgumentError(input, "by" to "string", "value" to "string", "package_name" to "string",
            "node_id" to "string", "nth" to "integer")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull
            ?: if (input.jsonObject.containsKey("nth")) -1 else 0
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseFingerprintedNodeId(it) }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(UIMessagePart.Text(invalidNodeId(rawNodeId).toString()))
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите node_id из read_window_tree или by (text, content_description, view_id_resource_name) вместе с value.")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите nth — номер совпадения, начиная с 0.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true) { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", svc.lastTreeError ?: "no_active_window")
                    put("recovery", "Root UIAutomator не вернул дерево. Откройте и разблокируйте нужное приложение; для нестандартного интерфейса используйте take_screenshot и координаты. Accessibility не подключён.") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                val (target, staleReason) = if (parsedNodeId != null) {
                    resolveByNodeId(svc, root, parsedNodeId.windowId, parsedNodeId.index, parsedNodeId.fingerprint)
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@withActionEnvelope buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "Экран изменился, прочитайте дерево заново через read_window_tree и используйте новый node_id.")
                        }
                    }
                }
                val clickable = svc.resolveClickable(target)
                    ?: return@withActionEnvelope buildJsonObject { put("error", "no_clickable_ancestor") }
                val ok = clickable.performAction(RootScreenNode.ACTION_CLICK)
                buildJsonObject {
                    put("success", ok)
                    put("clicked", buildJsonObject {
                        val rect = android.graphics.Rect()
                        clickable.getBoundsInScreen(rect)
                        put("bounds", buildJsonArray {
                            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
                        })
                        put("text", clickable.text?.toString() ?: "")
                        put("resolved_via_ancestor", clickable !== target)
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Type text into an editable accessibility node by selector. Works on standard input fields
 * (URL bars, search boxes, form fields, IME-driven text views). Does NOT work on terminals
 * like Termux because they render to a Surface and do not expose editable nodes; for Termux
 * use termux_run_command instead.
 */
fun setTextTool(
    service: RootScreenService,
): Tool = Tool(
    name = "set_text",
    description = "Ввести или заменить text в редактируемом поле через root: node_id предпочтительнее by/value, nth — от 0, по умолчанию 0. Подходит для поиска, форм и адресной строки; не подходит для терминалов Termux — используйте termux_run_command, если он включён, иначе объясните ограничение. Нужна поддержка Android input keycombination (Ctrl+A). ASCII без буквального %s вводится через input text; кириллица и эмодзи — через временный буфер и PASTE. Прежний буфер восстанавливается, если пользователь не скопировал новые данные; content:// в буфере сохраняется с отказом до очистки поля. При stale_node_id перечитайте дерево. Результат содержит success, set_to и after; проверьте after.screen_changed.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string"); put("description", "Дескриптор windowId:index:fingerprint из read_window_tree/find_node. Предпочтительный выбор; stale_node_id требует нового чтения дерева.") })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") }); put("description", "Ось точного поиска: text, content_description или view_id_resource_name. Передавайте вместе с value, если нет node_id.") })
                put("value", buildJsonObject {
                    put("type", "string"); put("description", "Точное строковое значение выбранной оси поиска; нужно вместе с by.") })
                put("text", buildJsonObject {
                    put("type", "string"); put("description", "Строка для установки в редактируемое поле. Пустая строка очищает поле; для терминалов используйте командный инструмент.") })
                put("package_name", buildJsonObject {
                    put("type", "string"); put("description", "Необязательное имя ожидаемого активного приложения для защиты от действия в другом окне.") })
                put("nth", buildJsonObject {
                    put("type", "integer"); put("description", "Номер совпадения с нуля, по умолчанию 0. Применяется к поиску by/value."); put("minimum", 0); put("default", 0) })
            },
            required = listOf("text")
        )
    },
    execute = { input ->
        screenArgumentError(input, "by" to "string", "value" to "string", "package_name" to "string",
            "node_id" to "string", "nth" to "integer", "text" to "string")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull
            ?: if (input.jsonObject.containsKey("nth")) -1 else 0
        val newText = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseFingerprintedNodeId(it) }
        if (newText == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "Укажите текст для ввода в text.") }.toString()
                )
            )
        }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(UIMessagePart.Text(invalidNodeId(rawNodeId).toString()))
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите node_id из read_window_tree или by (text, content_description, view_id_resource_name) вместе с value.")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "Укажите nth — номер совпадения, начиная с 0.") }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true) { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", svc.lastTreeError ?: "no_active_window")
                    put("recovery", "Root UIAutomator не вернул дерево. Откройте и разблокируйте нужное приложение; для нестандартного интерфейса используйте take_screenshot и координаты. Accessibility не подключён.") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                val (target, staleReason) = if (parsedNodeId != null) {
                    resolveByNodeId(svc, root, parsedNodeId.windowId, parsedNodeId.index, parsedNodeId.fingerprint)
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@withActionEnvelope buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "Экран изменился, прочитайте дерево заново через read_window_tree и используйте новый node_id.")
                        }
                    }
                }
                // ACTION_SET_TEXT requires the node to be editable. Walk up the parent chain
                // looking for one - some apps wrap their EditText in a non-editable container.
                var editable: RootScreenNode? = target
                while (editable != null && !editable.isEditable) {
                    editable = editable.parent
                }
                if (editable == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "node_not_editable")
                        put("recovery", "The matched node is not an editable input. Terminals (Termux) render natively and do not expose editable nodes; if the termux_run_command tool is enabled use it instead, otherwise report that terminals cannot be typed into.")
                    }
                }
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        RootScreenNode.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        newText
                    )
                }
                val ok = editable.performAction(RootScreenNode.ACTION_SET_TEXT, args)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "action_rejected")
                    put("set_to", newText)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
