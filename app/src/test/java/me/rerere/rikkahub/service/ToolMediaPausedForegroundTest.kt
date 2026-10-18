package me.rerere.rikkahub.service

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/** Real service and MediaSession; Robolectric's MediaPlayer prepares instantly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class ToolMediaPausedForegroundTest {
    private val app = RuntimeEnvironment.getApplication()
    private val uri = "https://example.com/track.mp3"
    private lateinit var controller: ServiceController<ToolMediaPlaybackService>
    private val service get() = shadowOf(controller.get())
    private val notification get() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(7001)

    @Before fun startPlaying() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, Uri.parse(uri)), ShadowMediaPlayer.MediaInfo(3_600_000, 0))
        controller = Robolectric.buildService(ToolMediaPlaybackService::class.java).create()
        command(ToolMediaPlaybackService.ACTION_PLAY, 1) {
            putExtra("source", "track").putExtra("playback_uri", uri)
        }
        assertFalse("Playing media is a foreground service", service.isForegroundStopped)
        assertTrue(service.lastForegroundNotificationId == 7001)
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        ShadowMediaPlayer.resetStaticState()
    }

    @Test fun `pause leaves the foreground and ends the session after five minutes`() {
        command(ToolMediaPlaybackService.ACTION_PAUSE, 2)
        assertTrue("Paused playback must not hold a foreground service", service.isForegroundStopped)
        assertFalse(service.isStoppedBySelf)
        assertNotNull("The paused notification stays so the user can resume", notification)

        idle(Duration.ofMillis(ToolMediaPlaybackService.PAUSED_STOP_DELAY_MS - 1_000))
        assertFalse(service.isStoppedBySelf)
        idle(Duration.ofSeconds(1))
        assertTrue("A long pause ends the session", service.isStoppedBySelf)
        assertNull("The detached notification is removed with the session", notification)
    }

    @Test fun `resume returns to the foreground and cancels the pending stop`() {
        command(ToolMediaPlaybackService.ACTION_PAUSE, 2)
        command(ToolMediaPlaybackService.ACTION_RESUME, 3)
        assertFalse("Resumed playback is a foreground service again", service.isForegroundStopped)

        idle(Duration.ofMillis(ToolMediaPlaybackService.PAUSED_STOP_DELAY_MS * 2))
        assertFalse("Resuming cancels the pause timeout", service.isStoppedBySelf)
        assertNotNull(notification)
    }

    @Test fun `finished track leaves the foreground and ends the session later`() {
        idle(Duration.ofHours(1))
        assertTrue("A finished track must not hold a foreground service", service.isForegroundStopped)
        idle(Duration.ofMillis(ToolMediaPlaybackService.PAUSED_STOP_DELAY_MS))
        assertTrue(service.isStoppedBySelf)
    }

    private fun command(action: String, startId: Int, extras: Intent.() -> Intent = { this }) {
        controller.withIntent(Intent(app, ToolMediaPlaybackService::class.java).setAction(action).extras()).startCommand(0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)
}
