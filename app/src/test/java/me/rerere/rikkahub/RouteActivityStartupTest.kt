package me.rerere.rikkahub

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.screenshot.ChatBeforeFixture
import me.rerere.rikkahub.ui.screenshot.FixedSeedDynamicColors
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.UiCustomizationScreenshotBoundaries
import me.rerere.tts.provider.TTSManager
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

/** Production Activity, loading gate, Navigation3 state and intent queue; external data is fixed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi", shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteActivityStartupTest {
    private val settings = MutableStateFlow(Settings.dummy())
    private lateinit var fixture: ChatBeforeFixture
    private var ttsManagerRequests = 0
    private val compose = createEmptyComposeRule()
    private val images = UiCustomizationScreenshotBoundaries()
    private lateinit var controller: ActivityController<RouteActivity>
    private val activity get() = controller.get()
    private val dependencies = object : ExternalResource() {
        override fun before() {
            val application = RuntimeEnvironment.getApplication()
            JLatexMathAndroid.init(application)
            fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
            fixture.install()
            val store = mockk<SettingsStore>(relaxed = true)
            every { store.settingsFlow } returns settings
            every { fixture.vm.settings } returns settings
            loadKoinModules(module {
                single { store }
                single {
                    ttsManagerRequests++
                    mockk<TTSManager>(relaxed = true)
                }
                viewModel { parameters -> ShareHandlerVM(parameters.get(), get()) }
            })
            application.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("create_new_conversation_on_start", true)
                .putString("lastConversationId", fixture.conversation.id.toString())
                .commit()
        }

        override fun after() {
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).edit().clear().commit()
            fixture.close()
        }
    }
    @get:Rule val rule: RuleChain = RuleChain.outerRule(dependencies).around(compose)

    @Before fun launchActivity() {
        images.install()
        compose.registerIdlingResource(images.imagesIdle)
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(RouteActivity::class.java)
            controller.setup()
        }
    }

    @After fun destroyActivity() {
        try {
            if (::controller.isInitialized && !activity.isDestroyed) {
                compose.runOnUiThread {
                    controller.pause().stop().destroy()
                    ShadowLooper.idleMainLooper()
                }
            }
        } finally {
            compose.unregisterIdlingResource(images.imagesIdle)
            images.close()
        }
    }

    @Test fun delayedAuthoritativeFalseStartsTheSavedChatDespiteLegacyTrue() {
        assertWaitingForSettings()
        loadSettings()
        assertEquals(listOf(Screen.Chat(fixture.conversation.id.toString())), stack())
        assertEquals(0, queuedIntentCount())
    }

    @Test fun pendingProcessTextWinsOnceAndActivityRecreationRetainsTheRestoredStack() {
        assertWaitingForSettings()
        val initialQueued = queuedIntentCount()
        compose.runOnIdle {
            deliverNewIntent(Intent(Intent.ACTION_PROCESS_TEXT)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "Сохранить выделенный текст"))
        }
        assertNull(stack())
        assertEquals(initialQueued + 1, queuedIntentCount())
        loadSettings()
        val expected = listOf(Screen.Chat(fixture.conversation.id.toString()),
            Screen.ShareHandler("Сохранить выделенный текст"))
        assertEquals(expected, stack())
        assertEquals(0, queuedIntentCount())
        // Robolectric's controller retains its launch Intent separately from Activity.intent.
        // Mirror the actual new Intent so recreation exercises the saved-instance replay gate.
        val previousActivity = activity
        compose.runOnUiThread {
            ReflectionHelpers.setField(controller, "intent", activity.intent)
            controller.recreate()
            ShadowLooper.idleMainLooper()
        }
        compose.waitForIdle()
        assertNotSame("Recreation must construct a new real RouteActivity", previousActivity, activity)
        assertEquals(Intent.ACTION_PROCESS_TEXT, activity.intent.action)
        assertEquals("Сохранить выделенный текст", activity.intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertEquals(expected, stack())
        assertEquals(0, queuedIntentCount())
        recomposeLoadedSettings()
        assertEquals(expected, stack())
    }

    @Test fun pendingConversationDeepLinkWinsOnceAfterTheLoadedStartupChoice() {
        assertWaitingForSettings()
        val destinationId = "70000000-0000-0000-0000-000000000001"
        compose.runOnIdle {
            deliverNewIntent(Intent().putExtra("conversationId", destinationId))
        }
        assertNull(stack())
        loadSettings()
        val expected = listOf(Screen.Chat(fixture.conversation.id.toString()), Screen.Chat(destinationId))
        assertEquals(expected, stack())
        assertEquals(0, queuedIntentCount())
        recomposeLoadedSettings()
        assertEquals(expected, stack())
    }

    private fun assertWaitingForSettings() {
        compose.onNodeWithTag("app_routes_loading").assertExists()
        compose.runOnIdle {
            assertNull("Settings.dummy must not create a random navigation stack", stack())
            assertEquals("Speech hooks must also wait for initialized settings", 0, ttsManagerRequests)
        }
    }

    private fun loadSettings() {
        compose.runOnIdle {
            settings.value = ScreenshotFixtures.settings.copy(dynamicColor = false, ttsProviders = emptyList(),
                asrProviders = emptyList(), displaySetting = ScreenshotFixtures.settings.displaySetting.copy(newChatOnLaunch = false))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app_routes_loading").assertDoesNotExist()
    }

    private fun recomposeLoadedSettings() {
        compose.runOnIdle { settings.value = settings.value.copy(developerMode = !settings.value.developerMode) }
        compose.waitForIdle()
    }

    private fun deliverNewIntent(intent: Intent) {
        controller.newIntent(intent)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stack(): List<NavKey>? = (RouteActivity::class.java.getDeclaredField("navStack")
        .apply { isAccessible = true }.get(activity) as? List<NavKey>)?.toList()

    private fun queuedIntentCount(): Int = (RouteActivity::class.java.getDeclaredField("pendingIntents")
        .apply { isAccessible = true }.get(activity) as Collection<*>).size
}
