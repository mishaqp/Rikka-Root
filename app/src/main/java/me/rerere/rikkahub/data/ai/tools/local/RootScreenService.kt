// Root transport adaptation of Agent RikkaAccessibilityService (AGPL-3.0).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.clipboard.AndroidRootClipboardInput
import me.rerere.rikkahub.root.clipboard.RootClipboardInput
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only the transport changes: Agent tools continue to use selectors, traversal and envelopes. */
class RootScreenService internal constructor(
    internal val context: Context,
    private val mayExecute: suspend () -> Boolean,
    clipboardInput: RootClipboardInput? = null,
    private val executeRoot: suspend (String, Int, Boolean, suspend () -> Boolean) -> RootProcessResult,
) {
    constructor(context: Context, manager: RootShellManager, mayExecute: suspend () -> Boolean = { true }) :
        this(context, mayExecute, executeRoot = { command, timeout, headless, livePermission ->
            manager.exec(command, timeout, headless, livePermission)
        })
    private val lock = Mutex()
    private val clipboardInput = clipboardInput ?: AndroidRootClipboardInput(context)
    internal val displayMetrics = DisplayMetrics().apply { setTo(context.resources.displayMetrics) }
    internal var rootInActiveWindow: RootScreenNode? = null
        private set
    internal var lastFailure: String? = null
        private set
    internal var lastTreeError: String? = null
        private set
    internal var currentFocus: String = ""
        private set
    internal var imeShown: Boolean = false
        private set
    internal var shadeShown: Boolean = false
        private set

    internal suspend fun withService(treeRequired: Boolean = true, block: suspend (RootScreenService) -> JsonObject): JsonObject =
        lock.withLock {
            if (!mayExecute()) return@withLock denied()
            try {
                lastFailure = null
                refreshSnapshot(treeRequired)
                if (lastFailure != null) return@withLock failure(lastFailure!!)
                val payload = block(this)
                // Preserve the action result, while explaining the root backend's exact failure.
                if (lastFailure != null && payload["error"] == null) JsonObject(payload + failure(lastFailure!!)) else payload
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure("screen_backend_failed")
            }
        }

    private fun denied() = buildJsonObject {
        put("error", "screen_permission_revoked")
        put("reason", "Автоматизация экрана отключена или разрешение инструмента отозвано. Включите функцию и подтвердите новый вызов.")
    }
    private fun failure(code: String) = buildJsonObject {
        put("error", code)
        put("reason", when (code) {
            "command_timeout" -> "Команда автоматизации экрана не завершилась вовремя; она остановлена и не повторяется."
            "root_interaction_required" -> "Для фонового вызова сначала откройте настройки Root и явно проверьте Root."
            "su_not_available", "root_not_granted", "root_verification_timeout" -> "Root недоступен. Проверьте разрешение Rikka-Root в менеджере root и повторите проверку Root в настройках."
            "screen_permission_revoked" -> "Функция отключена или её разрешение отозвано; команда не выполнена."
            "root_command_blocked" -> "Команда отклонена запретным списком Root."
            "input_text_unsupported" -> "Для ASCII-ввода через input text нужны печатные символы без последовательности %s. Кириллица и эмодзи вводятся через временный буфер обмена и root-вставку."
            "root_clipboard_unavailable" -> "Не удалось получить доступ к буферу обмена через root для ввода кириллицы или эмодзи. Поле не очищено. Проверьте Root; если прошивка ограничивает системный буфер, введите текст вручную."
            "root_clipboard_uri_restore_unsupported" -> "В буфере обмена есть фото или другой content:// URI. Android отзывает его разрешения при замене буфера, поэтому надёжное восстановление невозможно. Буфер сохранён, поле не очищено; сначала скопируйте обычный текст или вводите вручную."
            "root_clipboard_restore_failed" -> "Не удалось восстановить прежний буфер обмена после root-вставки. Проверьте содержимое буфера."
            "root_clipboard_changed" -> "Во время ввода содержимое буфера изменилось. Новое содержимое сохранено; root-вставка остановлена."
            "root_clipboard_text_too_large" -> "Текст превышает безопасный размер передачи через системный буфер (512 КБ UTF-8). Поле не очищено."
            "root_clipboard_transaction_failed" -> "Связь с root-вставкой прервалась после начала ввода. Проверьте содержимое поля и буфера; прежний буфер восстанавливается при завершении помощника."
            "root_clipboard_clear_unsupported" -> "Эта версия Android не умеет очищать буфер обмена через системный API. Пустой буфер сохранён, поле не очищено; сначала скопируйте обычный текст или введите вручную."
            "root_input_failed" -> "Android input отклонил действие. Для set_text требуется поддержка input keycombination (Ctrl+A); на старой версии Android замените текст вручную."
            "root_input_focus_unverified" -> "Не удалось подтвердить фокус выбранного текстового поля через root UIAutomator. Поле не очищено и текст не введён; откройте поле вручную и повторите чтение дерева."
            else -> "Не удалось выполнить root-команду автоматизации экрана ($code)."
        })
    }

    internal suspend fun exec(command: String, timeoutMs: Int = 10_000): RootProcessResult {
        if (RootCommandGuard.check(command) != null) return RootProcessResult(error = "root_command_blocked").also { lastFailure = it.error }
        if (!mayExecute()) return RootProcessResult(error = "screen_permission_revoked").also { lastFailure = it.error }
        val result = executeRoot(command, timeoutMs, currentCoroutineContext()[RunExecutionContext] != null, mayExecute)
        if (result.error != null) lastFailure = result.error
        else if (result.exitCode != 0) lastFailure = "root_input_failed"
        return result
    }

    internal suspend fun wakeIfNeeded(): Boolean {
        if (ScreenWaker.isInteractive(context)) return true
        val result = exec("input keyevent 224")
        return result.error == null && result.exitCode == 0
    }

    internal suspend fun refreshSnapshot(includeTree: Boolean) {
        rootInActiveWindow = null
        lastTreeError = null
        val display = exec("wm size")
        if (display.error != null || display.exitCode != 0) return
        parseRootDisplaySize(display.stdout)?.let { (w, h) -> displayMetrics.widthPixels = w; displayMetrics.heightPixels = h }
        val window = exec("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp|mInputMethodWindow|mImeInputTarget|mShowingDream|mDisplayFrozen|isVisible=true|StatusBar|NotificationShade'")
        // grep has exit code 1 when an OEM omits these diagnostics. This cannot block gestures.
        if (window.error == null && window.exitCode != 0) lastFailure = null
        currentFocus = Regex("mCurrentFocus=Window\\{[^}]*}").find(window.stdout)?.value.orEmpty()
        val orientation = exec("dumpsys input | grep -m 1 'SurfaceOrientation'")
        if (orientation.error == null && orientation.exitCode != 0) lastFailure = null
        val rotation = Regex("SurfaceOrientation:\\s*(\\d+)").find(orientation.stdout)?.groupValues?.get(1)?.toIntOrNull()
        if (rotation == 1 || rotation == 3) {
            val w = displayMetrics.widthPixels
            displayMetrics.widthPixels = displayMetrics.heightPixels
            displayMetrics.heightPixels = w
        }
        // An input-method target by itself does not prove the keyboard is visible.
        imeShown = window.stdout.lineSequence().any { it.contains("mInputMethodWindow=") && it.contains("Window{") }
        shadeShown = currentFocus.contains("NotificationShade") || currentFocus.contains("StatusBar")
        if (!includeTree || lastFailure != null) return
        val xmlFile = privateTemporaryFile(".xml")
        try {
            val result = exec("uiautomator dump ${rootScreenQuote(xmlFile.absolutePath)}", 15_000)
            if (result.error != null || result.exitCode != 0 || xmlFile.length() !in 1..MAX_XML_BYTES) {
                lastTreeError = result.error ?: "ui_tree_unavailable"
                // Root input and screencap remain available when UIAutomator has no tree.
                if (lastFailure !in setOf("screen_permission_revoked", "root_interaction_required", "su_not_available", "root_not_granted", "root_verification_timeout")) lastFailure = null
                return
            }
            val nodes = withContext(Dispatchers.IO) { parseRootWindowXml(xmlFile.readText()) }
            val root = nodes.firstOrNull() ?: return
            val windowId = (currentFocus.ifBlank { root.packageName.orEmpty() }).hashCode()
            nodes.forEach { it.owner = this; it.windowId = windowId }
            rootInActiveWindow = root
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            lastTreeError = "ui_tree_decode_failed"
        } finally {
            xmlFile.delete()
        }
    }

    internal suspend fun refreshAfterSnapshot() {
        val priorFailure = lastFailure
        refreshSnapshot(includeTree = true)
        // A failed observation after an accepted action must not report that action as failed.
        lastFailure?.let { lastTreeError = it }
        lastFailure = priorFailure
    }

    private fun privateTemporaryFile(suffix: String): File {
        val dir = File(context.cacheDir, "root-screen-control").apply { check(mkdirs() || isDirectory) }
        // Root writes an existing app-owned0600 inode; the resulting data stays readable by the app.
        return File(dir, "${UUID.randomUUID()}$suffix").also {
            check(it.createNewFile())
            check(it.setReadable(false, false) && it.setWritable(false, false) && it.setReadable(true, true) && it.setWritable(true, true))
        }
    }

    internal suspend fun tap(x: Double, y: Double, duration: Long = 50): Boolean =
        if (duration == 50L) executeInput("input tap ${x.toInt()} ${y.toInt()}")
        else executeInput("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $duration", (duration + 10_000).toInt())

    internal suspend fun swipe(sx: Double, sy: Double, ex: Double, ey: Double, duration: Long): Boolean =
        executeInput("input swipe ${sx.toInt()} ${sy.toInt()} ${ex.toInt()} ${ey.toInt()} $duration", (duration + 10_000).toInt())

    private suspend fun executeInput(command: String, timeout: Int = 10_000): Boolean {
        val result = exec(command, timeout)
        return result.error == null && result.exitCode == 0
    }

    internal suspend fun setText(node: RootScreenNode, text: String): Boolean {
        val escapedText = rootInputText(text)
        val unicode = text.any { it.code > 126 }
        if (escapedText == null && !unicode) { lastFailure = "input_text_unsupported"; return false }
        // Preserve HARDLINE for the actual text even when IPC, rather than a shell argument, carries
        // Unicode. Adding Cyrillic to a forbidden literal must not bypass the existing floor.
        val inputCommand = "input text ${rootScreenQuote(escapedText ?: text)}"
        // HARDLINE preflight must happen before focus/select/delete, even for literal input.
        if (RootCommandGuard.check(inputCommand) != null) { lastFailure = "root_command_blocked"; return false }
        if (!node.isEnabled) { lastFailure = "root_input_failed"; return false }
        if (!tap(node.bounds.centerX().toDouble(), node.bounds.centerY().toDouble())) return false
        // ACTION_SET_TEXT targets the node directly. Root input is global, so verify the focused
        // field before Ctrl+A/delete; a rejected tap or an overlay must not edit another field.
        refreshSnapshot(includeTree = true)
        if (lastFailure != null) return false
        val focused = mutableListOf<RootScreenNode>()
        rootInActiveWindow?.let { root -> traverseTree(root, { n, _ -> n.isEditable && n.isFocused }, 2,
            emit = { n, _, _ -> focused.add(n) }) }
        val target = focused.singleOrNull()
        val matches = target != null && target.isEnabled && target.packageName == node.packageName &&
            target.className == node.className &&
            if (!node.viewIdResourceName.isNullOrBlank()) target.viewIdResourceName == node.viewIdResourceName
            else target.bounds.contains(node.bounds.centerX(), node.bounds.centerY())
        if (!matches) { lastFailure = "root_input_focus_unverified"; return false }
        if (unicode) {
            // The bridge captures and verifies the old clipboard before any select/delete action.
            // Its one bounded root operation also works through the serialized headless transport.
            val error = clipboardInput.replaceText(text, authorizeCommand = { command ->
                RootCommandGuard.check(command) == null && mayExecute()
            }, launchRoot = { command, timeout -> exec(command, timeout) })
            if (error != null) lastFailure = error
            return error == null
        }
        // Android's root input does not expose ACTION_SET_TEXT. Select/delete via keycombination,
        // then type exactly once; a rejected step must not continue or retry the text.
        if (!executeInput("input keycombination 113 29")) return false
        if (!executeInput("input keyevent 67")) return false
        return text.isEmpty() || executeInput(inputCommand)
    }

    internal suspend fun performGlobalAction(code: Int): Boolean = rootGlobalActionCommand(code)?.let { executeInput(it) } ?: false

    internal suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome {
        if (displayId != 0) return ScreenshotOutcome.Failure("Для root screencap доступен основной экран (display_id=0); физические ID других экранов отличаются от логических Android ID.")
        val file = privateTemporaryFile(".png")
        try {
            val result = exec("screencap -p ${rootScreenQuote(file.absolutePath)}", 15_000)
            if (result.error != null || result.exitCode != 0) return ScreenshotOutcome.Failure(result.error ?: "screencap_failed")
            if (file.length() !in 1..MAX_PNG_BYTES) return ScreenshotOutcome.Failure("screenshot_empty_or_too_large")
            return withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_SCREENSHOT_PIXELS) {
                    ScreenshotOutcome.Failure("bitmap_invalid_or_too_large")
                } else BitmapFactory.decodeFile(file.absolutePath)?.let { ScreenshotOutcome.Success(it) }
                    ?: ScreenshotOutcome.Failure("bitmap_decode_failed")
            }
        } finally { file.delete() }
    }

    // Depth-first traversal and truncation behavior copied from Agent RikkaAccessibilityService.
    internal fun traverseTree(root: RootScreenNode, filter: (RootScreenNode, Int) -> Boolean, cap: Int,
        emit: (RootScreenNode, Int, Int) -> Unit, @Suppress("UNUSED_PARAMETER") recycle: Boolean = false): Triple<Int, Int, Boolean> {
        val maxDepth = 60
        var emitted = 0
        var seen = 0
        var truncated = false
        val stack = ArrayDeque<Pair<RootScreenNode, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (n, depth) = stack.removeLast()
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) truncated = true
            }
            if (!truncated && depth < maxDepth) {
                for (i in n.childCount - 1 downTo 0) {
                    val child = n.getChild(i) ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
            if (truncated) break
        }
        return Triple(emitted, seen, truncated)
    }

    internal fun resolveClickable(node: RootScreenNode): RootScreenNode? {
        var current: RootScreenNode? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

    internal sealed class ScreenshotOutcome {
        data class Success(val bitmap: Bitmap) : ScreenshotOutcome()
        data class Failure(val reason: String) : ScreenshotOutcome()
    }
    companion object {
        internal const val GLOBAL_ACTION_BACK = 1
        internal const val GLOBAL_ACTION_HOME = 2
        internal const val GLOBAL_ACTION_RECENTS = 3
        internal const val GLOBAL_ACTION_NOTIFICATIONS = 4
        internal const val GLOBAL_ACTION_QUICK_SETTINGS = 5
        internal const val GLOBAL_ACTION_POWER_DIALOG = 6
        internal const val GLOBAL_ACTION_LOCK_SCREEN = 8
        internal const val MAX_XML_BYTES = 4L * 1024 * 1024
        private const val MAX_PNG_BYTES = 32L * 1024 * 1024
        private const val MAX_SCREENSHOT_PIXELS = 24L * 1024 * 1024
    }
}

