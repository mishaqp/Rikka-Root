package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.IncompleteHistoryFixture
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.UiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi", shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncompleteHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var history: IncompleteHistoryFixture
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var vm: ChatVM
    private lateinit var workflows: me.rerere.rikkahub.workflow.db.WorkflowDatabase
    private val viewModels = androidx.lifecycle.ViewModelStore()
    @Before fun install() {
        history = IncompleteHistoryFixture()
        runBlocking { history.seed(); history.service.initializeConversation(history.id) }
        val checker = mockk<me.rerere.rikkahub.utils.UpdateChecker>(relaxed = true)
        every { checker.updateState } returns MutableStateFlow(UiState.Idle)
        vm = ChatVM(history.id.toString(), history.context, history.store, history.repository,
            history.service, checker, history.files, me.rerere.rikkahub.data.repository.FavoriteRepository(history.database.favoriteDao()))
        viewModels.put("chat", vm)
        fixture = ChatBeforeFixture(File("/tmp/chat-stage0/not-used.png"), vm)
        fixture.install()
        workflows = androidx.room.Room.inMemoryDatabaseBuilder(history.context, me.rerere.rikkahub.workflow.db.WorkflowDatabase::class.java)
            .allowMainThreadQueries().build()
        org.koin.core.context.loadKoinModules(org.koin.dsl.module {
            single { me.rerere.rikkahub.data.sync.BackupManager(history.context, history.database, history.store,
                me.rerere.rikkahub.utils.JsonInstant, workflows) }
        })
    }
    @After fun close() { viewModels.clear(); fixture.close(); workflows.close(); history.close() }
    @Test fun incompleteHistoryShowsDurableWarningAndRecoveryExport() {
        compose.setContent {
            ScreenshotTheme(ScreenshotThemeMode.Light, 1f) {
                Surface(Modifier.requiredSize(411.dp, 891.dp)) { fixture.Content() }
            }
        }
        compose.onNodeWithContentDescription(history.context.getString(me.rerere.rikkahub.R.string.send)).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(history.context.getString(me.rerere.rikkahub.R.string.regenerate)).onFirst().assertIsNotEnabled()
        compose.onAllNodesWithContentDescription(history.context.getString(me.rerere.rikkahub.R.string.copy)).onFirst().assertIsEnabled()
        compose.onNodeWithText("Часть истории не загрузилась, чат открыт только для чтения").assertIsDisplayed()
        compose.onNodeWithText("Экспорт").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Полная резервная копия").assertIsDisplayed()
        compose.onNodeWithText("Резервная копия включает всю базу данных, вложения чатов, навыки, шрифты и настройки приложения, в том числе незагруженные сообщения. Файлы рабочих пространств (workspace) в неё не входят — сохраните их отдельно.").assertIsDisplayed()
    }
    @Test fun favoriteMutationIsBlockedBeforeActualFavoriteDaoWrite() {
        vm.toggleMessageFavorite(vm.conversation.value.messageNodes.first())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking {
            org.junit.Assert.assertTrue(history.database.favoriteDao().getFavoriteNodeIdsOfConversation(history.id.toString()).isEmpty())
        }
        org.junit.Assert.assertTrue(history.service.errors.value.any {
            it.error.message == "Часть истории не загрузилась, чат открыт только для чтения"
        })
    }

}
