// Adapted from RikkaHub Agent (AGPL-3.0): pure core copied, live state captured via root.
package me.rerere.rikkahub.data.ai.tools.local

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Pure core of the post-action screen-state envelope. Everything in this section is
// JVM-testable: no framework calls, no Context.

/** FNV-1a 64-bit over the UTF-8 bytes of each part, order sensitive, with a separator
 *  fold between parts so ["ab","c"] and ["a","bc"] hash differently. */
internal fun fnv1a64(parts: Iterable<String>): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325, FNV offset basis
    val prime = 0x100000001b3L
    for (part in parts) {
        for (b in part.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= prime
        }
        hash = hash xor 0x1f
        hash *= prime
    }
    return hash
}

/** The facts about one on-screen window that the surface heuristics need. */
internal data class SurfaceFacts(
    val pkg: String,
    val type: Int,
    val top: Int,
    val bottom: Int,
)

/** The notification shade (or quick settings) is a SystemUI-owned TYPE_SYSTEM window
 *  covering more than half the display height. Status bar alone is far smaller. */
internal fun shadeOpen(windows: List<SurfaceFacts>, displayHeight: Int): Boolean =
    windows.any {
        it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            it.pkg.contains("systemui") &&
            (it.bottom - it.top) > displayHeight / 2
    }

internal fun imeVisible(windows: List<SurfaceFacts>): Boolean =
    windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

/** Parses the "windowId:traversalIndex" node_id emitted by read_window_tree.
 *  Traversal indices are 1-based (the service counts nodes from 1). */
internal fun parseNodeId(raw: String): Pair<Int, Int>? {
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val windowId = parts[0].toIntOrNull() ?: return null
    val traversalIndex = parts[1].toIntOrNull() ?: return null
    if (traversalIndex < 1) return null
    return windowId to traversalIndex
}

/** Negative coordinates are rejected earlier by the tools; this catches the far edges. */
internal fun coordsOutOfBounds(x: Double, y: Double, displayW: Int, displayH: Int): Boolean =
    x >= displayW || y >= displayH

/**
 * Waits until [quietMs] elapse with no window event, or [timeoutMs] total. [floor] is the
 * uptime at which the action was dispatched: the quiet window is measured from
 * max(lastEvent(), floor) so a stale lastEvent cannot satisfy the wait instantly.
 * Returns true if the UI went quiet, false on timeout.
 */
internal suspend fun awaitQuiet(
    quietMs: Long,
    timeoutMs: Long,
    now: () -> Long,
    lastEvent: () -> Long,
    floor: Long,
): Boolean {
    val start = now()
    while (true) {
        val n = now()
        if (n - maxOf(lastEvent(), floor) >= quietMs) return true
        if (n - start >= timeoutMs) return false
        delay(25)
    }
}

// Root snapshot half: the pure Agent hash/selector/heuristic helpers above are retained.
internal fun treeHash(svc: RootScreenService): Long? {
    val root = svc.rootInActiveWindow ?: return null
    val parts = mutableListOf<String>()
    val rect = Rect()
    svc.traverseTree(root, ::defaultFilter, 500, emit = { n, _, _ ->
        n.getBoundsInScreen(rect)
        parts.add("${n.className}|${n.text}|${n.contentDescription}|${rect.left},${rect.top},${rect.right},${rect.bottom}")
    })
    return fnv1a64(parts)
}

internal fun screenStateJson(svc: RootScreenService, screenChanged: Boolean?, settleTimedOut: Boolean = false): JsonObject {
    val root = svc.rootInActiveWindow
    val dm = svc.displayMetrics
    val pm = svc.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val km = svc.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    return buildJsonObject {
        put("package", root?.packageName ?: Regex(" ([^ /{}]+)/").find(svc.currentFocus)?.groupValues?.get(1).orEmpty())
        if (svc.currentFocus.isNotEmpty()) put("window", svc.currentFocus)
        if (svc.shadeShown) put("shade_open", true)
        if (svc.imeShown) put("ime_visible", true)
        if (pm?.isInteractive == false) put("screen_on", false)
        if (km?.isKeyguardLocked == true) put("keyguard_locked", true)
        put("display", buildJsonObject { put("w", dm.widthPixels); put("h", dm.heightPixels) })
        if (screenChanged != null) put("screen_changed", screenChanged)
        if (settleTimedOut) put("settle", "timeout")
        svc.lastTreeError?.let { put("tree_error", it) }
    }
}

/** Same Agent before/action/after envelope; root has no Accessibility event stream, so
 * replace event quietness with one delayed, bounded UIAutomator snapshot. */
internal suspend fun withActionEnvelope(svc: RootScreenService, act: suspend (RootScreenService) -> JsonObject): JsonObject {
    val before = treeHash(svc)
    val result = try { act(svc) } catch (error: CancellationException) { throw error }
        catch (_: Exception) { buildJsonObject { put("error", "tool_exception"); put("message", "Не удалось выполнить действие автоматизации экрана.") } }
    if (result.containsKey("error")) return result
    delay(300)
    // Preserve a failed command's exact status rather than losing it during state capture.
    val actionFailure = svc.lastFailure
    if (actionFailure == null) svc.refreshAfterSnapshot()
    val after = treeHash(svc)
    val changed = if (before != null && after != null) before != after else null
    return JsonObject(result + ("after" to screenStateJson(svc, changed, svc.lastTreeError != null)))
}
