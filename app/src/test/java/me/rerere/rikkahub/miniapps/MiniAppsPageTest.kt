package me.rerere.rikkahub.miniapps

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.nio.file.Files
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class MiniAppsPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun androidSharedArchiveOpensTheMiniAppImporterAndLeavesTheChatShareRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
        val file = context.cacheDir.resolve("shared-tracker.moruapp").apply { writeText("archive passed to importer") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val stack = mutableStateListOf<NavKey>(Screen.Setting, Screen.ShareHandler("", listOf(uri.toString())))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalNavController provides Navigator(stack)) {
                val route = stack.last()
                if (route is Screen.ShareHandler) me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage(route.text, route.streamUris)
                else Text("Настройки после импорта")
            }
        } }
        compose.waitUntil(10_000) { stack.lastOrNull() == Screen.Setting }
        compose.onNodeWithText("Настройки после импорта").assertIsDisplayed()
        val launched = shadowOf(context as Application).nextStartedActivity
        assertEquals(MiniAppActivity::class.java.name, launched.component!!.className)
        assertEquals(Intent.ACTION_SEND, launched.action)
        assertEquals(uri, IntentCompat.getParcelableExtra(launched, Intent.EXTRA_STREAM, Uri::class.java))
    }

    @Test fun deletionRequiresConfirmationAndCancelPreservesUserData() = withStore { store ->
        show(store)
        compose.onNodeWithText("Water").assertIsDisplayed()
        compose.onNodeWithContentDescription("Действия: Water").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить «Water»?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertNotNull(store.byId("water")) }
        assertEquals(JsonPrimitive(3), runBlocking { store.storageGet("water", "glasses") })
        compose.onNodeWithContentDescription("Действия: Water").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(10_000) { store.apps.value.isEmpty() }
        compose.onNodeWithText("Здесь появятся мини-приложения", substring = true).assertIsDisplayed()
    }

    @Test fun sharingExistingDataRequiresExplicitCodeOrDataChoice() = withStore { store ->
        show(store)
        compose.onNodeWithContentDescription("Действия: Water").performClick()
        compose.onNodeWithText("Поделиться приложением").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Только приложение").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Только приложение").assertIsDisplayed()
        compose.onNodeWithText("Приложение и данные").assertIsDisplayed()
        compose.onNodeWithText("Данные могут содержать личные записи", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(JsonPrimitive(3), runBlocking { store.storageGet("water", "glasses") })
    }

    @Test fun failedViewerShowsErrorKeepsBackAndDisablesUnavailableActions() {
        var back = 0
        compose.setContent { MaterialTheme {
            MiniAppViewerContent("Water", false, "Страница не найдена", { back++ }, {}, {}, null)
        } }
        compose.onNodeWithText("Страница не найдена").assertIsDisplayed()
        compose.onNodeWithContentDescription("Перезагрузить").assertIsNotEnabled()
        compose.onNodeWithContentDescription("На главный экран").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    private fun show(store: MiniAppStore) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf(Screen.Setting, Screen.MiniApps))) {
                MiniAppsPage(store)
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Water").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun withStore(block: (MiniAppStore) -> Unit) {
        val root = Files.createTempDirectory("mini-page").toFile()
        val source = Files.createTempDirectory("mini-page-src").toFile()
        try {
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water","description":"Daily water"}""")
            source.resolve("index.html").writeText("<p>Water</p>")
            val store = MiniAppStore(root)
            runBlocking { store.install(source); store.storageSet("water", "glasses", JsonPrimitive(3)) }
            block(store)
        } finally { root.deleteRecursively(); source.deleteRecursively() }
    }
}
