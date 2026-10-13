package me.rerere.rikkahub.ui.locale

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import me.rerere.rikkahub.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Android locale APIs and resource resolution on both sides of the Android 13 boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35], qualifiers = "en-rUS")
class InterfaceAppLanguageTest {
    private lateinit var activity: InterfaceLanguageTestActivity
    private val application get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        activity = Robolectric.buildActivity(InterfaceLanguageTestActivity::class.java).setup().get()
    }

    @After fun clean() {
        application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @Test fun nullRetainsAnExistingNativeOrLegacyLanguage() {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-Hant")
        } else {
            application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE)
                .edit().putString("language_tag", "zh-Hant").commit()
        }
        assertFalse(syncInterfaceAppLanguage(activity, null))
        assertEquals("zh-Hant", currentInterfaceAppLanguage(activity))
        assertEquals(0, activity.recreationCount)
    }

    @Test fun explicitLanguageUsesNativeManagerOrCommitsBeforeLegacyRecreation() {
        assertTrue(syncInterfaceAppLanguage(activity, "ru"))
        assertEquals("ru", currentInterfaceAppLanguage(activity))
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(LocaleList.forLanguageTags("ru"), activity.getSystemService(LocaleManager::class.java).applicationLocales)
            assertEquals("Android owns locale recreation on API 33+", 0, activity.recreationCount)
            assertFalse(application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE).contains("language_tag"))
        } else {
            assertEquals(1, activity.recreationCount)
            assertEquals("ru", activity.cachedTagAtRecreation)
        }
        assertFalse("Reloading the same settings must not recreate again", syncInterfaceAppLanguage(activity, "ru"))
        assertEquals(if (Build.VERSION.SDK_INT >= 33) 0 else 1, activity.recreationCount)
    }

    @Test fun changedBackupSettingsOverrideTheExistingPlatformOrCachedChoice() {
        syncInterfaceAppLanguage(activity, "ru")
        assertTrue(syncInterfaceAppLanguage(activity, "zh-CN"))
        assertEquals("zh-CN", currentInterfaceAppLanguage(activity))
        assertFalse(syncInterfaceAppLanguage(activity, "zh-CN"))
        assertEquals(if (Build.VERSION.SDK_INT >= 33) 0 else 2, activity.recreationCount)
    }

    @Test fun explicitSystemChoiceClearsOverrideOnce() {
        syncInterfaceAppLanguage(activity, "ru")
        assertTrue(syncInterfaceAppLanguage(activity, ""))
        assertEquals("", currentInterfaceAppLanguage(activity))
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(activity.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
        } else {
            assertEquals("", activity.cachedTagAtRecreation)
            assertEquals(2, activity.recreationCount)
        }
        assertFalse(syncInterfaceAppLanguage(activity, ""))
    }

    @Test fun legacyBootContextChangesNativeResourcesWithoutMutatingSystemBase() {
        if (Build.VERSION.SDK_INT >= 33) {
            // The old cache must never overrule Android's app-language setting after an OS upgrade.
            application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE)
                .edit().putString("language_tag", "ru").commit()
            assertSame(application, attachInterfaceLanguageContext(application))
            return
        }
        syncInterfaceAppLanguage(activity, "ru")
        val localized = attachInterfaceLanguageContext(application)
        assertNotSame(application, localized)
        assertEquals("ru", localized.resources.configuration.locales[0].language)
        assertEquals("Новый чат", localized.getString(R.string.chat_page_new_chat))
        assertEquals("en", application.resources.configuration.locales[0].language)
        syncInterfaceAppLanguage(activity, "")
        assertSame(application, attachInterfaceLanguageContext(application))
        assertEquals("New Chat", application.getString(R.string.chat_page_new_chat))
    }
}

class InterfaceLanguageTestActivity : Activity() {
    var recreationCount = 0
    var cachedTagAtRecreation: String? = null
    override fun recreate() {
        recreationCount++
        cachedTagAtRecreation = getSharedPreferences("interface_app_language", Context.MODE_PRIVATE)
            .getString("language_tag", null)
    }
}