internal class RootScreenNode(
    val packageName: String?, val className: String?, val text: String?, val contentDescription: String?,
    val viewIdResourceName: String?, val isClickable: Boolean, val isScrollable: Boolean,
    val isEditable: Boolean, val isEnabled: Boolean, val bounds: Rect, val isFocused: Boolean = false,
) {
    internal var owner: RootScreenService? = null
    internal var windowId: Int = 0
    internal var parent: RootScreenNode? = null
    internal val children = mutableListOf<RootScreenNode>()
    val childCount: Int get() = children.size
    val isVisibleToUser: Boolean get() = bounds.width() > 0 && bounds.height() > 0
    val window: RootScreenWindow? get() = owner?.currentFocus?.let { RootScreenWindow(it) }
    // UIAutomator exposes scrollable but not exact AccessibilityAction support. Preserve Agent's
    // direction-true swipe fallback rather than pretending that a native scroll action exists.
    val actionList: List<RootScreenAction> get() = emptyList()
    fun getChild(index: Int): RootScreenNode? = children.getOrNull(index)
    fun getBoundsInScreen(out: Rect) { out.set(bounds) }
    suspend fun performAction(action: Int, args: android.os.Bundle? = null): Boolean = when (action) {
        ACTION_CLICK -> isEnabled && owner?.tap(bounds.centerX().toDouble(), bounds.centerY().toDouble()) == true
        ACTION_SET_TEXT -> args?.getCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString()?.let { owner?.setText(this, it) } == true
        else -> false
    }
    object AccessibilityAction {
        val ACTION_SCROLL_RIGHT = RootScreenAction(16908347)
        val ACTION_SCROLL_LEFT = RootScreenAction(16908345)
    }
    companion object {
        const val ACTION_CLICK = 16
        const val ACTION_SCROLL_FORWARD = 4096
        const val ACTION_SCROLL_BACKWARD = 8192
        const val ACTION_SET_TEXT = 2097152
        const val ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE = "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE"
    }
}
internal data class RootScreenAction(val id: Int)
internal data class RootScreenWindow(val title: String)

