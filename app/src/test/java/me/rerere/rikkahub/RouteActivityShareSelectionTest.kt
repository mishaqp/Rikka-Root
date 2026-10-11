package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.utils.base64Decode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Only the settings boundary is mocked; activity, page, VM and navigation are production code. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteActivityShareSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val assistant = Assistant(name = "Получатель вложений")
    private val store = mockk<SettingsStore>()

    @Before
    fun installSettingsBoundary() {
        stopKoin()
        every { store.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant)))
        coEvery { store.updateAssistant(assistant.id) } returns Unit
        startKoin {
            modules(module {
                single { store }
                viewModel { parameters -> ShareHandlerVM(parameters.get(), get()) }
            })
        }
    }

    @After
    fun close() { stopKoin() }

    @Test
    fun `assistant selection keeps all ordered files and text in a Chat draft`() {
        val uris = listOf(Uri.parse("content://files/first.pdf"), Uri.parse("content://gallery/second.png"))
        val route = routeShareIntent(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_TEXT, "Текст с двумя файлами")
        })
        assertSelectionDraft(route, "Текст с двумя файлами", uris.map { it.toString() })
    }

    @Test
    fun `single file share remains a draft until the assistant is selected`() {
        val uri = Uri.parse("content://gallery/photo.png")
        val route = routeShareIntent(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Фото для чата")
        })
        assertSelectionDraft(route, "Фото для чата", listOf(uri.toString()))
    }

    @Test
    fun `text only share keeps the same assistant selection flow`() {
        val route = routeShareIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Только текст")
        })
        assertSelectionDraft(route, "Только текст", emptyList())
    }

    private fun assertSelectionDraft(route: Screen.ShareHandler, text: String, files: List<String>) {
        val stack = mutableListOf<NavKey>(Screen.Chat("existing"), route)
        val navigator = Navigator(stack)
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalNavController provides navigator) {
                    ShareHandlerPage(text = route.text, streamUris = route.streamUris)
                }
            }
        }
        compose.waitForIdle()
        assertEquals("Opening a share must wait for selection", route, stack.last())
        coVerify(exactly = 0) { store.updateAssistant(any()) }

        compose.onNodeWithText(assistant.name).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { store.updateAssistant(assistant.id) }
        assertEquals("Selection replaces the selection stack with a new draft", 1, stack.size)
        val draft = stack.single() as Screen.Chat
        assertEquals(text, draft.text?.base64Decode())
        assertEquals(files, draft.files)
        assertNull(draft.nodeId)
    }
}
