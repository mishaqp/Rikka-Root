package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatBranchControlsRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val messages = listOf(UIMessage.assistant("Первая"), UIMessage.assistant("Вторая"), UIMessage.assistant("Третья"))

    @Test fun `first and last alternatives expose only possible navigation as enabled`() {
        val node = mutableStateOf(MessageNode(messages = messages))
        compose.setContent { ScreenshotTheme { ChatMessageBranchSelector(node.value, onUpdate = { node.value = it }) } }
        val controls = compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        controls[0].assertIsNotEnabled()
        controls[1].assertIsEnabled()
        compose.runOnIdle { node.value = node.value.copy(selectIndex = 2) }
        controls[0].assertIsEnabled()
        controls[1].assertIsNotEnabled()
    }

    @Test fun `navigation still changes only the selected index and preserves node identity`() {
        val original = MessageNode(messages = messages, selectIndex = 1)
        val node = mutableStateOf(original)
        val updates = mutableListOf<MessageNode>()
        compose.setContent { ScreenshotTheme { ChatMessageBranchSelector(node.value, onUpdate = { updates += it; node.value = it }) } }
        val controls = compose.onAllNodes(hasClickAction())
        controls[0].performClick()
        controls[1].performClick()
        assertEquals(listOf(0, 1), updates.map { it.selectIndex })
        assertEquals(listOf(original.id, original.id), updates.map { it.id })
        assertEquals(listOf(messages, messages), updates.map { it.messages })
    }

    @Test fun `read only history disables both directions`() {
        compose.setContent { ScreenshotTheme {
            ChatMessageBranchSelector(MessageNode(messages = messages, selectIndex = 1), onUpdate = { error("read only") }, enabled = false)
        } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2).let {
            it[0].assertIsNotEnabled(); it[1].assertIsNotEnabled()
        }
    }

    @Test fun `branch controls have meaningful Russian accessibility labels`() {
        compose.setContent { ScreenshotTheme { ChatMessageBranchSelector(MessageNode(messages = messages), onUpdate = {}) } }
        compose.onNodeWithContentDescription("Предыдущая ветка").assertExists()
        compose.onNodeWithContentDescription("Следующая ветка").assertExists()
    }
}
