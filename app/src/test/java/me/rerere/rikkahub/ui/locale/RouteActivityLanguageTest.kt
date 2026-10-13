package me.rerere.rikkahub.ui.locale

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en-rUS")
class RouteActivityLanguageTest {
    @After fun clean() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("interface_app_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun activityBaseUsesCachedResourcesAndLanguageSyncDoesNotReplayNavigationIntents() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("interface_app_language", Context.MODE_PRIVATE)
            .edit().putString("language_tag", "ru").commit()
        // attachBaseContext is production code; onCreate's unrelated services are not needed here.
        val route = Robolectric.buildActivity(RouteActivity::class.java).get()
        assertEquals("ru", route.resources.configuration.locales[0].language)
        assertEquals("Новый чат", route.getString(R.string.chat_page_new_chat))
        assertSame(RouteActivity::class.java, route.javaClass)
        val chat = Screen.Chat("existing", text = "unchanged draft", files = listOf("content://image"))
        val stack = mutableListOf<NavKey>(chat)
        RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }.set(route, stack)
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "selected text")
        RouteActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(route, intent)
        val before = stack.toList()
        assertFalseLanguageChange(route)
        assertEquals("Applying an unchanged language must not alter navigation or replay the share", before, stack)
        assertEquals(chat, stack.first())
        assertEquals(2, stack.size)
    }

    private fun assertFalseLanguageChange(route: RouteActivity) {
        org.junit.Assert.assertFalse(syncInterfaceAppLanguage(route, "ru"))
        org.junit.Assert.assertFalse(syncInterfaceAppLanguage(route, null))
    }
}
