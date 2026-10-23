package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDocumentOpenComposeTest {
    @get:Rule val compose = createComposeRule()
    @Before fun clearProviderCache() {
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
    }

    @Test fun `document chip forwards declared MIME even when its stored file has no extension`() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "chat-opaque-document").apply { writeText("fixture") }
        val message = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document(file.toUri().toString(), "Документ.pdf", "application/pdf"),
        ))
        val settings = ScreenshotFixtures.settings.let { it.copy(displaySetting = it.displaySetting.copy(
            showProducedFiles = false, showUserMessageActions = false,
        )) }
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides settings) {
                    ChatMessage(MessageNode.of(message), onFork = {}, onRegenerate = {}, onEdit = {},
                        onShare = {}, onDelete = {}, onUpdate = {})
                }
            }
        }
        while (shadowOf(app).nextStartedActivity != null) { }

        compose.onNodeWithText("Документ.pdf").performClick()

        val chooser = checkNotNull(shadowOf(app).nextStartedActivity)
        val target = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("application/pdf", target.type)
        assertEquals("content", target.data!!.scheme)
    }
}
