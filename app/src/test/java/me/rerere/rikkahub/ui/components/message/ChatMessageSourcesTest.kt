package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMessageSourcesTest {
    @get:Rule val compose = createComposeRule()
    private val openedUrls = mutableListOf<String>()

    @Test
    fun groundingWithoutUrlCitationsDoesNotShowSources() {
        render(listOf(UIMessageAnnotation.WebContentUsed))

        compose.onNodeWithText("Источники: 0").assertDoesNotExist()
        compose.onNodeWithText("Источники").assertDoesNotExist()
        compose.onNodeWithContentDescription("Закрыть источники").assertDoesNotExist()
    }

    @Test
    fun sourcesPillOpensSheetAndCloseAllowsReopening() {
        render(listOf(citation("Документация", "https://example.com/docs")))

        compose.onNodeWithText("Документация").assertDoesNotExist()
        compose.onNodeWithText("Источники: 1").performClick()
        compose.onNodeWithText("Источники").assertExists()
        compose.onNodeWithText("Документация").assertExists()
        compose.onNodeWithContentDescription("Закрыть источники").performClick()
        compose.onNodeWithText("Документация").assertDoesNotExist()
        compose.onNodeWithText("Источники: 1").performClick()
        compose.onNodeWithText("Документация").assertExists()
    }

    @Test
    fun clickingDecodedSourceTitleOpensItsOriginalUrl() {
        val url = "https://example.com/docs?q=one%20two"
        render(listOf(citation("Документация%20API", url)))

        compose.onNodeWithText("Источники: 1").performClick()
        compose.onNodeWithText("Документация API").performClick()
        compose.runOnIdle { assertEquals(listOf(url), openedUrls) }
    }

    @Test
    fun blankAndNumericTitlesUseSourceDomain() {
        render(listOf(
            citation("  ", "https://first.example/docs"),
            citation(" 42 ", "https://second.example/docs"),
        ))

        compose.onNodeWithText("Источники: 2").performClick()
        compose.onNodeWithText("first.example").performClick()
        compose.onNodeWithText("second.example").performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://first.example/docs", "https://second.example/docs"), openedUrls)
        }
    }

    @Test
    fun blankTitleWithoutDomainUsesOriginalUrl() {
        val url = "content://documentation/page"
        render(listOf(citation("", url)))

        compose.onNodeWithText("Источники: 1").performClick()
        compose.onNodeWithText(url).performClick()
        compose.runOnIdle { assertEquals(listOf(url), openedUrls) }
    }

    @Test
    fun longSourcesListScrollsAndKeepsOriginalUrlAssociations() {
        val annotations = (1..12).map { citation("Документ $it", "https://example.com/docs/$it") }
        render(annotations)

        compose.onNodeWithText("Источники: 12").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(11)
        compose.onNode(
            hasText("12") and hasAnyAncestor(hasTestTag("chat_message_source_card_12")),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("Документ 12").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.com/docs/12"), openedUrls) }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
    fun sourceCardRemainsClickableAtNarrowWidthAndLargeFont() {
        val title = "Очень длинное название источника с подробным описанием документации API"
        val url = "https://example.com/a/long/documentation/path"
        render(listOf(citation(title, url)), width = 320, fontScale = 1.3f)

        compose.onNodeWithText("Источники: 1").performClick()
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(listOf(url), openedUrls) }
        compose.onNodeWithContentDescription("Закрыть источники").performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    private fun citation(title: String, url: String) = UIMessageAnnotation.UrlCitation(title, url)

    private fun render(annotations: List<UIMessageAnnotation>, width: Int = 411, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { openedUrls += uri }
                },
                LocalDensity provides Density(1f, fontScale),
            ) {
                MaterialTheme {
                    Surface {
                        Box(Modifier.width(width.dp)) { ChatMessageCitations(annotations) }
                    }
                }
            }
        }
    }
}