internal fun rootScreenQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
internal fun rootInputText(value: String): String? = if (value.any { it.code !in 32..126 } || "%s" in value) null else value.replace(" ", "%s")
internal fun parseRootDisplaySize(output: String): Pair<Int, Int>? =
    Regex("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)").findAll(output).lastOrNull()?.let { match ->
        val width = match.groupValues[1].toIntOrNull() ?: return@let null
        val height = match.groupValues[2].toIntOrNull() ?: return@let null
        if (width > 0 && height > 0) width to height else null
    }
internal fun rootGlobalActionCommand(code: Int): String? = when (code) {
    RootScreenService.GLOBAL_ACTION_BACK -> "input keyevent 4"
    RootScreenService.GLOBAL_ACTION_HOME -> "input keyevent 3"
    RootScreenService.GLOBAL_ACTION_RECENTS -> "input keyevent 187"
    RootScreenService.GLOBAL_ACTION_NOTIFICATIONS -> "cmd statusbar expand-notifications"
    RootScreenService.GLOBAL_ACTION_QUICK_SETTINGS -> "cmd statusbar expand-settings"
    RootScreenService.GLOBAL_ACTION_LOCK_SCREEN -> "input keyevent 223"
    RootScreenService.GLOBAL_ACTION_POWER_DIALOG -> "input keyevent --longpress 26"
    else -> null
}

