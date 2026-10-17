package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.data.ai.tools.local.NfcResult
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiRequest
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiResult
import me.rerere.rikkahub.data.ai.tools.local.SafUiRequest
import me.rerere.rikkahub.data.ai.tools.local.SafUiResult
import me.rerere.rikkahub.data.ai.tools.local.sharedNfcSessions
import me.rerere.rikkahub.data.ai.tools.local.sharedPersonalSessions
import me.rerere.rikkahub.data.ai.tools.local.sharedSafToolSessions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadow.api.Shadow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PickerClosingActivityShadow::class])
class ActivityLifecycleAuditTest {
    @Test fun recordingKeepsScreenOnUntilCancellation() = personalRequest(PersonalUiRequest.Record(30_000), cancel = true)

    @Test fun speechKeepsScreenOnUntilCancellation() = personalRequest(PersonalUiRequest.Speech("ru-RU", 30_000, false), cancel = true)

    @Test fun recordingCompletionReleasesScreenBeforeHostDestruction() = personalRequest(PersonalUiRequest.Record(30_000), cancel = false)

    @Test fun speechCompletionReleasesScreenBeforeHostDestruction() = personalRequest(PersonalUiRequest.Speech("ru-RU", 30_000, false), cancel = false)

    private fun personalRequest(request: PersonalUiRequest, cancel: Boolean) = runTest {
        // Keep capture queued: this checks the real Activity's request lifetime without
        // replacing capture functions or requiring microphone hardware in a JVM test.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = sharedPersonalSessions.register(request)
        val controller = Robolectric.buildActivity(PersonalToolActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), PersonalToolActivity::class.java)
                .putExtra(PersonalToolActivity.EXTRA_REQUEST_ID, session.id))
        try {
            val activity = controller.create().start().resume().get()
            assertTrue("The approved request must remain active", sharedPersonalSessions.isActive(session.id))
            assertTrue("An active microphone request must keep the screen on", keepsScreenOn(activity))
            if (cancel) sharedPersonalSessions.cancel(session.id)
            else sharedPersonalSessions.complete(session.id, PersonalUiResult.Speech("готово"))
            runCurrent()
            assertTrue("Completion must finish the host", activity.isFinishing)
            assertFalse("A completed microphone request must release the screen", keepsScreenOn(activity))
        } finally {
            sharedPersonalSessions.cancel(session.id)
            runCurrent()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun nfcKeepsScreenOnUntilCancellation() = nfcRequest(cancel = true)

    @Test fun nfcCompletionReleasesScreenBeforeHostDestruction() = nfcRequest(cancel = false)

    private fun nfcRequest(cancel: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val adapter = NfcAdapter.getDefaultAdapter(app)
        assertNotNull("The test requires the system NFC stub", adapter)
        shadowOf(adapter).setEnabled(true)
        val session = sharedNfcSessions.register("lifecycle-audit", false, 30)
        val controller = Robolectric.buildActivity(NfcToolActivity::class.java,
            Intent(app, NfcToolActivity::class.java).putExtra(NfcToolActivity.EXTRA_REQUEST_ID, session.requestId))
        try {
            val activity = controller.create().start().resume().get()
            assertTrue("The reader request must remain active", sharedNfcSessions.isActive(session.requestId))
            assertTrue("An active NFC request must keep the screen on", keepsScreenOn(activity))
            if (cancel) sharedNfcSessions.cancel(session.requestId)
            else sharedNfcSessions.complete(session.requestId, NfcResult.ReadOk("[]", "00"))
            runCurrent()
            assertTrue("Completion must finish the NFC host", activity.isFinishing)
            assertFalse("A completed NFC request must release the screen", keepsScreenOn(activity))
        } finally {
            sharedNfcSessions.cancel(session.requestId)
            runCurrent()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun safTimeoutClosesTheActualLaunchedPickerAndHost() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var controller: org.robolectric.android.controller.ActivityController<SafToolActivity>? = null
        var requestId: String? = null
        try {
            val result = async {
                sharedSafToolSessions.run(SafUiRequest(null, null), 1_000, { true }) { id ->
                    requestId = id
                    controller = Robolectric.buildActivity(SafToolActivity::class.java,
                        Intent(ApplicationProvider.getApplicationContext(), SafToolActivity::class.java)
                            .putExtra(SafToolActivity.EXTRA_REQUEST_ID, id)).create().start().resume()
                }
            }
            runCurrent()
            val host = requireNotNull(controller).get()
            val launched = shadowOf(host).nextStartedActivityForResult
            assertNotNull("The test must exercise a real ActivityResult picker launch", launched)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.intent.action)
            // The external picker covers the host while the invocation timeout remains active.
            requireNotNull(controller).pause().stop()
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(result.await() is SafUiResult.Error)
            assertTrue("Timeout must finish our host", host.isFinishing)
            val closing = Shadow.extract<PickerClosingActivityShadow>(host)
            assertTrue("Timeout must finish the exact external picker request, not only unregister its callback",
                launched.requestCode in closing.finishedRequestCodes)
            assertFalse(sharedSafToolSessions.isBusy())
        } finally {
            requestId?.let { sharedSafToolSessions.cancel(it) }
            runCurrent()
            controller?.destroy()
            Dispatchers.resetMain()
        }
    }

    private fun keepsScreenOn(activity: Activity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
}

/** Observe Android's child-Activity closing API, retaining the standard Activity shadow. */
@Implements(Activity::class)
class PickerClosingActivityShadow : ShadowActivity() {
    val finishedRequestCodes = mutableListOf<Int>()

    @Implementation
    protected fun finishActivity(requestCode: Int) {
        finishedRequestCodes += requestCode
    }
}
