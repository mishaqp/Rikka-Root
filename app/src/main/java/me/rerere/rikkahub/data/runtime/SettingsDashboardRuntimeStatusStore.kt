package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.web.WebServerState
import kotlin.uuid.Uuid

/**
 * UI telemetry holds only references published by runtimes that already exist. Observing a
 * dormant runtime returns null and never resolves its dependencies or starts the feature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsDashboardRuntimeStatusStore {
    private val mcpSource = MutableStateFlow<StateFlow<Map<Uuid, McpStatus>>?>(null)
    private val webSource = MutableStateFlow<StateFlow<WebServerState>?>(null)

    fun observeMcpStatus(): Flow<Map<Uuid, McpStatus>?> =
        mcpSource.flatMapLatest { source -> source ?: flowOf(null) }

    fun observeWebStatus(): Flow<WebServerState?> =
        webSource.flatMapLatest { source -> source ?: flowOf(null) }

    fun publishMcpStatus(source: StateFlow<Map<Uuid, McpStatus>>) {
        mcpSource.value = source
    }

    fun publishWebStatus(source: StateFlow<WebServerState>) {
        webSource.value = source
    }

    companion object {
        val shared = SettingsDashboardRuntimeStatusStore()
    }
}
