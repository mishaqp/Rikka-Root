package me.rerere.rikkahub.ui.pages.setting

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDashboardStatusTest {
    @Test
    fun `first UI snapshot is a placeholder before any source starts`() = runTest {
        var sourceStarted = false
        val source = flow<SettingsDashboardStatus> {
            sourceStarted = true
            emit(SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("Активен")))
        }

        val first = observeSettingsDashboardStatuses(mapOf(SettingsDashboardStatusKey.Root to source)).first()

        assertEquals(SettingsDashboardStatusKey.entries.toSet(), first.keys)
        assertTrue(first.values.all { it == SettingsDashboardStatus.Loading })
        assertFalse(sourceStarted)
    }

    @Test
    fun `suspended storage does not delay root status or its later updates`() = runTest {
        val storageGate = CompletableDeferred<SettingsDashboardStatus>()
        val root = MutableStateFlow<SettingsDashboardStatus>(ready("Не проверен"))
        val sources = mapOf(
            SettingsDashboardStatusKey.Root to root,
            SettingsDashboardStatusKey.Storage to flow { emit(storageGate.await()) },
        )
        var latest = emptyMap<SettingsDashboardStatusKey, SettingsDashboardStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSettingsDashboardStatuses(sources).collect { latest = it }
        }
        runCurrent()

        assertEquals(ready("Не проверен"), latest[SettingsDashboardStatusKey.Root])
        assertEquals(SettingsDashboardStatus.Loading, latest[SettingsDashboardStatusKey.Storage])

        root.value = ready("Активен")
        runCurrent()
        assertEquals(ready("Активен"), latest[SettingsDashboardStatusKey.Root])
        assertEquals(SettingsDashboardStatus.Loading, latest[SettingsDashboardStatusKey.Storage])

        storageGate.complete(ready("Файлов: 2"))
        runCurrent()
        assertEquals(ready("Файлов: 2"), latest[SettingsDashboardStatusKey.Storage])
        assertEquals(ready("Активен"), latest[SettingsDashboardStatusKey.Root])
    }

    @Test
    fun `failed source remains local while siblings can finish and emit no chip`() = runTest {
        val approvalsGate = CompletableDeferred<SettingsDashboardStatus>()
        var latest = emptyMap<SettingsDashboardStatusKey, SettingsDashboardStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSettingsDashboardStatuses(mapOf(
                SettingsDashboardStatusKey.Ssh to flow<SettingsDashboardStatus> {
                    throw IllegalStateException("An endpoint or credential must not reach UI state")
                },
                SettingsDashboardStatusKey.Approvals to flow { emit(approvalsGate.await()) },
            )).collect { latest = it }
        }
        runCurrent()
        assertEquals(SettingsDashboardStatus.Error, latest[SettingsDashboardStatusKey.Ssh])
        assertEquals(SettingsDashboardStatus.Loading, latest[SettingsDashboardStatusKey.Approvals])

        approvalsGate.complete(SettingsDashboardStatus.Ready())
        runCurrent()
        assertEquals(SettingsDashboardStatus.Ready(), latest[SettingsDashboardStatusKey.Approvals])
        assertEquals(SettingsDashboardStatus.Error, latest[SettingsDashboardStatusKey.Ssh])
        assertFalse(latest.toString().contains("credential"))
    }

    @Test
    fun `leaving the screen cancels suspended work without publishing a failure`() = runTest {
        var sourceStarted = false
        var sourceCancelled = false
        val snapshots = mutableListOf<Map<SettingsDashboardStatusKey, SettingsDashboardStatus>>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSettingsDashboardStatuses(mapOf(SettingsDashboardStatusKey.Storage to flow<SettingsDashboardStatus> {
                sourceStarted = true
                try { awaitCancellation() }
                finally { sourceCancelled = true }
            })).collect { snapshots.add(it) }
        }
        runCurrent()
        assertTrue(sourceStarted)

        collection.cancelAndJoin()

        assertTrue(sourceCancelled)
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.none { it[SettingsDashboardStatusKey.Storage] == SettingsDashboardStatus.Error })
    }

    private fun ready(text: String) = SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(text))
}
