package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.web.WebServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDashboardRuntimeStatusStoreTest {
    @Test
    fun `observing dormant runtimes does not initialize them and publication later connects observers`() = runTest {
        val store = SettingsDashboardRuntimeStatusStore()
        val dormantMcp = lazy { MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap()) }
        val dormantWeb = lazy { MutableStateFlow(WebServerState()) }
        val mcpSnapshots = mutableListOf<Map<Uuid, McpStatus>?>()
        val webSnapshots = mutableListOf<WebServerState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observeMcpStatus().collect { mcpSnapshots.add(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observeWebStatus().collect { webSnapshots.add(it) }
        }
        runCurrent()

        assertFalse(dormantMcp.isInitialized())
        assertFalse(dormantWeb.isInitialized())
        assertEquals(1, mcpSnapshots.size)
        assertEquals(1, webSnapshots.size)
        assertNull(mcpSnapshots.single())
        assertNull(webSnapshots.single())

        // These calls model existing feature initialization, which publishes its existing flow.
        store.publishMcpStatus(dormantMcp.value)
        runCurrent()
        assertEquals(emptyMap<Uuid, McpStatus>(), mcpSnapshots.last())
        assertFalse(dormantWeb.isInitialized())
        assertNull(webSnapshots.last())

        store.publishWebStatus(dormantWeb.value)
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        dormantMcp.value.value = mapOf(id to McpStatus.Connected)
        dormantWeb.value.value = WebServerState(isRunning = true)
        runCurrent()

        assertEquals(mapOf(id to McpStatus.Connected), mcpSnapshots.last())
        assertTrue(webSnapshots.last()!!.isRunning)

        dormantMcp.value.value = emptyMap()
        dormantWeb.value.value = WebServerState(isRunning = false)
        runCurrent()
        assertTrue(mcpSnapshots.last()!!.isEmpty())
        assertFalse(webSnapshots.last()!!.isRunning)
    }

    @Test
    fun `publishing a replacement switches observation away from the old runtime`() = runTest {
        val store = SettingsDashboardRuntimeStatusStore()
        val oldRuntime = MutableStateFlow(WebServerState(isRunning = true))
        val replacement = MutableStateFlow(WebServerState(isRunning = false))
        var current: WebServerState? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observeWebStatus().collect { current = it }
        }
        store.publishWebStatus(oldRuntime)
        runCurrent()
        assertTrue(current!!.isRunning)

        store.publishWebStatus(replacement)
        runCurrent()
        assertFalse(current!!.isRunning)

        oldRuntime.value = WebServerState(isLoading = true)
        runCurrent()
        assertEquals(replacement.value, current)

        replacement.value = WebServerState(isRunning = true)
        runCurrent()
        assertTrue(current!!.isRunning)
    }
}
