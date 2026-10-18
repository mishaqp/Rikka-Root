package me.rerere.rikkahub.data.datastore

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SettingsResetNoticeTest {
    @Test fun `reset notice stays until the user acknowledges it`() {
        val context = RuntimeEnvironment.getApplication()
        SettingsResetNotice.dismiss(context)
        assertFalse(SettingsResetNotice.pending(context))
        SettingsResetNotice.mark(context, "settings.preferences_pb.corrupt-1")
        assertTrue(SettingsResetNotice.pending(context))
        assertTrue("A process restart must still show it", SettingsResetNotice.pending(context))
        SettingsResetNotice.dismiss(context)
        assertFalse(SettingsResetNotice.pending(context))
    }
}
