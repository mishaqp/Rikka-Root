package me.rerere.rikkahub.miniapps

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MiniAppBridgeTest {
    @Test fun deniedReminderPermissionDoesNotPersistOrSchedule() = runBlocking {
        val root = Files.createTempDirectory("reminder-permission").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            source.resolve("index.html").writeText("<p>x</p>")
            val store = MiniAppStore(root.resolve("installed"))
            store.install(source)
            var scheduled = false
            val reminders = MiniAppReminders(store, { scheduled = true }, {})
            val bridge = MiniAppBridge(store, "water", MiniAppHost(reminders = reminders,
                beforeReminderSet = { throw MiniAppException("permission_denied", "Notifications denied.") }))
            assertEquals("window.__moruReply(1, false, \"Notifications denied.\");", bridge.handle("""{"id":1,"method":"reminders.set","args":{"id":"morning","reminder":{"time":"08:00"}}}"""))
            assertTrue(store.readReminders("water").isEmpty())
            assertFalse(scheduled)
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationPropagatesAndReleasesAiGuard() = runBlocking {
        val root = Files.createTempDirectory("cancel-bridge").toFile()
        try {
            var calls = 0
            val bridge = MiniAppBridge(MiniAppStore(root), "water", MiniAppHost(ask = { _, _ ->
                calls++
                if (calls == 1) throw CancellationException("cancelled")
                "ok"
            }))
            val message = """{"id":1,"method":"ai.ask","args":{"prompt":"p"}}"""
            try { bridge.handle(message); fail("swallowed cancellation") } catch (_: CancellationException) { }
            assertEquals("window.__moruReply(1, true, \"ok\");", bridge.handle(message))
            assertTrue(bridge.failedCalls.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun calendarManifestScopeAndAllowedFields() = runBlocking {
        val root = Files.createTempDirectory("calendar-bridge").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water","permissions":["calendar"]}""")
            source.resolve("index.html").writeText("<p>x</p>")
            val store = MiniAppStore(root.resolve("installed"))
            store.install(source)
            var received: JsonObject? = null
            val bridge = MiniAppBridge(store, "water", MiniAppHost(calendar = { method, args ->
                assertEquals("queryCalendar", method)
                received = args
                buildJsonObject { put("error", "denied"); put("message", "Permission denied.") }
            }))
            assertEquals("window.__moruReply(1, false, \"Permission denied.\");", bridge.handle("""{"id":1,"method":"calendar.list","args":{"range":"week","event_id":9}}"""))
            assertEquals(Json.parseToJsonElement("""{"range":"week"}"""), received)
        } finally { root.deleteRecursively() }
    }
    @Test fun busyGuardAndProtocol() = runBlocking {
        val root = Files.createTempDirectory("bridge").toFile()
        try {
            val gate = CompletableDeferred<String>()
            val bridge = MiniAppBridge(MiniAppStore(root), "water", MiniAppHost(ask = { _, _ -> gate.await() }))
            val first = async { bridge.handle("""{"id":1,"method":"ai.ask","args":{"prompt":"p"}}""") }
            yield()
            assertEquals("window.__moruReply(2, false, \"Wait for the previous moru.ai.ask to finish.\");", bridge.handle("""{"id":2,"method":"ai.ask","args":{"prompt":"p"}}"""))
            gate.complete("ok")
            assertEquals("window.__moruReply(1, true, \"ok\");", first.await())
        } finally { root.deleteRecursively() }
    }
    @Test fun diagnosticsAreBoundedAndReportsHaveNoReply() = runBlocking {
        val root = Files.createTempDirectory("bridge").toFile()
        try {
            val bridge = MiniAppBridge(MiniAppStore(root), "water")
            repeat(60) { assertNull(bridge.handle("""{"method":"__report","args":{"kind":"error","message":"x"}}""")) }
            assertEquals(50, bridge.pageErrors.size)
            assertEquals("window.__moruReply(null, false, \"This is not available here.\");", bridge.handle("""{"id":"unsafe","method":"notify"}"""))
            assertEquals("window.__moruChanged && window.__moruChanged(\"x\\\"\");", MiniAppBridge.changedScript("x\""))
        } finally { root.deleteRecursively() }
    }
}