internal fun parseRootWindowXml(xml: String): List<RootScreenNode> {
    require(xml.length <= 4 * 1024 * 1024)
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
    val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
    val all = mutableListOf<RootScreenNode>()
    val stack = ArrayDeque<RootScreenNode>()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
            check(all.size < 10_000 && stack.size < 60)
            fun attr(name: String): String = parser.getAttributeValue(null, name).orEmpty()
            val bounds = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(attr("bounds"))
                ?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
            val cls = attr("class")
            val node = RootScreenNode(attr("package"), cls, attr("text"), attr("content-desc"),
                attr("resource-id"), attr("clickable") == "true", attr("scrollable") == "true",
                attr("editable") == "true" || cls.contains("EditText"), attr("enabled") != "false",
                Rect(bounds[0], bounds[1], bounds[2], bounds[3]), attr("focused") == "true")
            stack.lastOrNull()?.let { parent -> node.parent = parent; parent.children.add(node) }
            stack.addLast(node)
            all.add(node)
        } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "node") {
            check(stack.isNotEmpty()); stack.removeLast()
        }
        parser.next()
    }
    require(stack.isEmpty())
    return all
}

/** Application-owned per-call permission identity; never decoded from model arguments. */
private class ScreenToolInvocation(val mayExecute: suspend () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScreenToolInvocation>
}

fun createScreenAutomationTools(context: Context, manager: RootShellManager,
    mayExecute: suspend (toolName: String, args: JsonElement) -> Boolean = { _, _ -> true }): List<Tool> {
    val service = RootScreenService(context, manager) {
        currentCoroutineContext()[ScreenToolInvocation]?.mayExecute?.invoke() == true
    }
    val tools = listOf(tapTool(service), longPressTool(service), swipeTool(service), readWindowTreeTool(service),
        findNodeTool(service), clickNodeTool(service), setTextTool(service), scrollTool(service), globalActionTool(service),
        takeScreenshotTool(context, service), wakeScreenTool(context, service))
    return tools.map { tool -> tool.copy(execute = { input ->
        withContext(ScreenToolInvocation { mayExecute(tool.name, input) }) { tool.execute(input) }
    }) }
}
