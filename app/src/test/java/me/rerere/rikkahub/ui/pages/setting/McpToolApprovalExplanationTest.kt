package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpToolApprovalExplanationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledServerApprovalExplainsTheGeneralPolicyOnTheCollapsedCard() {
        showCard()

        compose.onNodeWithText(APPROVAL_LABEL).assertIsDisplayed()
        compose.onNodeWithText(APPROVAL_EXPLANATION).assertIsDisplayed()
    }

    @Test fun additionalServerApprovalRetainsTheUncheckedToggleCallback() {
        val approvals = mutableListOf<Boolean>()
        val enableChanges = mutableListOf<Boolean>()
        showCard(onEnableChange = { enableChanges += it }, onNeedsApprovalChange = { approvals += it })

        val labelBounds = compose.onNodeWithText(APPROVAL_LABEL).fetchSemanticsNode().boundsInRoot
        // The app's custom Switch uses clickable rather than Role.Switch/ToggleableState.
        // Rows without semantics flatten into the card, so select the control next to this label.
        val besideApprovalLabel = SemanticsMatcher("control directly beside the server approval label") { node ->
            val bounds = node.boundsInRoot
            bounds.left >= labelBounds.right && bounds.center.y in labelBounds.top..labelBounds.bottom
        }
        compose.onNode(hasClickAction() and besideApprovalLabel).performClick()

        assertEquals(listOf(true), approvals)
        assertEquals(emptyList<Boolean>(), enableChanges)
    }

    private fun showCard(
        onEnableChange: (Boolean) -> Unit = {},
        onNeedsApprovalChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            ScreenshotTheme {
                Surface {
                    Box(Modifier.width(320.dp)) {
                        ActualMcpToolCard(
                            tool = McpTool(name = "fetch", needsApproval = false),
                            onEnableChange = onEnableChange,
                            onNeedsApprovalChange = onNeedsApprovalChange,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    companion object {
        private const val APPROVAL_LABEL = "Дополнительное подтверждение сервера"
        private const val APPROVAL_EXPLANATION =
            "Выключение этого флага не отменяет общую политику разрешений инструментов. " +
                "Сохранённые разрешения и режим «Я ГЛУПЫЙ» работают как прежде."

        // Render the existing private production card without changing it before the RED run.
        private val card = Class.forName("me.rerere.rikkahub.ui.pages.setting.SettingMcpPageKt")
            .declaredMethods.single { it.name == "McpToolCard" && it.parameterCount == 5 }
            .apply { isAccessible = true }

        @OptIn(InternalComposeApi::class)
        @Composable
        private fun ActualMcpToolCard(
            tool: McpTool,
            onEnableChange: (Boolean) -> Unit,
            onNeedsApprovalChange: (Boolean) -> Unit,
        ) {
            card.invoke(null, tool, onEnableChange, onNeedsApprovalChange, currentComposer, 0)
        }
    }
}
