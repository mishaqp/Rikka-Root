package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.koin.core.context.loadKoinModules
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import kotlin.uuid.Uuid
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowDialog
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi", shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHeaderIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    @Before fun install() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns MutableStateFlow(ScreenshotFixtures.settings)
        loadKoinModules(module { single { settingsStore } })
    }
    @After fun close() { fixture.close() }
    private val navigation = mutableListOf<androidx.navigation3.runtime.NavKey>()
    private fun render(settings: Settings = ScreenshotFixtures.settings) {
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Light, 1.3f) {
                Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                    CompositionLocalProvider(LocalNavController provides Navigator(navigation), LocalSettings provides settings) { fixture.Content() }
                }
            }
        }
    }
    @Test fun headerModelOpensExistingPickerAndSelectionReachesViewModel() {
        render()
        compose.onNodeWithTag("chat_header_model").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNode(hasText("GPT-4.1") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).performClick()
        verify(exactly = 1) { fixture.vm.setChatModel(ScreenshotFixtures.model) }
    }
    @Test fun titleStillRenamesAndPreviewStillOpensSearch() {
        render()
        compose.onNodeWithTag("chat_header_title").performClick()
        layoutTitleDialog()
        // Robolectric's wrap-content dialog repeatedly invalidates the text selection wrapper's
        // placement. Exercise the real dialog accessibility actions after its initial layout;
        // regular idle synchronization resumes when Save dismisses the dialog.
        val renamedTitle = "Изменённый заголовок"
        compose.runWithoutImplicitWait {
            compose.onNode(hasSetTextAction() and hasText("Аудит экрана чата"))
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(renamedTitle)) }
        }
        layoutTitleDialog()
        compose.runWithoutImplicitWait {
            compose.onNode(hasSetTextAction() and hasText(renamedTitle)).assertIsDisplayed()
            compose.onNodeWithText("Сохранить").assertIsDisplayed().assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        verify(exactly = 1) { fixture.vm.updateTitle(renamedTitle) }
        compose.onNodeWithContentDescription("Меню чата").performClick()
        compose.onNodeWithText("Обзор и поиск сообщений").performClick()
        compose.onNode(hasSetTextAction() and hasText("Поиск")).assertIsDisplayed()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun layoutTitleDialog() {
        compose.waitUntil(timeoutMillis = 1_000) {
            compose.runOnUiThread {
                ShadowLooper.idleMainLooper()
                ShadowDialog.getLatestDialog()?.isShowing == true
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            ShadowLooper.idleMainLooper()
            fun layout(view: View) {
                if (view is ViewRootForTest) view.measureAndLayoutForTest()
                if (view is ViewGroup) for (index in 0 until view.childCount) layout(view.getChildAt(index))
            }
            layout(requireNotNull(ShadowDialog.getLatestDialog().window).decorView)
        }
    }
    @Test fun filesButtonNavigatesDirectlyToFilesTab() {
        val id = Uuid.parse("50000000-0000-0000-0000-000000000001")
        every { fixture.vm.assistant } returns MutableStateFlow(ScreenshotFixtures.assistant.copy(workspaceId = id))
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        every { repository.listFlow() } returns flowOf(listOf(WorkspaceEntity(id.toString(), "Рабочая область", "/workspace", createdAt = 0L, updatedAt = 0L)))
        render()
        compose.onNodeWithTag("chat_header_workspace").assertIsDisplayed()
        compose.onNodeWithContentDescription("Файлы").performClick()
        org.junit.Assert.assertEquals(Screen.WorkspaceDetail(id.toString(), openFiles = true), navigation.last())
    }
    @Test fun quickMessageInPlusAppendsWithoutInsertingSpacesAndClosesSheet() {
        val quick = QuickMessage(title = "Вставить шаблон", content = "после")
        val assistant = ScreenshotFixtures.assistant.copy(quickMessageIds = setOf(quick.id))
        val settings = ScreenshotFixtures.settings.copy(quickMessages = listOf(quick), assistants = listOf(assistant))
        every { fixture.vm.settings } returns MutableStateFlow(settings)
        every { fixture.vm.assistant } returns MutableStateFlow(assistant)
        val input = fixture.vm.inputState
        input.setMessageText("До")
        render(settings)
        compose.onNodeWithContentDescription("Дополнительные действия").performClick()
        compose.onNodeWithText("Быстрые сообщения").performClick()
        compose.onNodeWithText("Вставить шаблон").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("Допосле", input.textContent.text.toString()) }
        compose.onNodeWithText("Камера").assertDoesNotExist()
    }
    @Test fun unboundChatDoesNotShowWorkspaceSwitcher() {
        render()
        compose.onNodeWithTag("chat_header_workspace").assertDoesNotExist()
        compose.onNodeWithTag("chat_header_new_chat").assertHasClickAction()
    }
}
