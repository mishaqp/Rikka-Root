package me.rerere.rikkahub.data.sync

import android.app.Application
import io.mockk.coEvery
import kotlinx.coroutines.*
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CherryBackupCompletionPart3Test : BackupVmPart3Fixture() {
    @Test fun completionWaitsForActualDataStoreCommit() = runBlocking {
        val model = vm(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { preferences().updateData { entered.complete(Unit); release.await(); it } }
        entered.await()
        val importing = async(start = CoroutineStart.UNDISPATCHED) { model.restoreFromCherryStudio(cherry()) }
        try { assertFalse("Restart/success cannot precede the actual settings write", importing.isCompleted) }
        finally { release.complete(Unit); blocker.join(); importing.await() }
        val saved = store.update { it }.providers.filterIsInstance<ProviderSetting.OpenAI>()
        assertTrue(saved.any { it.apiKey == "DUMMY_API_KEY" })
    }
    @Test fun cancellingImportWhileWriteWaitsMustCancelTheImportOperation() = runBlocking {
        val model = vm(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { preferences().updateData { entered.complete(Unit); release.await(); it } }
        entered.await()
        val importing = launch(start = CoroutineStart.UNDISPATCHED) { model.restoreFromCherryStudio(cherry()) }
        try { importing.cancel(); importing.join(); assertTrue("A detached completed import cannot honor caller cancellation", importing.isCancelled) }
        finally { release.complete(Unit); blocker.join() }
    }
    @Test fun dummyApiKeyIsPersistedButNeverIncludedInImportLogs() = runBlocking {
        val canary = "DUMMY-IMPORT-SECRET-NOT-A-REAL-KEY"
        ShadowLog.clear(); vm().restoreFromCherryStudio(cherry(canary))
        // Drain the legacy detached write too, so the privacy check is independent of completion behavior.
        withTimeout(5000) { while (store.settingsFlow.value.providers.filterIsInstance<ProviderSetting.OpenAI>().none { it.apiKey == canary }) yield() }
        assertTrue(store.update { it }.providers.filterIsInstance<ProviderSetting.OpenAI>().any { it.apiKey == canary })
        assertFalse("Imported backup credentials must not be copied to logcat", ShadowLog.getLogsForTag("BackupVM").any { it.msg.contains(canary) })
    }
    @Test fun invalidArchiveFailsSynchronouslyAndPreservesExistingProviders() = runBlocking {
        val before = store.update { it }
        val invalid = zip("invalid-cherry.zip", mapOf("data.json" to "{}".encodeToByteArray()))
        assertNotNull(runCatching { vm().restoreFromCherryStudio(invalid) }.exceptionOrNull())
        assertEquals(before.providers, store.update { it }.providers)
    }
}
